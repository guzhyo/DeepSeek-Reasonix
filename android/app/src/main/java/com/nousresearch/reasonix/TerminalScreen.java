package com.nousresearch.reasonix;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;

/**
 * Terminal screen buffer with ANSI escape code parser.
 * Renders to a Canvas.
 */
public class TerminalScreen {
    // Catppuccin Mocha palette
    private static final int[] PALETTE = {
        0xFF45475A, // 0: black
        0xFFF38BA8, // 1: red
        0xFFA6E3A1, // 2: green
        0xFFF9E2AF, // 3: yellow
        0xFF89B4FA, // 4: blue
        0xFFF5C2E7, // 5: magenta
        0xFF94E2D5, // 6: cyan
        0xFFBAC2DE, // 7: white
        0xFF585B70, // 8: bright black
        0xFFF38BA8, // 9: bright red
        0xFFA6E3A1, // 10: bright green
        0xFFF9E2AF, // 11: bright yellow
        0xFF89B4FA, // 12: bright blue
        0xFFF5C2E7, // 13: bright magenta
        0xFF94E2D5, // 14: bright cyan
        0xFFA6ADC8  // 15: bright white
    };

    private int cols;
    private int rows;
    private TerminalCell[][] cells;
    private TerminalCell defaultCell = new TerminalCell();

    // Cursor
    private int cursorX = 0;
    private int cursorY = 0;
    private boolean cursorVisible = true;

    // SGR state
    private int currentFg = 0xFFCDD6F4;
    private int currentBg = 0xFF1E1E2E;
    private boolean currentBold = false;
    private boolean currentItalic = false;
    private boolean currentUnderline = false;

    // Rendering
    private Paint textPaint;
    private float cellWidth;
    private float cellHeight;

    // Parser state
    private StringBuilder escapeBuf = new StringBuilder();
    private boolean inEscape = false;
    private boolean inCsi = false;

    public TerminalScreen(int cols, int rows) {
        resize(cols, rows);
        initPaint();
    }

    private void initPaint() {
        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setTypeface(Typeface.MONOSPACE);
        textPaint.setTextSize(32);
    }

    public void resize(int cols, int rows) {
        this.cols = cols;
        this.rows = rows;
        cells = new TerminalCell[rows][cols];
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < cols; x++) {
                cells[y][x] = new TerminalCell();
            }
        }
        cursorX = 0;
        cursorY = 0;
    }

    public int getCols() { return cols; }
    public int getRows() { return rows; }

    // UTF-8 decoder state
    private int utf8Remaining = 0;
    private int utf8Codepoint = 0;

    /**
     * Feed bytes from PTY into the terminal parser.
     * Handles UTF-8 multi-byte sequences before dispatching to character processor.
     * Returns true if screen was modified.
     */
    public boolean feed(byte[] data, int offset, int length) {
        boolean modified = false;
        for (int i = offset; i < offset + length; i++) {
            int b = data[i] & 0xFF;
            if (utf8Remaining > 0) {
                // Continuation byte
                if ((b & 0xC0) == 0x80) {
                    utf8Codepoint = (utf8Codepoint << 6) | (b & 0x3F);
                    utf8Remaining--;
                    if (utf8Remaining == 0) {
                        modified |= processUtf8Char(utf8Codepoint);
                    }
                } else {
                    // Invalid sequence: flush and restart
                    modified |= processChar((char) utf8Codepoint);
                    utf8Remaining = 0;
                    modified |= processChar((char) b);
                }
            } else if ((b & 0x80) == 0) {
                // ASCII
                modified |= processChar((char) b);
            } else if ((b & 0xE0) == 0xC0) {
                utf8Codepoint = b & 0x1F;
                utf8Remaining = 1;
            } else if ((b & 0xF0) == 0xE0) {
                utf8Codepoint = b & 0x0F;
                utf8Remaining = 2;
            } else if ((b & 0xF8) == 0xF0) {
                utf8Codepoint = b & 0x07;
                utf8Remaining = 3;
            } else {
                // Invalid start byte, treat as-is
                modified |= processChar((char) b);
            }
        }
        return modified;
    }

    private boolean processUtf8Char(int codepoint) {
        if (codepoint <= 0xFFFF) {
            return processChar((char) codepoint);
        } else {
            // Supplementary planes (emoji, etc.) — encode as surrogate pair
            int sp = codepoint - 0x10000;
            boolean mod = processChar((char) (0xD800 | (sp >> 10)));
            mod |= processChar((char) (0xDC00 | (sp & 0x3FF)));
            return mod;
        }
    }

    private boolean processChar(char c) {
        if (inEscape) {
            return handleEscape(c);
        }
        if (c == 0x1B) { // ESC
            inEscape = true;
            escapeBuf.setLength(0);
            return false;
        }
        return handleNormal(c);
    }

    private boolean handleEscape(char c) {
        if (!inCsi) {
            if (c == '[') {
                inCsi = true;
                escapeBuf.append(c);
                return false;
            }
            // Simple escape sequences
            inEscape = false;
            switch (c) {
                case '7': saveCursor(); return true;
                case '8': restoreCursor(); return true;
                case 'D': scrollDown(); return true;
                case 'M': scrollUp(); return true;
                case 'c': resetTerminal(); return true;
                default: return false;
            }
        }

        escapeBuf.append(c);
        if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '~') {
            inEscape = false;
            inCsi = false;
            return processCsi(escapeBuf.toString());
        }
        return false;
    }

    private boolean processCsi(String seq) {
        char cmd = seq.charAt(seq.length() - 1);
        String params = seq.substring(1, seq.length() - 1); // remove '[' and cmd

        int[] vals = parseParams(params);

        switch (cmd) {
            case 'A': // cursor up
                cursorY = Math.max(0, cursorY - (vals.length > 0 ? vals[0] : 1));
                break;
            case 'B': // cursor down
                cursorY = Math.min(rows - 1, cursorY + (vals.length > 0 ? vals[0] : 1));
                break;
            case 'C': // cursor forward
                cursorX = Math.min(cols - 1, cursorX + (vals.length > 0 ? vals[0] : 1));
                break;
            case 'D': // cursor back
                cursorX = Math.max(0, cursorX - (vals.length > 0 ? vals[0] : 1));
                break;
            case 'H': // cursor position
            case 'f':
                cursorY = (vals.length > 0 ? vals[0] : 1) - 1;
                cursorX = (vals.length > 1 ? vals[1] : 1) - 1;
                cursorY = Math.max(0, Math.min(rows - 1, cursorY));
                cursorX = Math.max(0, Math.min(cols - 1, cursorX));
                break;
            case 'J': // erase in display
                eraseDisplay(vals.length > 0 ? vals[0] : 0);
                return true;
            case 'K': // erase in line
                eraseLine(vals.length > 0 ? vals[0] : 0);
                return true;
            case 'L': // insert lines
                insertLines(vals.length > 0 ? vals[0] : 1);
                return true;
            case 'M': // delete lines
                deleteLines(vals.length > 0 ? vals[0] : 1);
                return true;
            case 'P': // delete chars
                deleteChars(vals.length > 0 ? vals[0] : 1);
                return true;
            case 'm': // SGR
                applySgr(vals);
                break;
            case 'h': // set mode
                if (params.equals("?25")) cursorVisible = true;
                break;
            case 'l': // reset mode
                if (params.equals("?25")) cursorVisible = false;
                break;
            case 's': saveCursor(); break;
            case 'u': restoreCursor(); break;
            case 'n': // device status report
                break;
        }
        return false;
    }

    private int[] parseParams(String s) {
        if (s.isEmpty()) return new int[0];
        String[] parts = s.split(";");
        int[] vals = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                vals[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                vals[i] = 1;
            }
        }
        return vals;
    }

    private boolean handleNormal(char c) {
        switch (c) {
            case '\r': cursorX = 0; return false;
            case '\n': newline(); return true;
            case '\b': if (cursorX > 0) cursorX--; return false;
            case '\t': cursorX = (cursorX + 8) & ~7; if (cursorX >= cols) { cursorX = 0; newline(); } return false;
            case '\007': return false; // bell
            default:
                putChar(c);
                return true;
        }
    }

    private void putChar(char c) {
        if (cursorX >= cols) {
            cursorX = 0;
            newline();
        }
        TerminalCell cell = cells[cursorY][cursorX];
        cell.ch = c;
        cell.fgColor = currentFg;
        cell.bgColor = currentBg;
        cell.bold = currentBold;
        cell.italic = currentItalic;
        cell.underline = currentUnderline;
        cursorX++;
    }

    private void newline() {
        cursorY++;
        if (cursorY >= rows) {
            scrollUp();
            cursorY = rows - 1;
        }
    }

    private void scrollUp() {
        for (int y = 0; y < rows - 1; y++) {
            System.arraycopy(cells[y + 1], 0, cells[y], 0, cols);
        }
        for (int x = 0; x < cols; x++) {
            cells[rows - 1][x].reset();
        }
    }

    private void scrollDown() {
        for (int y = rows - 1; y > 0; y--) {
            System.arraycopy(cells[y - 1], 0, cells[y], 0, cols);
        }
        for (int x = 0; x < cols; x++) {
            cells[0][x].reset();
        }
    }

    private void eraseDisplay(int mode) {
        switch (mode) {
            case 0: // cursor to end
                eraseLine(0);
                for (int y = cursorY + 1; y < rows; y++)
                    for (int x = 0; x < cols; x++)
                        cells[y][x].reset();
                break;
            case 1: // start to cursor
                for (int y = 0; y < cursorY; y++)
                    for (int x = 0; x < cols; x++)
                        cells[y][x].reset();
                eraseLine(1);
                break;
            case 2: // entire screen
                for (int y = 0; y < rows; y++)
                    for (int x = 0; x < cols; x++)
                        cells[y][x].reset();
                cursorX = 0; cursorY = 0;
                break;
        }
    }

    private void eraseLine(int mode) {
        switch (mode) {
            case 0: // cursor to end
                for (int x = cursorX; x < cols; x++)
                    cells[cursorY][x].reset();
                break;
            case 1: // start to cursor
                for (int x = 0; x <= cursorX; x++)
                    cells[cursorY][x].reset();
                break;
            case 2: // entire line
                for (int x = 0; x < cols; x++)
                    cells[cursorY][x].reset();
                break;
        }
    }

    private void insertLines(int n) {
        for (int y = rows - 1; y >= cursorY + n; y--) {
            System.arraycopy(cells[y - n], 0, cells[y], 0, cols);
        }
        for (int y = cursorY; y < cursorY + n && y < rows; y++) {
            for (int x = 0; x < cols; x++) cells[y][x].reset();
        }
    }

    private void deleteLines(int n) {
        for (int y = cursorY; y < rows - n; y++) {
            System.arraycopy(cells[y + n], 0, cells[y], 0, cols);
        }
        for (int y = rows - n; y < rows; y++) {
            for (int x = 0; x < cols; x++) cells[y][x].reset();
        }
    }

    private void deleteChars(int n) {
        for (int x = cursorX; x < cols - n; x++) {
            cells[cursorY][x] = cells[cursorY][x + n];
        }
        for (int x = cols - n; x < cols; x++) {
            cells[cursorY][x].reset();
        }
    }

    private void applySgr(int[] vals) {
        if (vals.length == 0) vals = new int[]{0};
        for (int i = 0; i < vals.length; i++) {
            int v = vals[i];
            switch (v) {
                case 0: // reset
                    currentFg = 0xFFCDD6F4; currentBg = 0xFF1E1E2E;
                    currentBold = false; currentItalic = false; currentUnderline = false;
                    break;
                case 1: currentBold = true; break;
                case 3: currentItalic = true; break;
                case 4: currentUnderline = true; break;
                case 22: currentBold = false; break;
                case 23: currentItalic = false; break;
                case 24: currentUnderline = false; break;
                case 30: case 31: case 32: case 33: case 34: case 35: case 36: case 37:
                    currentFg = PALETTE[v - 30]; break;
                case 38: // extended fg
                    if (i + 2 < vals.length) {
                        if (vals[i + 1] == 5) { // 256-color
                            int idx = vals[i + 2];
                            if (idx >= 0 && idx < 256) currentFg = xterm256Color(idx);
                            i += 2;
                        } else if (vals[i + 1] == 2 && i + 4 < vals.length) { // true color: 38;2;R;G;B
                            int r = vals[i + 2], g = vals[i + 3], b = vals[i + 4];
                            currentFg = (0xFF << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
                            i += 4;
                        }
                    }
                    break;
                case 39: currentFg = 0xFFCDD6F4; break;
                case 40: case 41: case 42: case 43: case 44: case 45: case 46: case 47:
                    currentBg = PALETTE[v - 40]; break;
                case 48: // extended bg
                    if (i + 2 < vals.length) {
                        if (vals[i + 1] == 5) { // 256-color
                            int idx = vals[i + 2];
                            if (idx >= 0 && idx < 256) currentBg = xterm256Color(idx);
                            i += 2;
                        } else if (vals[i + 1] == 2 && i + 4 < vals.length) { // true color: 48;2;R;G;B
                            int r = vals[i + 2], g = vals[i + 3], b = vals[i + 4];
                            currentBg = (0xFF << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
                            i += 4;
                        }
                    }
                    break;
                case 49: currentBg = 0xFF1E1E2E; break;
                case 90: case 91: case 92: case 93: case 94: case 95: case 96: case 97:
                    currentFg = PALETTE[v - 82]; break;
                case 100: case 101: case 102: case 103: case 104: case 105: case 106: case 107:
                    currentBg = PALETTE[v - 92]; break;
            }
        }
    }

    private int xterm256Color(int idx) {
        if (idx < 16) return PALETTE[idx % 16];
        if (idx < 232) {
            idx -= 16;
            int r = (idx / 36) * 51;
            int g = ((idx / 6) % 6) * 51;
            int b = (idx % 6) * 51;
            return (0xFF << 24) | (r << 16) | (g << 8) | b;
        }
        int gray = (idx - 232) * 10 + 8;
        return (0xFF << 24) | (gray << 16) | (gray << 8) | gray;
    }

    // Cursor save/restore
    private int savedX, savedY;
    private void saveCursor() { savedX = cursorX; savedY = cursorY; }
    private void restoreCursor() { cursorX = savedX; cursorY = savedY; }

    private void resetTerminal() {
        for (int y = 0; y < rows; y++)
            for (int x = 0; x < cols; x++)
                cells[y][x].reset();
        cursorX = 0; cursorY = 0;
        currentFg = 0xFFCDD6F4; currentBg = 0xFF1E1E2E;
    }

    /**
     * Render the screen to a Canvas.
     */
    public void render(Canvas canvas, float width, float height) {
        if (canvas == null || rows == 0 || cols == 0) return;

        cellWidth = width / cols;
        cellHeight = height / rows;

        float textSize = cellHeight * 0.85f;
        textPaint.setTextSize(textSize);

        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float baseline = (cellHeight - fm.ascent - fm.descent) / 2;

        for (int y = 0; y < rows; y++) {
            float top = y * cellHeight;
            for (int x = 0; x < cols; x++) {
                TerminalCell cell = cells[y][x];
                float left = x * cellWidth;

                // Draw background
                textPaint.setColor(cell.bgColor);
                canvas.drawRect(left, top, left + cellWidth, top + cellHeight, textPaint);

                // Draw character
                if (cell.ch != ' ' && cell.ch != 0) {
                    int fg = cell.bold ? makeBrighter(cell.fgColor) : cell.fgColor;
                    textPaint.setColor(fg);
                    textPaint.setFakeBoldText(cell.bold);
                    textPaint.setUnderlineText(cell.underline);

                    String str = String.valueOf(cell.ch);
                    float charWidth = textPaint.measureText(str);
                    float cx = left + (cellWidth - charWidth) / 2;
                    canvas.drawText(str, cx, top + baseline, textPaint);
                }
            }
        }

        // Draw cursor
        if (cursorVisible && cursorY < rows && cursorX < cols) {
            float cx = cursorX * cellWidth;
            float cy = cursorY * cellHeight;
            textPaint.setColor(0x80CDD6F4);
            canvas.drawRect(cx, cy + cellHeight - 4, cx + cellWidth, cy + cellHeight, textPaint);
        }
    }

    private int makeBrighter(int color) {
        int a = (color >> 24) & 0xFF;
        int r = Math.min(255, ((color >> 16) & 0xFF) + 40);
        int g = Math.min(255, ((color >> 8) & 0xFF) + 40);
        int b = Math.min(255, (color & 0xFF) + 40);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
