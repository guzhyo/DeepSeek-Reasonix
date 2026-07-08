package com.nousresearch.reasonix;

public class ReaderTask implements Runnable {
    TerminalActivity activity;
    ReaderTask(TerminalActivity a) { activity = a; }

    public void run() {
        byte[] buf = new byte[4096];
        while (activity.running) {
            int n = TerminalBridge.readPty(activity.sessionId, buf);
            if (n > 0) {
                final int len = n;
                final byte[] data = new byte[len];
                System.arraycopy(buf, 0, data, 0, len);
                activity.mainHandler.post(new FeedOutputTask(activity, data, len));
            } else if (n == -2) {
                activity.running = false;
                final int exitCode = TerminalBridge.waitPty(activity.sessionId);
                activity.mainHandler.post(new ShowExitTask(activity, exitCode));
                break;
            } else if (n < 0) {
                activity.running = false;
                activity.mainHandler.post(new ShowErrorTask(activity, "PTY read error"));
                break;
            } else {
                try { Thread.sleep(20); } catch (InterruptedException e) { break; }
            }
        }
    }
}
