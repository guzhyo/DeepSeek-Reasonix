package com.nousresearch.reasonix;

public class FeedOutputTask implements Runnable {
    TerminalActivity activity;
    byte[] data;
    int len;
    FeedOutputTask(TerminalActivity a, byte[] d, int l) { activity = a; data = d; len = l; }

    public void run() {
        if (activity.terminalView != null)
            activity.terminalView.feedOutput(data, len);
    }
}
