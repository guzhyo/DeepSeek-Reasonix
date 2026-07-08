package com.nousresearch.reasonix;

import android.content.Context;
import android.view.View;
import android.view.inputmethod.InputMethodManager;

public class ShowKeyboardTask implements Runnable {
    TerminalView view;
    ShowKeyboardTask(TerminalView v) { view = v; }

    public void run() {
        InputMethodManager imm = (InputMethodManager) view.getContext()
            .getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT);
        }
    }
}
