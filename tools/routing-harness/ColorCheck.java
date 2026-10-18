import bili.dongsz.howtogo.RoadConfig;
import bili.dongsz.howtogo.road.ColorHex;
import bili.dongsz.howtogo.road.RoadClass;
import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.road.RoadSegment;
import bili.dongsz.howtogo.webmap.RoadMapSnapshot;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Map;

/**
 * The colours a player may set: what a written colour means, and that the defaults they replace are
 * the ones the map has always drawn.
 *
 * <h2>Why this is checked without a game</h2>
 * The two colour features are, from the outside, one question asked twice: a road class's colour and
 * a transit line kind's colour both come from a config string and both end up as an ARGB int in a
 * stroke. Everything that can go wrong between those ends is pure -- a parser that refuses a colour
 * the game accepts, a fallback that is not the colour the class had before the feature existed, a web
 * payload handed the configured colours that writes the defaults anyway -- and all of it is
 * reachable without a client. What a screenshot would add is the one thing a check cannot answer
 * anyway: whether the player likes the colours.
 */
public final class ColorCheck {

    private static int checks;
    private static int failures;

    private ColorCheck() {
    }

    public static int[] run() {
        System.out.println("== colours: written, defaulted, drawn ==");
        parsing();
        defaults();
        webMap();
        System.out.println(failures == 0
                ? "colours ok (" + checks + " checks)"
                : "colours FAILED: " + failures + " of " + checks + " checks");
        return new int[]{checks, failures};
    }

    /** Every way this mod writes a colour, read back as the number it should be. */
    private static void parsing() {
        expect("#RRGGBB is opaque",
                ColorHex.parseOrNull("#3FA9F5") == 0xFF3FA9F5);
        expect("the # is optional",
                ColorHex.parseOrNull("3fa9f5") == 0xFF3FA9F5);
        expect("a 0x prefix is accepted too, because that is how Java writes one",
                ColorHex.parseOrNull("0x3FA9F5") == 0xFF3FA9F5
                        && ColorHex.parseOrNull("0X3FA9F5") == 0xFF3FA9F5);
        expect("eight digits keep the alpha they say",
                ColorHex.parseOrNull("#80FF0000") == 0x80FF0000
                        && ColorHex.parseOrNull("80FF0000") == 0x80FF0000);
        expect("spaces around the text cost nothing",
                ColorHex.parseOrNull("  #FF0000  ") == 0xFFFF0000);

        expect("a name that is not a colour is not one",
                ColorHex.parseOrNull("red") == null && ColorHex.parseOrNull("") == null);
        expect("nor is a colour with the wrong number of digits",
                ColorHex.parseOrNull("#12345") == null && ColorHex.parseOrNull("#1234567") == null);
        expect("nor is one with a digit outside hex",
                ColorHex.parseOrNull("#GGGGGG") == null);
        expect("and null is not a colour either", ColorHex.parseOrNull(null) == null);
        expect("the fallback is what a non-colour reads as",
                ColorHex.parse("red", 0xFF010203) == 0xFF010203);

        expect("a colour is written back in the same form it is read from",
                "#3FA9F5".equals(ColorHex.formatRgb(0xFF3FA9F5)));
        expect("an eight-digit colour writes its alpha",
                "#803FA9F5".equals(ColorHex.formatArgb(0x803FA9F5)));
        expect("and reading what was written gives the same number",
                ColorHex.parseOrNull(ColorHex.formatRgb(0xFF3FA9F5)) == 0xFF3FA9F5);
    }

    /**
     * What the map draws when nobody has configured anything.
     *
     * <p>The harness has no config file, so every value reads as its declared default -- which is
     * exactly the state a new world is in. That makes this the check that the feature changed no
     * colour for anybody who does not touch it: the road defaults are the enum's own colours and the
     * line defaults are the palette the map used before kinds could be recoloured.
     */
    private static void defaults() {
        Map<RoadClass, Integer> roads = RoadConfig.roadColors();
        expect("every road class has a configured colour", roads.size() == RoadClass.values().length);
        boolean roadsUnchanged = true;
        for (RoadClass roadClass : RoadClass.values()) {
            roadsUnchanged &= roads.get(roadClass) == roadClass.color();
        }
        expect("and with nothing configured it is the class's own colour, so the map is unchanged",
                roadsUnchanged);

        // The palette the renderer used before these became settings, as literals: if a later edit
        // changes one of them, this is the line that says the map changed under players who did not
        // ask for it.
        expect("a rail line is the blue the map has always drawn it in",
                RoadConfig.transitLineColor(RoadClass.RAIL) == 0xFF8AB4FF);
        expect("a water line is its blue", RoadConfig.transitLineColor(RoadClass.WATER) == 0xFF3FA9F5);
        expect("an ice line is its pale blue", RoadConfig.transitLineColor(RoadClass.ICE) == 0xFF9FE8FF);
        expect("a road line is its green", RoadConfig.transitLineColor(RoadClass.ROAD) == 0xFFB8E986);
        expect("and the kinds are told apart by them",
                RoadConfig.transitLineColor(RoadClass.RAIL)
                        != RoadConfig.transitLineColor(RoadClass.WATER)
                        && RoadConfig.transitLineColor(RoadClass.WATER)
                        != RoadConfig.transitLineColor(RoadClass.ICE)
                        && RoadConfig.transitLineColor(RoadClass.ICE)
                        != RoadConfig.transitLineColor(RoadClass.ROAD));
    }

    /** The browser map is handed the configured colours and writes them, not the defaults. */
    private static void webMap() {
        RoadNetwork network = new RoadNetwork();
        RoadSegment road = RoadSegment.of(1, RoadClass.HIGHWAY, 64, 0, 0, 100, 0);
        network.putSegment(road);

        JsonObject configured = JsonParser.parseString(RoadMapSnapshot
                .of("world", "minecraft:overworld", network, Map.of(RoadClass.HIGHWAY, 0xFF123456))
                .toJson(1L)).getAsJsonObject();
        expect("the browser map is given the configured colour of that class",
                "#123456".equals(colourOf(configured, "HIGHWAY")));
        expect("and a class the caller said nothing about keeps its default",
                "#3FD07A".equals(colourOf(configured, "ROAD")));

        JsonObject defaults = JsonParser.parseString(RoadMapSnapshot
                .of("world", "minecraft:overworld", network).toJson(1L)).getAsJsonObject();
        expect("with nothing configured the payload is the class's own colour as before",
                "#3FA9F5".equals(colourOf(defaults, "HIGHWAY")));
    }

    private static String colourOf(JsonObject payload, String classId) {
        for (var element : payload.getAsJsonArray("classes")) {
            JsonObject entry = element.getAsJsonObject();
            if (classId.equals(entry.get("id").getAsString())) {
                return entry.get("color").getAsString();
            }
        }
        return null;
    }

    private static void expect(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
        }
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
    }
}
