package bili.dongsz.howtogo.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

/**
 * Hand-drawn multi-line plain-text editor for the place description.
 * Not a widget: EditBox is single-line and MultiLineEditBox does not exist before 1.20.5 while this tree still builds 1.20.1, so one hand-drawn field keeps a single implementation across nodes.
 * Committed text arrives through Screen.charTyped: input methods deliver text as committed characters, never GLFW key codes, so key codes only handle editing keys. This is what keeps non-ASCII descriptions -- most of them Chinese -- typable.
 * Pasting is not a key here: the shortcut needs the platform's modifier state, which the screen already unpacks per version, so the screen calls paste with the clipboard's text and this class only decides where it lands.
 * The caret is a code-point index. Layout uses PlaceText.wrap, the same wrapping as the read-only card, and lines are walked back to char ranges so drawing and caret never disagree.
 * Code points rather than chars because PlaceDetails.MAX_DESCRIPTION counts code points, and a char index could split a surrogate pair.
 */
final class MultilineField {

    /** Height of one visual line: the font's 9 plus the pixel the caret needs above and below. */
    private static final int LINE_HEIGHT = 10;
    private static final int PAD = 4;
    /** Width the scrollbar takes from the right edge when there is something to scroll. */
    private static final int SCROLLBAR_WIDTH = 3;
    /** Caret half-period in milliseconds. */
    private static final long BLINK_PERIOD_MS = 1000L;
    private static final long BLINK_HALF_MS = BLINK_PERIOD_MS / 2L;

    /** Most code points the value may hold; passed in rather than read from the detail limits. */
    private final int limit;
    /** Greyed placeholder drawn while the value is empty. */
    private final Component hint;

    private String value;
    /** Caret as a code-point index: 0 before the first character, the count after the last. */
    private int caret;
    /** First visual line shown when the text is taller than the box. */
    private int scrollRow;
    /** Set by an edit or a caret move; the next render scrolls the caret back into view. */
    private boolean revealCaret;

    private int x;
    private int y;
    private int width;
    private int height;

    /**
     * @param limit the most code points the value may hold
     * @param hint the greyed placeholder drawn while the value is empty
     * @param initial the text to start with, never null
     */
    MultilineField(int limit, Component hint, String initial) {
        this.limit = Math.max(1, limit);
        this.hint = hint;
        // Carriage returns are normalised here as in the store because layout walks the value
        this.value = initial == null ? "" : initial.replace("\r\n", "\n").replace('\r', '\n');
        // Start at the end: the field opens with existing text, so typing should append.
        this.caret = this.value.codePointCount(0, this.value.length());
    }

    String value() {
        return value;
    }

    /** Moves and resizes the box; value, caret and scroll position are kept. */
    void setBounds(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    /** Whether the point is inside the box, which is what decides whether a click focuses it. */
    boolean contains(double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }


    /**
     * One visual line: the drawn text, and the char range of value it came from.
     * Offsets are char offsets; the caret converts once when it needs its line.
     */
    private record Row(String text, int start, int end) {
    }

    /**
     * The visual lines of the value at this box's width.
     * Wrapping is PlaceText.wrap's, so the box and the read-only card break a description in the same places; each line records its value range by walking a cursor and stepping over one newline per paragraph. A value ending in a newline gets a final empty line so the caret has somewhere to be drawn.
     */
    private List<Row> rows(Font font) {
        int wrapWidth = Math.max(1, width - PAD * 2);
        List<Row> rows = buildRows(font, wrapWidth);
        if (rows.size() > visibleRows()) {
            // The scrollbar takes its width out of the text, so re-wrap against what is left
            rows = buildRows(font, Math.max(1, wrapWidth - SCROLLBAR_WIDTH - 1));
        }
        return rows;
    }

    private List<Row> buildRows(Font font, int wrapWidth) {
        List<String> lines = PlaceText.wrap(font, value, wrapWidth);
        List<Row> rows = new ArrayList<>(lines.size() + 1);
        int cursor = 0;
        for (int i = 0; i < lines.size(); i++) {
            // Between lines the value either soft-wrapped or had a typed newline, which belongs
            if (i > 0 && cursor < value.length() && value.charAt(cursor) == '\n') {
                cursor++;
            }
            String text = lines.get(i);
            int start = Math.min(cursor, value.length());
            int end = Math.min(value.length(), start + text.length());
            rows.add(new Row(text, start, end));
            cursor = end;
        }
        if (rows.isEmpty()) {
            // Empty value: still one row so the caret has a place to be.
            rows.add(new Row("", 0, 0));
        }
        return rows;
    }

    private int visibleRows() {
        return Math.max(1, (height - PAD * 2) / LINE_HEIGHT);
    }

    private int caretChar() {
        int count = value.codePointCount(0, value.length());
        return value.offsetByCodePoints(0, Mth.clamp(caret, 0, count));
    }

    /**
     * Where the caret is drawn: line index, and code points into that line.
     * A caret at a line end belongs to that line rather than the next: at a soft wrap both readings agree, and at a typed newline the next line starts one character later.
     */
    private int[] caretAt(List<Row> rows) {
        int caretChar = caretChar();
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            if (caretChar <= row.end()) {
                return new int[]{i, value.codePointCount(row.start(), caretChar)};
            }
        }
        Row last = rows.get(rows.size() - 1);
        return new int[]{rows.size() - 1, value.codePointCount(last.start(), last.end())};
    }


    /**
     * Positions the caret at a click and reports whether it was inside the box.
     * The line comes from the vertical offset, the column from measuring code points until the cursor's x is passed.
     */
    boolean mouseClicked(Font font, double mouseX, double mouseY) {
        if (!contains(mouseX, mouseY)) {
            return false;
        }
        List<Row> rows = rows(font);
        int slot = Mth.clamp((int) ((mouseY - textTop()) / LINE_HEIGHT), 0, visibleRows() - 1);
        int rowIndex = Mth.clamp(scrollRow + slot, 0, rows.size() - 1);
        Row row = rows.get(rowIndex);
        double localX = mouseX - (x + PAD);
        int column = 0;
        int measured = 0;
        int codePoints = row.text().codePointCount(0, row.text().length());
        while (column < codePoints) {
            int at = row.text().offsetByCodePoints(0, column);
            int next = row.text().offsetByCodePoints(at, 1);
            int step = font.width(row.text().substring(at, next));
            if (localX < measured + step / 2.0) {
                break;
            }
            measured += step;
            column++;
        }
        caret = value.codePointCount(0, row.start()) + column;
        revealCaret = true;
        return true;
    }

    /**
     * Runs one editing key.
     * Takes Font because this is not a widget and layout needs the measurements first; takes no modifiers because every key here is a plain editing key. Paste is the one shortcut, and it is answered in the screen, where the modifier state is already unpacked per version.
     * @return whether the key was used, so the screen knows whether to try the rest
     */
    boolean keyPressed(Font font, int keyCode) {
        boolean handled = true;
        switch (keyCode) {
            // Enter is a newline here, never a submit: a line break is content in this field.
            case InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> insert("\n");
            case InputConstants.KEY_BACKSPACE -> {
                if (caret > 0) {
                    delete(caret - 1, caret);
                }
            }
            case InputConstants.KEY_DELETE -> {
                if (caret < value.codePointCount(0, value.length())) {
                    delete(caret, caret + 1);
                }
            }
            case InputConstants.KEY_LEFT -> {
                caret = Math.max(0, caret - 1);
                revealCaret = true;
            }
            case InputConstants.KEY_RIGHT -> {
                caret = Math.min(value.codePointCount(0, value.length()), caret + 1);
                revealCaret = true;
            }
            case InputConstants.KEY_UP -> moveVertically(font, -1);
            case InputConstants.KEY_DOWN -> moveVertically(font, 1);
            case InputConstants.KEY_HOME -> {
                // Home is the start of the visible line, not the paragraph: the breaks on screen
                List<Row> rows = rows(font);
                caret = value.codePointCount(0, rows.get(caretAt(rows)[0]).start());
                revealCaret = true;
            }
            case InputConstants.KEY_END -> {
                List<Row> rows = rows(font);
                caret = value.codePointCount(0, rows.get(caretAt(rows)[0]).end());
                revealCaret = true;
            }
            default -> handled = false;
        }
        return handled;
    }

    /**
     * Moves the caret a line up or down, staying as close to its column as that line allows.
     * The column is counted in code points, not pixels: chasing the pixel would drift the caret sideways on every press while walking lines.
     */
    private void moveVertically(Font font, int delta) {
        List<Row> rows = rows(font);
        int[] spot = caretAt(rows);
        int target = Mth.clamp(spot[0] + delta, 0, rows.size() - 1);
        Row row = rows.get(target);
        int codePoints = value.codePointCount(row.start(), row.end());
        caret = value.codePointCount(0, row.start()) + Math.min(spot[1], codePoints);
        revealCaret = true;
    }

    /**
     * Inserts committed text at the caret.
     * Called for characters, never key codes. Takes a code point because from 1.21.9 the screen hands over a CharacterEvent and before that a char, which is half a surrogate pair for an emoji. A lone surrogate is inserted as one code unit and the caret is recounted after every insert.
     * @return whether the character was taken; false for a control character or past the limit
     */
    boolean charTyped(int codePoint) {
        if (!Character.isValidCodePoint(codePoint) || Character.isISOControl(codePoint)) {
            return false;
        }
        if (value.codePointCount(0, value.length()) >= limit) {
            return false;
        }
        insert(new String(Character.toChars(codePoint)));
        return true;
    }

    private void insert(String text) {
        int charIndex = caretChar();
        value = new StringBuilder(value)
                .replace(charIndex, charIndex, text)
                .toString();
        caret = value.codePointCount(0, charIndex + text.length());
        revealCaret = true;
    }

    /**
     * Inserts clipboard text at the caret.
     * The clipboard is a document, not a keystroke: its line breaks are kept because a description has
     * them too, while every other control character is dropped because this field can neither type nor
     * draw one -- a tab would come out as a missing glyph and a NUL would travel into the saved text.
     * Text longer than the room left is cut to that room instead of being refused whole, so a pasted
     * paragraph still delivers its beginning rather than nothing at all.
     * @param clipboard the clipboard's text, possibly null or empty
     * @return whether anything was inserted
     */
    boolean paste(String clipboard) {
        if (clipboard == null || clipboard.isEmpty()) {
            return false;
        }
        String normalized = clipboard.replace("\r\n", "\n").replace('\r', '\n');
        int room = limit - value.codePointCount(0, value.length());
        if (room <= 0) {
            return false;
        }
        StringBuilder accepted = new StringBuilder();
        int added = 0;
        for (int i = 0; i < normalized.length() && added < room; ) {
            int codePoint = normalized.codePointAt(i);
            i += Character.charCount(codePoint);
            if (codePoint != '\n' && Character.isISOControl(codePoint)) {
                continue;
            }
            accepted.appendCodePoint(codePoint);
            added++;
        }
        if (added == 0) {
            return false;
        }
        insert(accepted.toString());
        return true;
    }

    /** Removes the code points in [from, to) and leaves the caret at from. */
    private void delete(int from, int to) {
        int fromChar = value.offsetByCodePoints(0, Math.max(0, from));
        int toChar = value.offsetByCodePoints(0, Math.min(value.codePointCount(0, value.length()), to));
        value = value.substring(0, fromChar) + value.substring(toChar);
        caret = Math.max(0, from);
        revealCaret = true;
    }

    /**
     * Scrolls by whole lines in the wheel direction.
     * @return whether anything moved, so a box that cannot scroll lets the wheel pass
     */
    boolean scroll(Font font, double amount) {
        List<Row> rows = rows(font);
        int maxScroll = Math.max(0, rows.size() - visibleRows());
        int next = Mth.clamp(scrollRow - (int) Math.signum(amount), 0, maxScroll);
        if (next == scrollRow) {
            return false;
        }
        scrollRow = next;
        // The caret stays put: the player asked to look elsewhere, not to move the insertion point.
        revealCaret = false;
        return true;
    }


    /**
     * The box, its text, the caret and the scrollbar.
     * Draws its own frame and sheet so it matches the vanilla EditBox beside it; the caller draws the card underneath first.
     * @param focused whether the field has focus, which shows the caret and the accent frame
     */
    void render(GuiGraphics graphics, Font font, boolean focused) {
        List<Row> rows = rows(font);
        int[] spot = caretAt(rows);
        int maxScroll = Math.max(0, rows.size() - visibleRows());
        if (revealCaret) {
            // Only enough to bring the caret back into the box; the text above stays
            if (spot[0] < scrollRow) {
                scrollRow = spot[0];
            } else if (spot[0] >= scrollRow + visibleRows()) {
                scrollRow = spot[0] - visibleRows() + 1;
            }
        }
        scrollRow = Mth.clamp(scrollRow, 0, maxScroll);
        revealCaret = false;

        graphics.fill(x - 1, y - 1, x + width + 1, y + height + 1,
                focused ? UiTheme.accent() : UiTheme.panelEdge());
        graphics.fill(x, y, x + width, y + height, UiTheme.searchBox());

        int visible = visibleRows();
        for (int slot = 0; slot < visible; slot++) {
            int index = scrollRow + slot;
            if (index >= rows.size()) {
                break;
            }
            graphics.drawString(font, rows.get(index).text(), x + PAD, textTop() + slot * LINE_HEIGHT,
                    UiTheme.text(), false);
        }
        if (value.isEmpty()) {
            // Shown while focused too, unlike a vanilla EditBox: the caret sits before the hint
            String placeholder = hint == null ? "" : font.plainSubstrByWidth(hint.getString(),
                    Math.max(0, width - PAD * 2));
            graphics.drawString(font, placeholder, x + PAD, textTop(), UiTheme.textFaint(), false);
        }

        if (maxScroll > 0) {
            int trackX = x + width - SCROLLBAR_WIDTH - 1;
            int trackTop = y + PAD;
            int trackHeight = height - PAD * 2;
            int thumbHeight = Math.max(8, trackHeight * visible / rows.size());
            int thumbY = trackTop + (int) ((trackHeight - thumbHeight)
                    * ((double) scrollRow / maxScroll));
            graphics.fill(trackX, trackTop, trackX + SCROLLBAR_WIDTH, trackTop + trackHeight,
                    UiTheme.scrollTrack());
            graphics.fill(trackX, thumbY, trackX + SCROLLBAR_WIDTH, thumbY + thumbHeight,
                    UiTheme.scrollThumb());
        }

        // Blinking, and only while focused: an unfocused field has no caret, which is what shows
        boolean blinking = (System.currentTimeMillis() / BLINK_HALF_MS) % 2L == 0L;
        int caretRow = spot[0];
        if (focused && blinking && caretRow >= scrollRow && caretRow < scrollRow + visible) {
            Row row = rows.get(caretRow);
            int column = Math.min(spot[1], row.text().codePointCount(0, row.text().length()));
            int prefix = row.text().offsetByCodePoints(0, column);
            int caretX = x + PAD + font.width(row.text().substring(0, prefix));
            int lineTop = textTop() + (caretRow - scrollRow) * LINE_HEIGHT;
            graphics.fill(caretX, lineTop - 1, caretX + 1, lineTop + 9, UiTheme.accent());
        }
    }

    private int textTop() {
        return y + PAD;
    }
}
