package com.nousresearch.reasonix;

import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;

/**
 * Represents a single cell in the terminal screen.
 */
class TerminalCell {
    char ch = ' ';
    int fgColor = 0xFFCDD6F4;  // Catppuccin text
    int bgColor = 0xFF1E1E2E;  // Catppuccin base
    boolean bold = false;
    boolean italic = false;
    boolean underline = false;

    void reset() {
        ch = ' ';
        fgColor = 0xFFCDD6F4;
        bgColor = 0xFF1E1E2E;
        bold = false;
        italic = false;
        underline = false;
    }
}
