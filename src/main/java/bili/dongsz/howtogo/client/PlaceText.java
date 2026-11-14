package bili.dongsz.howtogo.client;

import net.minecraft.client.gui.Font;

import java.util.ArrayList;
import java.util.List;

/**
 * Text layout for place details: wrapping, clipping and one-line previews.
 *
 * Font.split wraps on spaces, which fails on Chinese text; this wraps by code point and honours
 * player-typed newlines first.
 */
public final class PlaceText {

    private PlaceText() {
    }

    /**
     * Wraps plain text to width, honouring player newlines. A code point wider than the box is
     * emitted on its own line, never dropped.
     * @return lines in order; never null, at least one for non-empty input
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
     * Clips a line to width with an ellipsis when cut. Cuts by code point so surrogate pairs are
     * never split.
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

    /** Collapses a description to one clipped line for hover readouts; empty means no description. */
    public static String summary(Font font, String text, int width) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").strip();
        return ellipsis(font, flat, width);
    }

    /** The number of lines wrap would produce, without keeping them. */
    public static int lineCount(Font font, String text, int width) {
        return wrap(font, text, width).size();
    }
}
