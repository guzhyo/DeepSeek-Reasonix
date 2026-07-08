package com.nousresearch.reasonix;

public class ShowExitTask implements Runnable {
    TerminalActivity activity;
    int exitCode;
    ShowExitTask(TerminalActivity a, int c) { activity = a; exitCode = c; }

    public void run() { activity.showExit(exitCode); }
}
