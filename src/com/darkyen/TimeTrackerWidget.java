package com.darkyen;

import com.intellij.ide.ui.UISettings;
import com.intellij.notification.*;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.DocumentAdapter;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.editor.event.EditorEventMulticaster;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.CustomStatusBarWidget;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.ui.JBColor;
import com.intellij.util.concurrency.EdtExecutorService;
import com.intellij.util.ui.EmptyIcon;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.AWTEventListener;
import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 *
 */
public final class TimeTrackerWidget extends JButton implements CustomStatusBarWidget, AWTEventListener {

    private static final NotificationGroup NOTIFICATION_GROUP = new NotificationGroup("Darkyenus Time Tracker", NotificationDisplayType.BALLOON, false, null, EmptyIcon.ICON_0);

    private final Project project;

    private TimeTrackerState state = new TimeTrackerState();

    private boolean running = false;
    private long startedAtMs = 0;

    private boolean idle = false;
    private long lastActivityAtMs = System.currentTimeMillis();
    private long lastTickAt;

    private ScheduledFuture<?> ticker;
    private long tickTooLongMs;

    private DocumentListener autoStartDocumentListener = null;

    TimeTrackerWidget(Project project) {
        this.project = project;
        addActionListener(e -> setRunning(!running));
        setBorder(StatusBarWidget.WidgetBorder.INSTANCE);
        setOpaque(false);
        setFocusable(false);

        setupAutoStartDocumentListener(state.autoStart);
    }

    void setState(TimeTrackerState state) {
        repaint();
        this.state = state;
        setupAutoStartDocumentListener(state.autoStart);
    }

    private void foldRunningTime() {
        final long runningForSeconds = runningForSeconds();
        if (runningForSeconds > 0) {
            state.totalTimeSeconds += runningForSeconds;
            startedAtMs += runningForSeconds * 1000;
        }
    }

    synchronized TimeTrackerState getState() {
        checkForTimeJump(false);
        foldRunningTime();
        return state;
    }

    private long runningForSeconds() {
        if (!running) {
            return 0;
        } else {
            return Math.max(System.currentTimeMillis() - startedAtMs, 0) / 1000;
        }
    }

    private synchronized void checkForTimeJump(boolean stop) {
        final long now = System.currentTimeMillis();
        final long sinceLastTick = now - lastTickAt;
        if (this.running && sinceLastTick > tickTooLongMs) {
            lastTickAt = now;
            final long frozenForSec = (sinceLastTick - (tickTooLongMs / 2)) / 1000;
            foldRunningTime();
            if (stop) {
                setRunning(false);
                idle = true;
            }
            state.totalTimeSeconds -= frozenForSec;

            final Notification notification = NOTIFICATION_GROUP.createNotification(
                    "Hibernation or freeze detected",
                    "For " + formatDuration(sinceLastTick / 1000) + ".<br>This time is not counted. <a href=\"revert\">Count anyway</a>.",
                    NotificationType.WARNING,
                    (n, event) -> {
                        if ("revert".equals(event.getDescription())) {
                            n.expire();
                            state.totalTimeSeconds += frozenForSec;
                            repaint();
                        }
                    });
            Notifications.Bus.notify(notification, project);
        }
    }

    private synchronized void setRunning(boolean running) {
        if (!this.running && running) {
            repaint();
            this.idle = false;
            this.running = true;
            this.startedAtMs = System.currentTimeMillis();

            if (ticker != null) {
                ticker.cancel(false);
            }
            lastTickAt = System.currentTimeMillis();
            final long tickDelay = 1;
            final TimeUnit tickDelayUnit = TimeUnit.SECONDS;
            tickTooLongMs = tickDelayUnit.toMillis(tickDelay) * 20;//20 sec is too long
            ticker = EdtExecutorService.getScheduledExecutorInstance().scheduleWithFixedDelay(() -> UIUtil.invokeLaterIfNeeded(() -> {
                checkForTimeJump(true);
                lastTickAt = System.currentTimeMillis();
                if (System.currentTimeMillis() - lastActivityAtMs > state.idleThresholdMs) {
                    if (this.running) {
                        setRunning(false);
                        idle = true;
                    }
                }
                repaint();
            }), tickDelay, tickDelay, tickDelayUnit);
        } else if(this.running && !running) {
            checkForTimeJump(false);
            state.totalTimeSeconds += runningForSeconds();
            this.running = false;

            if (ticker != null) {
                ticker.cancel(false);
                ticker = null;
            }
            repaint();
        }
    }

    @NotNull
    @Override
    public String ID() {
        return "com.darkyen.DarkyenusTimeTracker";
    }

    @Nullable
    @Override
    public WidgetPresentation getPresentation(@NotNull PlatformType type) {
        return null;
    }

    @Override
    public void install(@NotNull StatusBar statusBar) {
        Toolkit.getDefaultToolkit().addAWTEventListener(this,
                AWTEvent.KEY_EVENT_MASK |
                        AWTEvent.MOUSE_EVENT_MASK |
                        AWTEvent.MOUSE_MOTION_EVENT_MASK
        );
    }

    private void setupAutoStartDocumentListener(boolean enabled) {
        final EditorEventMulticaster editorEventMulticaster = EditorFactory.getInstance().getEventMulticaster();
        if (autoStartDocumentListener != null) {
            editorEventMulticaster.removeDocumentListener(autoStartDocumentListener);
            autoStartDocumentListener = null;
        }
        if (enabled) {
            editorEventMulticaster.addDocumentListener(autoStartDocumentListener = new DocumentAdapter() {
                @Override
                public void documentChanged(DocumentEvent e) {
                    if (running) return;
                    final Editor selectedTextEditor = FileEditorManager.getInstance(project).getSelectedTextEditor();
                    if (selectedTextEditor == null) return;
                    if(e.getDocument().equals(selectedTextEditor.getDocument())) {
                        setRunning(true);
                    }
                }
            });
        }
    }

    @Override
    public void dispose() {
        setupAutoStartDocumentListener(false);
        Toolkit.getDefaultToolkit().removeAWTEventListener(this);
        setRunning(false);
    }

    private static final Color COLOR_OFF = new JBColor(new Color(189, 0, 16), new Color(128, 0, 0));
    private static final Color COLOR_ON = new JBColor(new Color(28, 152, 19), new Color(56, 113, 41));
    private static final Color COLOR_IDLE = new JBColor(new Color(200, 164, 23), new Color(163, 112, 17));

    @Override
    public void paintComponent(final Graphics g) {
        long result;
        synchronized (this) {
            checkForTimeJump(true);
            result = state.totalTimeSeconds + runningForSeconds();
        }
        final String info = formatDuration(result);

        final Dimension size = getSize();
        final Insets insets = getInsets();

        final int totalBarLength = size.width - insets.left - insets.right;
        final int barHeight = Math.max(size.height, getFont().getSize() + 2);
        final int yOffset = (size.height - barHeight) / 2;
        final int xOffset = insets.left;

        g.setColor(running ? COLOR_ON : (idle ? COLOR_IDLE : COLOR_OFF));
        g.fillRect(insets.left, insets.bottom, totalBarLength, size.height - insets.bottom - insets.top);

        final Color fg = getModel().isPressed() ? UIUtil.getLabelDisabledForeground() : JBColor.foreground();
        g.setColor(fg);
        UISettings.setupAntialiasing(g);
        g.setFont(getWidgetFont());
        final FontMetrics fontMetrics = g.getFontMetrics();
        final int infoWidth = fontMetrics.charsWidth(info.toCharArray(), 0, info.length());
        final int infoHeight = fontMetrics.getAscent();
        g.drawString(info, xOffset + (totalBarLength - infoWidth) / 2, yOffset + infoHeight + (barHeight - infoHeight) / 2 - 1);
    }

    private static String formatDuration(long secondDuration) {
        final Duration duration = Duration.ofSeconds(secondDuration);
        final StringBuilder sb = new StringBuilder();

        boolean found = false;
        boolean secondsRelevant = true;
        final long days = duration.toDays();
        if(days != 0) {
            found = true;
            secondsRelevant = false;
            sb.append(days).append(" day");
            if (days != 1) {
                sb.append("s");
            }
        }
        final long hours = duration.toHours() % 24;
        if(found || hours != 0) {
            if(found) {
                sb.append(" ");
            }
            found = true;
            secondsRelevant = false;
            sb.append(hours).append(" hour");
            if (hours != 1) {
                sb.append("s");
            }
        }
        final long minutes = duration.toMinutes() % 60;
        if(found || minutes != 0) {
            if(found) {
                sb.append(" ");
            }
            found = true;
            sb.append(minutes).append(" min");/*
            if (minutes != 1) {
                sb.append("s");
            }*/
        }
        if(secondsRelevant) {
            final long seconds = duration.getSeconds() % 60;
            {
                if(found) {
                    sb.append(" ");
                }
                sb.append(seconds).append(" sec");/*
            if (seconds != 1) {
                sb.append("s");
            }*/
            }
        }
        return sb.toString();
    }

    @Override
    public JComponent getComponent() {
        return this;
    }

    private static Font getWidgetFont() {
        return JBUI.Fonts.label(11);
    }

    private static final String SAMPLE_STRING = formatDuration(999999999999L);
    @Override
    public Dimension getPreferredSize() {
        final Insets insets = getInsets();
        int width = getFontMetrics(getWidgetFont()).stringWidth(SAMPLE_STRING) + insets.left + insets.right + JBUI.scale(2);
        int height = getFontMetrics(getWidgetFont()).getHeight() + insets.top + insets.bottom + JBUI.scale(2);
        return new Dimension(width, height);
    }

    @Override
    public Dimension getMinimumSize() {
        return getPreferredSize();
    }

    @Override
    public Dimension getMaximumSize() {
        return getPreferredSize();
    }

    @Override
    public void eventDispatched(AWTEvent event) {
        lastActivityAtMs = System.currentTimeMillis();
        if (idle) {
            idle = false;
            setRunning(true);
        }
    }
}
