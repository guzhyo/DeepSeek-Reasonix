package com.nousresearch.reasonix;

import android.content.Context;
import android.graphics.Canvas;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

public class TerminalView extends View {
    private TerminalScreen screen;
    private int sessionId;
    private int cols, rows;

    public TerminalView(Context context) {
        super(context);
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    public void attachSession(int sessionId, TerminalScreen screen) {
        this.sessionId = sessionId;
        this.screen = screen;
        this.cols = screen.getCols();
        this.rows = screen.getRows();
        requestFocus();
        showKeyboard();
    }

    private void showKeyboard() {
        postDelayed(new ShowKeyboardTask(this), 200);
    }

    public boolean onCheckIsTextEditor() {
        return true;
    }

    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            requestFocus();
            showKeyboard();
        }
        return true;
    }

    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (screen != null && w > 0 && h > 0) {
            float cellW = w / (float) cols;
            float cellH = h / (float) rows;
            int newCols = Math.max(1, (int) (w / cellW));
            int newRows = Math.max(1, (int) (h / cellH));
            if (newCols != cols || newRows != rows) {
                cols = newCols;
                rows = newRows;
                screen.resize(cols, rows);
                TerminalBridge.resizePty(sessionId, rows, cols);
            }
        }
    }

    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (screen != null) {
            canvas.drawColor(0xFF1E1E2E);
            screen.render(canvas, getWidth(), getHeight());
        }
    }

    public boolean feedOutput(byte[] data, int len) {
        if (screen == null) return false;
        boolean modified = screen.feed(data, 0, len);
        if (modified) {
            postInvalidate();
        }
        return modified;
    }

    private byte[] keyToBytes(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return null;

        int keyCode = event.getKeyCode();
        int metaState = event.getMetaState();
        boolean ctrl = (metaState & KeyEvent.META_CTRL_ON) != 0;

        switch (keyCode) {
            case KeyEvent.KEYCODE_ENTER: return new byte[]{'\r'};
            case KeyEvent.KEYCODE_DEL: return new byte[]{'\b'};
            case KeyEvent.KEYCODE_FORWARD_DEL: return "\033[3~".getBytes();
            case KeyEvent.KEYCODE_TAB: return new byte[]{'\t'};
            case KeyEvent.KEYCODE_DPAD_UP: return "\033[A".getBytes();
            case KeyEvent.KEYCODE_DPAD_DOWN: return "\033[B".getBytes();
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "\033[C".getBytes();
            case KeyEvent.KEYCODE_DPAD_LEFT: return "\033[D".getBytes();
            case KeyEvent.KEYCODE_ESCAPE: case KeyEvent.KEYCODE_BACK:
                return new byte[]{0x1B};
            default:
                int unicode = event.getUnicodeChar(metaState);
                if (unicode != 0) {
                    if (ctrl && unicode >= 'a' && unicode <= 'z')
                        return new byte[]{(byte)(unicode - 'a' + 1)};
                    if (ctrl && unicode >= 'A' && unicode <= 'Z')
                        return new byte[]{(byte)(unicode - 'A' + 1)};
                    return String.valueOf((char) unicode).getBytes();
                }
                return null;
        }
    }

    public boolean onKeyDown(int keyCode, KeyEvent event) {
        byte[] bytes = keyToBytes(event);
        if (bytes != null) {
            TerminalBridge.writePty(sessionId, bytes);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        outAttrs.inputType = EditorInfo.TYPE_NULL;
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN;
        return new TerminalInput(this, sessionId, this);
    }
}
