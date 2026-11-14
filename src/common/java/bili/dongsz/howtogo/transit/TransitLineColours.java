package bili.dongsz.howtogo.transit;

import bili.dongsz.howtogo.road.ColorHex;

import java.util.List;

/**
 * One-click colour presets for the line editor.
 * Eight distinct colours (red, orange, yellow, green, cyan, blue, purple, pink) at a saturation readable as a thin map stroke: the common case is telling a new line apart, not picking an exact colour.
 * Given as #RRGGBB text so the value checked is the value written; ColorHex parses them. Not editor-specific -- swatches and copied lines use the same list.
 */
public final class TransitLineColours {

    /** The eight presets in draw order, six-digit #RRGGBB each. */
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
     * Presets as opaque ARGB numbers, ready to draw and to store on a line.
     * Parsed per call (eight entries, once per frame while the editor is open); the text stays the single source of truth. An unparseable entry is dropped rather than drawn as black; the harness checks every entry parses.
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

    /** The presets as their #RRGGBB text, as seen by the screen and the check. */
    public static List<String> presetHexes() {
        return PRESETS;
    }
}
