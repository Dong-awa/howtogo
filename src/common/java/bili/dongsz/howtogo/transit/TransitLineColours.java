package bili.dongsz.howtogo.transit;

import bili.dongsz.howtogo.road.ColorHex;

import java.util.List;

/**
 * The colours the line editor offers as one-click presets.
 *
 * <h2>Why a fixed palette rather than a colour picker</h2>
 * The editor already has one typed field per line for the exact colour, and that is the thing a player
 * who knows the hex they want uses. The presets are for the far commoner case -- a player who has just
 * built a second rail line and needs it told apart from the first one on the map -- where being able to
 * click a colour is the whole interaction and a picker would be more screen for less. A handful of
 * distinct, evenly spaced colours covers "different from the others" without a dialog.
 *
 * <h2>Why these eight</h2>
 * One per family a transit map normally uses, at a saturation that stays readable as a thin stroke on
 * the map and as a throwaway chip in a list: red, orange, yellow, green, cyan, blue, purple, pink. They
 * are deliberately given as {@code #RRGGBB} text rather than as {@code 0x} literals so that what is
 * checked -- that every preset is something {@link ColorHex} reads -- is also what is written here.
 *
 * <p>Nothing here is specific to the line editor: the same list is what a preset swatch shows and what
 * a copy of a line keeps, and the harness checks each entry through the same parser the field uses, so
 * a preset that is not a colour cannot reach a player by accident.
 */
public final class TransitLineColours {

    /**
     * The eight presets, in the order the swatches are drawn.
     *
     * <p>Six digits each, so every one is a colour a player could also have typed into the field: the
     * palette is not a second colour format, only eight values of the one there is.
     */
    private static final List<String> PRESETS = List.of(
            "#E53935", // red
            "#FB8C00", // orange
            "#FDD835", // yellow
            "#43A047", // green
            "#00ACC1", // cyan
            "#1E88E5", // blue
            "#8E24AA", // purple
            "#EC407A"); // pink

    private TransitLineColours() {
    }

    /**
     * The presets as ARGB numbers, opaque, ready to draw and to store on a line.
     *
     * <p>Parsed on every call rather than kept as a second constant list: there are eight of them and
     * the list is asked for once per frame while the editor is open, which is nothing -- and keeping
     * only the text means the text is the one source of truth about what the presets are.
     *
     * <p>A preset text that {@link ColorHex} refused would come back null; one is dropped rather than
     * drawn as black, because a swatch that quietly means something else is worse than one fewer
     * swatch. The harness makes that never happen, by checking every entry parses.
     */
    public static List<Integer> presets() {
        java.util.List<Integer> parsed = new java.util.ArrayList<>(PRESETS.size());
        for (String preset : PRESETS) {
            Integer colour = ColorHex.parseOrNull(preset);
            if (colour != null) {
                parsed.add(colour);
            }
        }
        return List.copyOf(parsed);
    }

    /** The presets as the text they are written as, which is what the screen and the check both see. */
    public static List<String> presetHexes() {
        return PRESETS;
    }
}
