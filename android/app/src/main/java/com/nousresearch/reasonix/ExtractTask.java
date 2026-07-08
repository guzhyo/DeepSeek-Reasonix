package com.nousresearch.reasonix;

import java.io.File;

public class ExtractTask implements Runnable {
    TerminalActivity activity;
    ExtractTask(TerminalActivity a) { activity = a; }

    public void run() {
        try {
            // Use binary directly from native library dir (SELinux-compatible)
            // Copying to app data dir prevents exec() due to SELinux
            String libDir = activity.getApplicationInfo().nativeLibraryDir;
            File source = new File(libDir, "libreasonix.so");

            if (!source.exists()) {
                activity.mainHandler.post(
                    new ShowErrorTask(activity, "Binary not found: " + source.getAbsolutePath()));
                return;
            }

            final String binaryPath = source.getAbsolutePath();
            activity.mainHandler.post(new StartTask(activity, binaryPath));
        } catch (final Exception e) {
            activity.mainHandler.post(
                new ShowErrorTask(activity, "Extract failed: " + e.getMessage()));
        }
    }
}
