package com.nousresearch.reasonix;

import android.view.ViewGroup;
import android.widget.FrameLayout;

public class StartTask implements Runnable {
    TerminalActivity activity;
    String binaryPath;
    StartTask(TerminalActivity a, String p) { activity = a; binaryPath = p; }

    public void run() {
        try {
            activity.terminalScreen = new TerminalScreen(
                TerminalActivity.DEFAULT_COLS, TerminalActivity.DEFAULT_ROWS);
            activity.terminalView = new TerminalView(activity);
            FrameLayout layout = new FrameLayout(activity);
            layout.addView(activity.terminalView,
                new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            activity.setContentView(layout);

            activity.sessionId = TerminalBridge.createPty(binaryPath,
                new String[]{}, TerminalActivity.DEFAULT_ROWS, TerminalActivity.DEFAULT_COLS);

            if (activity.sessionId < 0) {
                activity.showError("Failed to create PTY session");
                return;
            }

            activity.terminalView.attachSession(activity.sessionId, activity.terminalScreen);
            activity.running = true;
            new Thread(new ReaderTask(activity)).start();
        } catch (Exception e) {
            activity.showError("Start failed: " + e.getMessage());
        }
    }
}
