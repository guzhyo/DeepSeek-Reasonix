package com.nousresearch.reasonix;

public class ShowErrorTask implements Runnable {
    TerminalActivity activity;
    String msg;
    ShowErrorTask(TerminalActivity a, String m) { activity = a; msg = m; }

    public void run() { activity.showError(msg); }
}
