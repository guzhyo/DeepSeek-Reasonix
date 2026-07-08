package com.nousresearch.reasonix;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;

public class TerminalActivity extends Activity {
    TerminalView terminalView;
    TerminalScreen terminalScreen;
    int sessionId = -1;
    boolean running = false;
    Handler mainHandler;
    static final int DEFAULT_COLS = 80;
    static final int DEFAULT_ROWS = 24;

    protected void onCreate(android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mainHandler = new Handler(Looper.getMainLooper());
        new Thread(new ExtractTask(this)).start();
    }

    void showError(String message) {
        FrameLayout layout = new FrameLayout(this);
        TextView errorText = new TextView(this);
        errorText.setText("Error: " + message);
        errorText.setTextSize(16);
        errorText.setGravity(Gravity.CENTER);
        errorText.setPadding(48, 48, 48, 48);
        layout.addView(errorText,
            new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(layout);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    void showExit(int exitCode) {
        Toast.makeText(this, "Exited: " + exitCode, Toast.LENGTH_LONG).show();
    }

    protected void onResume() {
        super.onResume();
        if (terminalView != null) terminalView.requestFocus();
    }

    protected void onDestroy() {
        super.onDestroy();
        running = false;
        if (sessionId >= 0) TerminalBridge.closePty(sessionId);
    }
}
