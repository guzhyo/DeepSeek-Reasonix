package com.nousresearch.reasonix;

import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;

public class TerminalInput extends BaseInputConnection {
    private int sessionId;
    private TerminalView view;

    public TerminalInput(View targetView, int sid, TerminalView v) {
        super(targetView, false);
        sessionId = sid;
        view = v;
    }

    public boolean commitText(CharSequence text, int pos) {
        if (text != null) {
            byte[] b = text.toString().getBytes();
            TerminalBridge.writePty(sessionId, b);
        }
        return true;
    }

    public boolean sendKeyEvent(KeyEvent event) {
        return view.onKeyDown(event.getKeyCode(), event);
    }

    public boolean deleteSurroundingText(int before, int after) {
        for (int i = 0; i < before; i++)
            TerminalBridge.writePty(sessionId, new byte[]{'\b'});
        return true;
    }
}
