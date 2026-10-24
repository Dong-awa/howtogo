package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.RoadConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
//? if <1.21.9 {
import net.minecraft.client.multiplayer.ClientLevel;
//?} else {
/*import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.phys.Vec3;
*///?}

/**
 * The colours this mod's interfaces are drawn in, in one of two styles.
 *
 * <h2>Two palettes, one set of roles</h2>
 * Every surface this mod draws -- the main screen, the destination picker, the line and map-data
 * windows, the road-name editor, the navigation panel in the corner of the screen, the map filter
 * box and the destination page inside MCphone -- asks this class for its colours instead of writing
 * a number down. {@link #light()} decides which of the two palettes answers, and every method here
 * returns a dark value or a light one accordingly. There is no third thing to keep in step: adding
 * a colour means adding one role with its two values, and no screen can be left behind because no
 * screen owns a colour any more.
 *
 * <h2>The dark palette is the design that was already there</h2>
 * The dark values below are the literals the screens used to carry, moved here unchanged, because
 * the black style is the one that exists and this class exists to add a second one, not to
 * restyle the first. Four pairs of literals were the same colour under two names and are now one
 * definition: the two full-screen scrims ({@code #000000} and {@code #101010} at the same alpha),
 * the two route blues ({@code #2FD0FF} on the navigation side, {@code #4FA3FF} on the viewer), the
 * three shades the list windows used for the same panel {@code #181818}, and the two dark rings
 * around the player dot. Nothing else about the dark style moved.
 *
 * <h2>Where the light palette comes from</h2>
 * Google Maps' light interface, because that is the reading a map should take: white sheets over a
 * pale grey map, near-black text, one blue for anything that can be acted on, and a green/orange/red
 * trio for the traffic-like meanings this mod already had. Translating the dark roles into it is a
 * role-by-role job rather than an inversion -- inverting {@code #2FD0FF} would give a muddy red, and
 * a light theme is not a dark theme with the numbers flipped:
 *
 * <ul>
 *   <li><b>Sheets</b> are white ({@code #FFFFFF}) with a {@code #DADCE0} hairline, not pure black
 *       with a hard edge. The screen behind them is dimmed with a pale wash rather than blacked out,
 *       because a light interface over a darkened world reads as a hole in the screen.</li>
 *   <li><b>The map</b> is Google's land grey {@code #F1F3F4}; road and place labels are the greys
 *       Google uses on it ({@code #5F6368} for a road, {@code #3C4043} for a place).</li>
 *   <li><b>Text</b> is {@code #202124} -- Google's primary grey, 16.1:1 on white -- and the three
 *       ranks under it are {@code #5F6368}, {@code #70757A} and {@code #80868B}. The dark palette
 *       had five ranks of near-white grey; the light one keeps the ranks because the screens use
 *       them to mean different things, but the steps are Google's.</li>
 *   <li><b>A screen's name</b> is the ordinary text colour, as it is in Google's own sheets: white
 *       surfaces, near-black titles, and colour kept for what can be acted on.</li>
 *   <li><b>The accent</b> is Google blue {@code #1967D2}: 5.5:1 on white, so it can be text as well
 *       as a line, which the cyan it replaces could never be on a white sheet. It is the darker of
 *       Google's two blues because most of its uses here are eight-pixel text -- a button's face
 *       takes the lighter {@code #1A73E8} through {@link #selection()}, which is read as white on
 *       blue. The accent's hover is the darker still {@code #174EA6}.</li>
 *   <li><b>Selection</b> is {@code #1A73E8} as a filled row with white text, and hover is the pale
 *       grey {@code #F1F3F4} -- the two states Google's own lists have.</li>
 *   <li><b>Meaning colours</b> get the contrast a light background demands: red {@code #D93025},
 *       green {@code #188038}, amber {@code #B06000}. Neon {@code #44FF88} and {@code #FFB454} were
 *       legible on near-black and are not on white.</li>
 * </ul>
 *
 * <h2>Auto: day white, night black, and never white underground</h2>
 * {@link Mode#AUTO} asks the world rather than the clock: a dimension with no sky -- the Nether, the
 * End, and anything else that declares {@code has_skylight: false} -- is always dark, and a
 * dimension with a sky goes light while the sky is bright. "Bright" is the game's own sky-light
 * reading, so the interface turns over when the world does and needs no table of sunrise times; the
 * reading carries rain and thunder as well, so a storm dims it the way it dims the world.
 *
 * <p>Read <em>live</em>, from the shape the game exposes it in. The obvious reading,
 * {@code level.getSkyDarken()}, is an int the server refreshes every tick and the client writes once,
 * when the level is built: a session that logged in at noon kept a noon reading all night, so the
 * automatic style never turned over. That is why this class asks for a brightness float instead --
 * the client's sky-light calculation up to 1.21.8, the environment attribute it feeds from 1.21.9 on
 * -- and turns over where that reading crosses half, which is where vanilla's own "bright outside"
 * line falls (see {@link #TURNOVER_SKY_BRIGHTNESS}).
 *
 * <h2>Why methods and not constants</h2>
 * A {@code static final int} is decided when the class is loaded, and this answer is not: it changes
 * with the config, with the dimension, and with the sky. Methods also mean a reload of the config
 * takes effect on the next frame drawn, with no listener and no cache to invalidate -- the read
 * behind them is a field on a config value and the level's sky reading, which is cheap enough to do
 * once per colour per frame, and is done in a draw loop rather than a simulation hot path.
 */
public final class UiTheme {

    /**
     * The three answers the {@code ui_theme} config entry offers.
     *
     * <p>Ids are the on-disk spellings for the Fabric arm, which writes a string; the NeoForge arm
     * declares this enum directly, so its config file holds the constant name. Both files mean the
     * same thing and both are read back through {@link #byId}, case-insensitively.
     */
    public enum Mode {

        /** Always the black style. */
        DARK("dark"),

        /** Always the white style. */
        LIGHT("light"),

        /** The white style in daylight, the black style at night and in the Nether and the End. */
        AUTO("auto");

        private final String id;

        Mode(String id) {
            this.id = id;
        }

        /** The spelling this mode is written as in a config file. */
        public String id() {
            return id;
        }

        /**
         * The mode an id names, or {@code null} when it names none.
         *
         * <p>Case-insensitive because the two config arms disagree about case -- the Fabric file is
         * written in lower case, the NeoForge one in constant names -- and a player who edits either
         * by hand should not have to remember which file they are in.
         */
        public static Mode byId(String id) {
            if (id == null) {
                return null;
            }
            for (Mode mode : values()) {
                if (mode.id.equalsIgnoreCase(id) || mode.name().equalsIgnoreCase(id)) {
                    return mode;
                }
            }
            return null;
        }
    }

    /**
     * The sky brightness at which the interface turns over, from a dark night to a bright day.
     *
     * <p>Half, because that is where vanilla's own "bright outside" line falls in the reading this
     * class asks for: the client's sky-light value runs from 1.0 at noon to 0.2 at midnight and crosses
     * 0.5 about thirty ticks from the moment vanilla's {@code isBrightOutside()} calls the world dark,
     * so the interface turns over when the world does rather than at a threshold invented here. The
     * environment attribute that replaced this reading on 1.21.9+ runs from 1.0 by day to 0.24 at night
     * and crosses the same line at the same time of day.
     */
    private static final float TURNOVER_SKY_BRIGHTNESS = 0.5F;

    private UiTheme() {
    }

    // ------------------------------------------------------------- resolution

    /**
     * Whether the light palette is in force right now.
     *
     * <p>Read on every colour, so it must stay cheap: a config value, a dimension test and the level's
     * own sky reading, with no allocation and no state of its own to go stale. The one thing it can do
     * that a cached answer could not is follow the world into the Nether, and the sun down, mid-session.
     */
    public static boolean light() {
        return switch (RoadConfig.uiTheme()) {
            case LIGHT -> true;
            case DARK -> false;
            case AUTO -> autoLight();
        };
    }

    /**
     * The auto rule applied to the world on screen.
     *
     * <p>No world to ask -- the config screens are not our clients and this mod's own screens only
     * exist over a world -- so the dark style is the answer that cannot surprise anybody: it is what a
     * player who has never seen the setting has.
     */
    private static boolean autoLight() {
        var level = Minecraft.getInstance().level;
        if (level == null) {
            return false;
        }
        return autoIsLight(level.dimensionType().hasSkyLight(), skyBrightness(level));
    }

    /**
     * The sky's brightness right now, from 0 under a dark night to 1 under a bright day.
     *
     * <p>The two shapes the game has had for this reading, fenced rather than reflected: up to 1.21.8
     * the client's own sky-light calculation, which is named {@code getSkyDarken} but is in fact the
     * brightness multiplier the light texture uses (one at noon, a fifth at midnight); from 1.21.9 on
     * the environment attribute that calculation feeds, read at the world's origin because the value
     * is time-based rather than a fact about a place. Both carry rain and thunder as well, so a storm
     * is already in the number.
     */
    //? if <1.21.9 {
    private static float skyBrightness(Level level) {
        return ((ClientLevel) level).getSkyDarken(1.0F);
    }
    //?} else {
    /*private static float skyBrightness(Level level) {
        return level.environmentAttributes()
                .getValue(EnvironmentAttributes.SKY_LIGHT_FACTOR, Vec3.ZERO);
    }
    *///?}

    /**
     * The sky's brightness in the world on screen right now, or -1 when no world is loaded.
     *
     * <p>For the self-test's report rather than for drawing: it is the number the auto rule decides
     * on, so a player reporting that the white style did not turn over can say what the game thought
     * the sky was at the time.
     */
    static float liveSkyBrightness() {
        var level = Minecraft.getInstance().level;
        return level == null ? -1.0F : skyBrightness(level);
    }

    /**
     * The auto rule itself, as a pure function, so it can be checked without a world.
     *
     * <p>Split out of {@link #light()} for the self-test: the rule is the only part of this class
     * that has an opinion rather than a constant, and an opinion that only runs while a world is
     * loaded is one nobody can test.
     *
     * @param hasSkyLight   whether the dimension declares a sky at all
     * @param skyBrightness the sky's brightness, from 0 under a dark night to 1 under a bright day
     */
    static boolean autoIsLight(boolean hasSkyLight, float skyBrightness) {
        return hasSkyLight && skyBrightness > TURNOVER_SKY_BRIGHTNESS;
    }

    /** The dark or light value, which is the whole of every role below. */
    private static int pick(int dark, int light) {
        return light() ? light : dark;
    }

    // ----------------------------------------------------------------- surfaces

    /** The wash drawn over the whole screen behind a panel. */
    public static int dim() {
        return pick(0x90000000, 0xB3F8F9FA);
    }

    /** The main sheet: the panel most screens consist of. */
    public static int panel() {
        return pick(0xF012171C, 0xF7FFFFFF);
    }

    /** The same sheet, opaque, for the windows that are the whole screen. */
    public static int panelSolid() {
        return pick(0xFF181818, 0xFEFFFFFF);
    }

    /** The hairline around a panel. */
    public static int panelEdge() {
        return pick(0xFF2C3540, 0xFFDADCE0);
    }

    /** The outermost pixel around a window that draws its own frame. */
    public static int windowEdge() {
        return pick(0xFF000000, 0xFFDADCE0);
    }

    /** The translucent box the map filter sits in, drawn over the world map rather than a screen. */
    public static int overlayPanel() {
        return pick(0xA0000000, 0xC9FFFFFF);
    }

    /** The hairline around {@link #overlayPanel()}. */
    public static int overlayEdge() {
        return pick(0x60FFFFFF, 0x33000000);
    }

    /**
     * A ring drawn around something in the overlay under the cursor.
     *
     * <p>Stronger than {@link #overlayEdge()} in both styles: the edge is a hairline that only has to
     * be seen, while a ring has to say "this is the box your click will hit". In the white style the
     * edge's 20 % black would read as a shadow rather than as an answer, so the ring is the same
     * weight the black style has always used, mirrored into black.
     */
    public static int overlayHoverEdge() {
        return pick(0x60FFFFFF, 0x61000000);
    }

    /** The empty map: what is under the roads. */
    public static int mapBackdrop() {
        return pick(0xFF0B0F13, 0xFFF1F3F4);
    }

    /** The frame around the empty map. */
    public static int mapEdge() {
        return pick(0xFF232B33, 0xFFDADCE0);
    }

    /** A button floating on the map, such as the recenter control. */
    public static int mapButton() {
        return pick(0xA010171C, 0xE8FFFFFF);
    }

    /** The plate a marker or a small icon stands on, so it reads over any part of the map. */
    public static int markerBackdrop() {
        return pick(0xFF10161E, 0xFFFFFFFF);
    }

    /** The phone page's search field. */
    public static int searchBox() {
        return pick(0xFF161B22, 0xFFF1F3F4);
    }

    // --------------------------------------------------------------------- text

    /** A screen's own name, which is the one piece of text meant to be looked at first. */
    public static int title() {
        return pick(0xFFFFE070, 0xFF202124);
    }

    /** A column heading or a caption above a block. */
    public static int header() {
        return pick(0xFF8FA0B0, 0xFF5F6368);
    }

    /** Ordinary body text. */
    public static int text() {
        return pick(0xFFD0D8E0, 0xFF202124);
    }

    /** Body text that has to win against {@link #text()} beside it, such as a value in a list row. */
    public static int textStrong() {
        return pick(0xFFF2F6FA, 0xFF202124);
    }

    /** The rank under {@link #text()}: a note, a count, a unit. */
    public static int textSecondary() {
        return pick(0xFFA8B4C0, 0xFF5F6368);
    }

    /** The rank under that: something that is there but not meant to be read twice. */
    public static int textMuted() {
        return pick(0xFF7E8B98, 0xFF5F6368);
    }

    /** The faintest readable rank: a hint in the corner of a map. */
    public static int textFaint() {
        return pick(0xFF808A96, 0xFF70757A);
    }

    /** The same rank as {@link #textFaint()}, for the colours that were written a shade lighter. */
    public static int textDim() {
        return pick(0xFF909AA4, 0xFF70757A);
    }

    /** The aside drawn after a name to say where the name came from. */
    public static int sourceNote() {
        return pick(0xFFAAB4BE, 0xFF70757A);
    }

    /**
     * Text drawn on top of a filled selection.
     *
     * <p>White in both palettes, which is the one colour that is not re-mapped: the selection is the
     * same blue in both, and white on it is the pair a reader expects. A light theme that turned
     * this dark would put dark text on blue.
     */
    public static int onSelection() {
        return 0xFFFFFFFF;
    }

    /**
     * The rank under {@link #onSelection()}: a detail written on a filled selection.
     *
     * <p>A pale blue rather than a grey, because a grey on the selection blue is a smudge: it has to
     * be lighter than the fill and darker than the white beside it, which on a blue face means a blue.
     * The black style has always used a pale blue here; the white style keeps the idea and takes
     * Google's own pale blue for text on its blue.
     */
    public static int onSelectionDetail() {
        return pick(0xFFD0E0FF, 0xFFD2E3FC);
    }

    /**
     * The fill of a chosen *control* -- a chip, a toggle, a segmented button -- as opposed to a
     * chosen list row, which is {@link #selection()}.
     *
     * <p>A row is a large area and can carry the full accent colour with white text on it; a row of
     * four chips cannot, because four solid blue blocks in a panel read as four primary actions and
     * the one that is simply "the current choice" shouts as loudly as the button that commits. So the
     * white style fills a chosen chip with Google's pale blue and writes the label in the darker blue
     * of a link, which is the pair that style uses for exactly this state; the black style keeps the
     * solid blue it has always used here.
     */
    public static int selectionSoft() {
        return pick(0xFF1F6FEB, 0xFFE8F0FE);
    }

    /** Text on {@link #selectionSoft()}, and only there. */
    public static int onSelectionSoft() {
        return pick(0xFFFFFFFF, 0xFF174EA6);
    }

    // ------------------------------------------------------- accents and states

    /** Anything that can be acted on, and the accent line of a card. */
    public static int accent() {
        return pick(0xFF2FD0FF, 0xFF1967D2);
    }

    /** {@link #accent()} under the cursor. */
    public static int accentHover() {
        return pick(0xFF52DBFF, 0xFF174EA6);
    }

    /** A link, an informative note, or a value that is about to be replaced. */
    public static int accentSoft() {
        return pick(0xFF7FB0FF, 0xFF1967D2);
    }

    /** The fill of the chosen row in a list. */
    public static int selection() {
        return pick(0xFF1F6FEB, 0xFF1A73E8);
    }

    /**
     * The fill of a row that can be clicked, at rest.
     *
     * <p>For a list whose rows open something rather than staying chosen -- the map-data window's
     * archive list is one -- there is no selection to fill, and a row that is nothing but text is read
     * as a line of a directory listing rather than as something to click. A grey band per row is the
     * shape every list in the game uses for "these are rows", and it is the one hint that shows before
     * the cursor arrives. The hover and active tints are laid over this, not instead of it.
     *
     * <p>The white style takes the pale grey the map's land and the search field already use, one step
     * off the sheet. The black style steps up from its {@code #181818} sheet instead: a band darker than
     * the panel reads there as a hole rather than as a row.
     */
    public static int rowSurface() {
        return pick(0xFF232B33, 0xFFF1F3F4);
    }

    /** A cursor tint laid over whatever is already in the row, for lists drawn on the panel. */
    public static int rowHoverTint() {
        return pick(0x28FFFFFF, 0x0F202124);
    }

    /** The chosen-row tint for lists drawn on the panel rather than as filled rows. */
    public static int rowActiveTint() {
        return pick(0x382FD0FF, 0x1A1A73E8);
    }

    /** A row that has been marked, short of chosen: the picker's "you tapped this one" state. */
    public static int rowMarkedTint() {
        return pick(0x20FFFFFF, 0x0A202124);
    }

    /** A row that stands for a place, tinted in the colour a place is drawn in. */
    public static int placeTint() {
        return pick(0x40FFD24A, 0x26E8710A);
    }

    /** A toggle that is on without being the current choice. */
    public static int buttonOn() {
        return pick(0x40FFFFFF, 0xFFE8F0FE);
    }

    /**
     * Text on {@link #buttonOn()}.
     *
     * <p>White in the black style, because that tint is a wash of white over a dark sheet and any
     * other colour would sit on it as a smudge; near-black in the white style, because there it is a
     * pale blue and white text on pale blue is unreadable. It is the one state where "the text on a
     * filled control" and "the text on a selection" cannot be the same value.
     */
    public static int onButtonOn() {
        return pick(0xFFFFFFFF, 0xFF202124);
    }

    /** A button's face. */
    public static int button() {
        return pick(0xFF232B34, 0xFFF1F3F4);
    }

    /** {@link #button()} under the cursor. */
    public static int buttonHover() {
        return pick(0xFF303A45, 0xFFE8EAED);
    }

    /**
     * Text on a neutral filled button, the one that carries no meaning of its own.
     *
     * <p>Not {@link #onSelection()}: a neutral button's face is the pale sheet in the white style and
     * a dark grey in the black one, so the text has to be the ordinary text colour in each. A button
     * filled with the accent or with the danger colour takes {@link #onSelection()} instead.
     */
    public static int onButton() {
        return pick(0xFFF2F6FA, 0xFF202124);
    }

    /** The groove of a scrollbar. */
    public static int scrollTrack() {
        return pick(0x30FFFFFF, 0x1A202124);
    }

    /** The thumb in {@link #scrollTrack()}. */
    public static int scrollThumb() {
        return pick(0x80FFFFFF, 0x6B202124);
    }

    /** The crosshair drawn at the cursor over a picker's map. */
    public static int crosshair() {
        return pick(0x90FFFFFF, 0x8C202124);
    }

    /** The groove of a progress bar. */
    public static int track() {
        return pick(0xFF2C3540, 0xFFE8EAED);
    }

    /** A small icon drawn inside a button or on a plate. */
    public static int icon() {
        return pick(0xFFC0CCD8, 0xFF5F6368);
    }

    // ---------------------------------------------------------------- meanings

    /** A destructive button's face. */
    public static int danger() {
        return pick(0xFFB4553F, 0xFFD93025);
    }

    /** {@link #danger()} under the cursor. */
    public static int dangerHover() {
        return pick(0xFFC96A52, 0xFFB3261E);
    }

    /** Text that reports something wrong. */
    public static int error() {
        return pick(0xFFFF6B6B, 0xFFD93025);
    }

    /** Text that warns: something is not broken but is about to be. */
    public static int warn() {
        return pick(0xFFFFB454, 0xFFB06000);
    }

    /** Text that notes: a thing the player may not have expected, stated without alarm. */
    public static int note() {
        return pick(0xFFFFC060, 0xFFB06000);
    }

    /** Text that reports something succeeded. */
    public static int ok() {
        return pick(0xFF6BE58A, 0xFF188038);
    }

    /** The same, a shade duller, for a status line that is not the point of the screen. */
    public static int okSoft() {
        return pick(0xFF7FD08A, 0xFF188038);
    }

    // ------------------------------------------------------------ map symbols

    /** The route being followed, and the route being previewed. */
    public static int route() {
        return pick(0xFF2FD0FF, 0xFF1A73E8);
    }

    /** The part of a route already travelled. */
    public static int routeDone() {
        return pick(0xFF6E6E6E, 0xFF80868B);
    }

    /** Where the trip starts. */
    public static int origin() {
        return pick(0xFF44FF88, 0xFF188038);
    }

    /** Where it ends. */
    public static int destination() {
        return pick(0xFFFF5555, 0xFFD93025);
    }

    /** A hand-placed place, and the marker that stands for one. */
    public static int place() {
        return pick(0xFFFFD24A, 0xFFE8710A);
    }

    /**
     * The same thing said in words: the place colour used as text.
     *
     * <p>The marker orange is a good shape on either map and a poor paragraph: 2.8:1 on the light
     * map, which is fine for a five-pixel square and not fine for a name. The word form is the same
     * hue taken down until it reads -- the marker stays {#E8710A} so the two still look related.
     */
    public static int placeText() {
        return pick(0xFFFFD24A, 0xFFC24E00);
    }

    /** A road's name written along the road. */
    public static int mapLabel() {
        return pick(0xFFDCE3EA, 0xFF5F6368);
    }

    /** A place's name written beside its marker. */
    public static int mapLabelStrong() {
        return pick(0xFFE8EEF4, 0xFF3C4043);
    }

    /** The arrow or dot that is the player. */
    public static int player() {
        return pick(0xFFFFFFFF, 0xFF1A73E8);
    }

    /** The ring or plate the player's dot is drawn on. */
    public static int playerHalo() {
        return pick(0xFF10161E, 0xFFFFFFFF);
    }

    // ------------------------------------------------------------------ badges

    /** The badge on the step where the player leaves the vehicle. */
    public static int badgeStop() {
        return pick(0xFF7A2E2E, 0xFFD93025);
    }

    /** The badge on the step the player is on now, or is about to start. */
    public static int badgeActive() {
        return pick(0xFF1F6FEB, 0xFF1A73E8);
    }

    /** The badge on a step that has been set aside. */
    public static int badgeIdle() {
        return pick(0xFF232A33, 0xFFE8EAED);
    }

    // ----------------------------------------------------------- filter panel

    /** The edge of a filter panel's check box. */
    public static int checkboxEdge() {
        return pick(0xFF101418, 0xFFBDC1C6);
    }

    /** The inside of a filter panel's unticked box. */
    public static int checkboxOff() {
        return pick(0xFF20262E, 0xFFFFFFFF);
    }
}
