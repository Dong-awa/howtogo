package bili.dongsz.howtogo.road;

/**
 * A colour written as text, and the ARGB number it stands for.
 *
 * <h2>Why this is not a {@code Color.decode}</h2>
 * The colours this mod lets a player set are written in a config file and in a data file, so what
 * they are read from is text somebody typed by hand -- not a Java int, not a value a screen handed
 * over. {@code Color.decode} parses six-digit hex only and throws on anything else, which turns a
 * typo into an exception at whatever moment the colour happens to be read, and that moment is a
 * frame being drawn. Here a colour that does not parse is simply not a colour: the caller is given
 * its own fallback and the mod carries on.
 *
 * <h2>What is accepted</h2>
 * Six digits of RGB ({@code "#3FA9F5"}, {@code "3FA9F5"}, {@code "0x3FA9F5"}, either case) or eight
 * of ARGB ({@code "#FF3FA9F5"}), which is the order Java writes an ARGB int in and therefore the
 * order that needs no explanation beside it. A leading {@code #} and surrounding spaces are
 * optional; nothing else is.
 *
 * <p>Nothing here is specific to roads: the road classes' colours, a line's colour and the colour a
 * player types into the line editor are all read and written through this one place, so a format
 * this class refuses is refused everywhere rather than accepted in one screen and not another.
 */
public final class ColorHex {

    private ColorHex() {
    }

    /**
     * The ARGB number a written colour stands for, or null when the text names no colour.
     *
     * <p>Null rather than a fallback here, because the two callers want opposite things with it: a
     * config value falls back to the class's own colour and ought to say so once, while a text field
     * being typed into is meant to leave the colour alone until it reads as one.
     */
    public static Integer parseOrNull(String text) {
        if (text == null) {
            return null;
        }
        String digits = text.trim();
        if (digits.startsWith("#")) {
            digits = digits.substring(1);
        } else if (digits.length() > 2 && (digits.startsWith("0x") || digits.startsWith("0X"))) {
            digits = digits.substring(2);
        }
        if (digits.length() == 6) {
            try {
                // Six digits are RGB: the alpha they did not write is opaque, because a road colour
                // that came out invisible would read as the colour having been ignored.
                return 0xFF000000 | Integer.parseUnsignedInt(digits, 16);
            } catch (NumberFormatException notHex) {
                return null;
            }
        }
        if (digits.length() == 8) {
            try {
                return Integer.parseUnsignedInt(digits, 16);
            } catch (NumberFormatException notHex) {
                return null;
            }
        }
        return null;
    }

    /** The ARGB number the text stands for, or {@code fallback} when it names no colour. */
    public static int parse(String text, int fallback) {
        Integer parsed = parseOrNull(text);
        return parsed == null ? fallback : parsed;
    }

    /** The colour as {@code #RRGGBB}: what the browser map's CSS and a data file both want. */
    public static String formatRgb(int argb) {
        return String.format("#%06X", argb & 0xFFFFFF);
    }

    /**
     * The colour as {@code #AARRGGBB}, alpha included.
     *
     * <p>What a colour with a transparency of its own is written as, and the form the config's
     * defaults are explained in: six digits are the common case and eight are the one that says
     * what the alpha is rather than leaving it to be assumed.
     */
    public static String formatArgb(int argb) {
        return String.format("#%08X", argb);
    }
}
