package com.nousresearch.reasonix;

import java.util.ArrayList;

/**
 * JNI bridge for PTY management.
 * Native library: libptyjni.so
 */
public class TerminalBridge {
    static {
        System.loadLibrary("ptyjni");
    }

    // Create a PTY and fork a child with the given command
    public static native int createPty(String cmd, String[] args, int rows, int cols);

    // Read from PTY master. Returns bytes read, 0 for no data, -1 for error, -2 for EOF
    public static native int readPty(int sessionId, byte[] buf);

    // Write to PTY master
    public static native int writePty(int sessionId, byte[] buf);

    // Resize terminal
    public static native void resizePty(int sessionId, int rows, int cols);

    // Check if child exited. Returns exit code, -256 if still running, -1 on error
    public static native int waitPty(int sessionId);

    // Close PTY and kill child
    public static native void closePty(int sessionId);
}
