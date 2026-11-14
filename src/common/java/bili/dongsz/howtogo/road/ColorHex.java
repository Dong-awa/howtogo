package bili.dongsz.howtogo.road;

/**
 * A colour written as text, and the ARGB number it stands for.
 * Parsing is lenient because colours are typed by hand in config and data files: text that does not parse is simply not a colour, and the caller gets its fallback instead of an exception while a frame is drawn.
 * Accepts six hex digits of RGB ("#3FA9F5", "3FA9F5", "0x3FA9F5", any case) or eight of ARGB ("#FF3FA9F5"); leading # and surrounding spaces are optional.
 * Every road colour is read and written through this class.
 */
public final class ColorHex {

    private ColorHex() {
    }

    /** The ARGB number the text stands for, or null when it names no colour. */
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
                // Six digits are RGB: the missing alpha is opaque, or the colour would look ignored.
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

    /** The ARGB number the text stands for, or fallback when it names no colour. */
    public static int parse(String text, int fallback) {
        Integer parsed = parseOrNull(text);
        return parsed == null ? fallback : parsed;
    }

    /** The colour as #RRGGBB: what the browser map's CSS and a data file want. */
    public static String formatRgb(int argb) {
        return String.format("#%06X", argb & 0xFFFFFF);
    }

    /** The colour as #AARRGGBB, alpha included. */
    public static String formatArgb(int argb) {
        return String.format("#%08X", argb);
    }
}
