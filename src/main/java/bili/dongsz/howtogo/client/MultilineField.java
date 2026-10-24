package bili.dongsz.howtogo.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

/**
 * A hand-drawn multi-line plain-text editor: the description box of the place-details dialog.
 *
 * <h2>Why this is not a Minecraft widget</h2>
 * Every editing control this mod needs already exists as a real widget -- {@link
 * net.minecraft.client.gui.components.EditBox} for one line, {@link
 * net.minecraft.client.gui.components.MultiLineEditBox} from 1.20.5 on for several -- and this class
 * exists because neither is usable here. {@code EditBox} is single-line, and a description is
 * multi-line text. {@code MultiLineEditBox} does not exist before 1.20.5, while this tree still
 * builds 1.20.1, so using it would mean a whole second implementation for the older nodes. What is
 * left is a field this mod draws itself, next to {@link PanelButton} and the rest of its hand-drawn
 * controls: the same trade those make -- no focus narration, no tab order from the game -- for one
 * implementation that compiles on every node.
 *
 * <h2>Why the committed characters arrive through {@code Screen.charTyped}</h2>
 * This is not a widget, so it has no {@code charTyped} of its own for the game to find: the only
 * place an input method's committed text can reach it is the screen's own {@code charTyped} override,
 * which the screen forwards here while the description has the focus. That is not a workaround, it is
 * the only correct path: on every node this mod supports, non-ASCII text enters the game through the
 * platform input method and is delivered as committed <em>characters</em>, never as GLFW key codes.
 * A field that read raw key codes would type ASCII only and would break every Chinese description,
 * which is most of them. Key codes are therefore used for the editing keys alone -- Enter, backspace,
 * delete, the arrows, home and end -- and {@link #charTyped} for the text.
 *
 * <h2>Why the caret and the layout agree by construction</h2>
 * The caret is a <em>code-point</em> index into the value, and the visual lines are computed with
 * {@link PlaceText#wrap} so the box breaks lines exactly where the read-only detail card does. The
 * wrapped lines are then walked back against the value to record each line's character range, which
 * is what turns "where is the caret" into "which line, and how far along it" without measuring the
 * text a second time. Two independent layout implementations -- one for drawing and one for the
 * caret -- is how an editor ends up with a caret that sits a character away from the text.
 *
 * <p>Code points rather than {@code char}s because the limit of
 * {@link bili.dongsz.howtogo.place.PlaceDetails#MAX_DESCRIPTION} is counted in code points: with a
 * {@code char} index, backspace could remove half of a surrogate pair and leave text that is not
 * text any more.
 */
final class MultilineField {

    /** Height of one visual line: the font's 9 plus the pixel the caret needs above and below. */
    private static final int LINE_HEIGHT = 10;
    /** Inner padding of the box, where the text starts. */
    private static final int PAD = 4;
    /** Width the scrollbar takes out of the right edge when there is something to scroll. */
    private static final int SCROLLBAR_WIDTH = 3;
    /** How long the caret stays in each of its two states, in milliseconds. */
    private static final long BLINK_PERIOD_MS = 1000L;
    private static final long BLINK_HALF_MS = BLINK_PERIOD_MS / 2L;

    /** Most code points the value may hold. Passed in rather than read from the detail limits. */
    private final int limit;
    /** Shown grey inside the box while it is empty, so an empty description looks empty. */
    private final Component hint;

    private String value;
    /** Caret as a code-point index: 0 is before the first character, the count is after the last. */
    private int caret;
    /** First visual line shown, for a description longer than the box. */
    private int scrollRow;
    /** Set by an edit or a move; the next render scrolls the caret back into view. */
    private boolean revealCaret;

    private int x;
    private int y;
    private int width;
    private int height;

    /**
     * @param limit   the most code points the value may hold
     * @param hint    the greyed placeholder drawn while the value is empty
     * @param initial the text to start with, never null
     */
    MultilineField(int limit, Component hint, String initial) {
        this.limit = Math.max(1, limit);
        this.hint = hint;
        // Carriage returns are normalised here as well as in the store, because the layout walks the
        // value itself to recover where every line starts: a '\r' left in it would be one character
        // that PlaceText.wrap splits on and this class then could not find again, and the caret would
        // drift one place per line of a hand-edited file.
        this.value = initial == null ? "" : initial.replace("\r\n", "\n").replace('\r', '\n');
        // Starting at the end rather than at the beginning: the field is opened with the text the
        // place already has, and what a player who starts typing means is "add to it".
        this.caret = this.value.codePointCount(0, this.value.length());
    }

    String value() {
        return value;
    }

    /** Moves and resizes the box; the value, the caret and the scroll position are kept. */
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

    // ---------------------------------------------------------------- layout

    /**
     * One visual line: the text drawn, and the character range of {@link #value} it came from.
     *
     * <p>Offsets are {@code char} offsets rather than code points because they are used against the
     * value directly while walking it; the caret converts once, when it needs its line.
     */
    private record Row(String text, int start, int end) {
    }

    /**
     * The visual lines of the value at this box's width.
     *
     * <p>The wrapping itself is {@link PlaceText#wrap}'s, so the box and the read-only card break a
     * description in the same places. What this method adds is the range each line occupies in the
     * value: the wrap keeps every character except the newlines it splits on, so each line can be
     * matched back by walking a cursor through the value and stepping over one newline per paragraph.
     *
     * <p>A value that ends in a newline gets a final empty line, because the caret can sit after that
     * newline and needs somewhere to be drawn.
     */
    private List<Row> rows(Font font) {
        int wrapWidth = Math.max(1, width - PAD * 2);
        List<Row> rows = buildRows(font, wrapWidth);
        if (rows.size() > visibleRows()) {
            // The scrollbar takes its width out of the text, so the description that is already too
            // tall is wrapped once more against what is left. A line that fits one pixel over would
            // otherwise be clipped by the bar that exists because it did not fit.
            rows = buildRows(font, Math.max(1, wrapWidth - SCROLLBAR_WIDTH - 1));
        }
        return rows;
    }

    private List<Row> buildRows(Font font, int wrapWidth) {
        List<String> lines = PlaceText.wrap(font, value, wrapWidth);
        List<Row> rows = new ArrayList<>(lines.size() + 1);
        int cursor = 0;
        for (int i = 0; i < lines.size(); i++) {
            // Between two lines the value either went on (a soft wrap) or had a newline there (a
            // paragraph the player typed), and the newline belongs to neither line.
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
            // The value is empty, and PlaceText.wrap has no lines to give: still needs one row so the
            // caret has a place to be.
            rows.add(new Row("", 0, 0));
        }
        return rows;
    }

    /** How many whole lines fit in the box, at least one so a too-short box still shows something. */
    private int visibleRows() {
        return Math.max(1, (height - PAD * 2) / LINE_HEIGHT);
    }

    /** The caret as a {@code char} offset, which is what the line ranges speak. */
    private int caretChar() {
        int count = value.codePointCount(0, value.length());
        return value.offsetByCodePoints(0, Mth.clamp(caret, 0, count));
    }

    /**
     * Where the caret is drawn: the line's index, and how many code points into that line it sits.
     *
     * <p>A caret exactly at a line's end is given to that line rather than the next one. At a soft
     * wrap both readings are the same position, and drawing it at the end of the line above is what
     * an editor does with the character about to move down; at a typed newline the next line starts
     * one character later, so the two cases do not disagree.
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

    // ---------------------------------------------------------------- input

    /**
     * Positions the caret at a click and reports whether the click was inside the box.
     *
     * <p>The line is found from the vertical offset and the column by measuring the code points of
     * that line until the cursor's x is passed, so clicking on the right half of a character puts the
     * caret after it, the way every text field does.
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
     *
     * <p>Takes the {@link Font} because this is not a widget and has no font of its own: the layout
     * needs the font's measurements before anything about the caret can be decided. The modifiers are
     * not taken: none of the keys this field owns is a shortcut, they are all plain editing keys, and
     * a field that started reading modifiers would be inventing shortcuts the rest of the mod has not
     * got.
     *
     * @return whether the key was one this field uses, so the screen knows whether to try the rest
     */
    boolean keyPressed(Font font, int keyCode) {
        boolean handled = true;
        switch (keyCode) {
            // Enter is a newline here and never a submit: the description is the one field of the
            // dialog where a line break is content rather than a confirmation.
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
                // Home is the beginning of the line being shown, not of the paragraph: the line breaks
                // on screen are the ones the player sees and can point at.
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
     *
     * <p>The column is counted in code points rather than pixels: a line of narrow latin text and one
     * of wide CJK characters are the same number of characters in a different amount of space, and
     * chasing the pixel would move the caret sideways on every press while the player is trying to
     * walk up a list of lines.
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
     *
     * <p>Called only for characters, never for key codes: see the class comment on why. It takes a
     * code point rather than a {@code char} because the two versions of the game disagree about it:
     * from 1.21.9 the screen hands over a {@code CharacterEvent} carrying one Unicode code point, and
     * before that it hands over a {@code char}, which for an emoji is half of a surrogate pair. A code
     * point that is a lone surrogate is still valid input and is inserted as one code unit: the second
     * half completes the pair, and the caret is recounted from the value after every insert, so the
     * completed pair is one code point either way.
     *
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

    /** Removes the code points in {@code [from, to)} and leaves the caret at {@code from}. */
    private void delete(int from, int to) {
        int fromChar = value.offsetByCodePoints(0, Math.max(0, from));
        int toChar = value.offsetByCodePoints(0, Math.min(value.codePointCount(0, value.length()), to));
        value = value.substring(0, fromChar) + value.substring(toChar);
        caret = Math.max(0, from);
        revealCaret = true;
    }

    /**
     * Scrolls the box by whole lines, in the direction of the wheel.
     *
     * @return whether anything moved, so the screen can let a box that cannot scroll pass the wheel on
     */
    boolean scroll(Font font, double amount) {
        List<Row> rows = rows(font);
        int maxScroll = Math.max(0, rows.size() - visibleRows());
        int next = Mth.clamp(scrollRow - (int) Math.signum(amount), 0, maxScroll);
        if (next == scrollRow) {
            return false;
        }
        scrollRow = next;
        // The caret stays where it was rather than dragging the view back: the player asked to look
        // somewhere else, not to move the insertion point.
        revealCaret = false;
        return true;
    }

    // --------------------------------------------------------------- drawing

    /**
     * The box, the text, the caret and the scrollbar.
     *
     * <p>The box draws its own frame and sheet so it sits on the dialog the way the vanilla
     * {@code EditBox} beside it does; the caller only has to draw the card underneath first.
     *
     * @param focused whether the field has the focus, which is what shows the caret and the accent
     *                frame
     */
    void render(GuiGraphics graphics, Font font, boolean focused) {
        List<Row> rows = rows(font);
        int[] spot = caretAt(rows);
        int maxScroll = Math.max(0, rows.size() - visibleRows());
        if (revealCaret) {
            // Only enough to bring the caret back into the box: an edit at the bottom scrolls one
            // line further, and the text above stays where the player was reading it.
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
            // Shown while focused too, unlike a vanilla EditBox: the caret then sits before the
            // placeholder, which is how a web form reads and is the only way the player learns what
            // the box is for -- the field is empty exactly when the hint matters most.
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

        // Blinking, and only while focused -- an unfocused field has no caret at all, which is what
        // says where the typing will go when there are two fields in the dialog.
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

    /** Top of the first text line, which is the box's own top plus its padding. */
    private int textTop() {
        return y + PAD;
    }
}
