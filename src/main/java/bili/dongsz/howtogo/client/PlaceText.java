package bili.dongsz.howtogo.client;

import net.minecraft.client.gui.Font;

import java.util.ArrayList;
import java.util.List;

/**
 * Text layout for the place-detail interface: wrapping, clipping and one-line previews.
 *
 * <h2>Why not {@code Font.split}</h2>
 * The vanilla helper wraps on spaces, and a Chinese description has none: a whole paragraph arrives
 * as one "word" and is clipped to a single line. Half of this mod's players write in Chinese and a
 * description is exactly the text they write most of, so wrapping here steps by code point and lets
 * the natural line breaks come from the font's own measurements. Newlines the player typed are
 * honoured first, because a description is multi-line text and the paragraphs in it are theirs.
 *
 * <h2>Why the previews live here too</h2>
 * A hover readout, a search row and a detail card all show "the description, on one line": one
 * function with one idea of what a line is means those three cannot disagree about where a summary
 * ends. Recording the ellipsis here rather than at each call site is what makes the truncation
 * visible rather than silent.
 */
public final class PlaceText {

    private PlaceText() {
    }

    /**
     * Wraps plain text to a width, honouring the newlines the player typed.
     *
     * <p>Every code point is measured against the running line: a line breaks before the point that
     * would overflow, and a single point wider than the whole box is still emitted (one character on
     * its own line) rather than dropped -- a too-narrow box is a layout problem, and silently losing
     * text to it would be worse.
     *
     * @return the lines, in order; never null, and at least one entry for non-empty input
     */
    public static List<String> wrap(Font font, String text, int width) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return lines;
        }
        int limit = Math.max(1, width);
        for (String paragraph : text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            if (paragraph.isEmpty()) {
                lines.add("");
                continue;
            }
            StringBuilder line = new StringBuilder();
            int i = 0;
            while (i < paragraph.length()) {
                int codePoint = paragraph.codePointAt(i);
                String next = new String(Character.toChars(codePoint));
                if (line.length() > 0 && font.width(line + next) > limit) {
                    lines.add(line.toString());
                    line.setLength(0);
                }
                line.append(next);
                i += Character.charCount(codePoint);
            }
            lines.add(line.toString());
        }
        return lines;
    }

    /**
     * {@code line} clipped to {@code width}, with an ellipsis when anything was cut.
     *
     * <p>Cut by code point, so the result is always valid text: a substring that ended inside a
     * surrogate pair would be a broken character, and it would be the last thing a reader sees before
     * the ellipsis.
     */
    public static String ellipsis(Font font, String line, int width) {
        if (line == null || line.isEmpty()) {
            return "";
        }
        if (font.width(line) <= width) {
            return line;
        }
        String mark = "…";
        int budget = Math.max(0, width - font.width(mark));
        int best = 0;
        int measured = 0;
        int i = 0;
        while (i < line.length()) {
            int codePoint = line.codePointAt(i);
            int step = font.width(new String(Character.toChars(codePoint)));
            if (measured + step > budget) {
                break;
            }
            measured += step;
            best = i + Character.charCount(codePoint);
            i = best;
        }
        return best <= 0 ? mark : line.substring(0, best) + mark;
    }

    /**
     * A description collapsed to one line and clipped: what a hover readout shows.
     *
     * <p>Whitespace runs become one space because a preview that began with a newline would draw
     * nothing, and a description is allowed to begin with one -- the player typed it, not this mod.
     * An empty result means "there is no description", which the caller draws as nothing at all.
     */
    public static String summary(Font font, String text, int width) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").strip();
        return ellipsis(font, flat, width);
    }

    /** The number of lines {@link #wrap} would produce, without keeping them. */
    public static int lineCount(Font font, String text, int width) {
        return wrap(font, text, width).size();
    }
}
