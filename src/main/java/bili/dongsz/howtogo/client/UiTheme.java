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
 * The colours this mod's interfaces are drawn in, in one of two palettes.
 *
 * Every screen asks this class rather than writing a number; light() decides which palette answers,
 * so adding a colour means one role with two values. The dark palette is the existing design
 * unchanged; the light one follows Google Maps (white sheets, pale grey map, near-black text, one
 * blue accent, green/orange/red meanings).
 *
 * Mode#AUTO asks the world, not the clock: no sky is dark, otherwise light while the sky is bright.
 * Brightness is read live (client sky-light, or the 1.21.9+ attribute), never stale getSkyDarken.
 * Methods, not constants: the answer changes with config, dimension and sky, at frame cost.
 */
public final class UiTheme {

    /**
     * The three answers the ui_theme config entry offers.
     * Ids are the Fabric file's lowercase spellings; NeoForge stores the constant name. byId reads both.
     */
    public enum Mode {

        DARK("dark"),

        LIGHT("light"),

        AUTO("auto");

        private final String id;

        Mode(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        /** The mode an id names, or null; case-insensitive because the two config arms disagree. */
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
     * The sky brightness at which the interface turns over: half, where vanilla's own
     * bright-outside line falls in the reading this class asks for.
     */
    private static final float TURNOVER_SKY_BRIGHTNESS = 0.5F;

    private UiTheme() {
    }


    /**
     * Whether the light palette is in force right now. Read on every colour, so it stays cheap: a
     * config value, a dimension test and the level's sky reading.
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
     * No world (or no client, as in the offline routing harness) answers dark, the style that cannot
     * surprise.
     */
    private static boolean autoLight() {
        Minecraft minecraft = Minecraft.getInstance();
        var level = minecraft == null ? null : minecraft.level;
        if (level == null) {
            return false;
        }
        return autoIsLight(level.dimensionType().hasSkyLight(), skyBrightness(level));
    }

    /**
     * The sky's brightness right now, 0 under a dark night to 1 under a bright day.
     * Two fenced shapes: the client's sky-light calculation up to 1.21.8 (getSkyDarken is a
     * brightness multiplier, not darkness), and the 1.21.9+ environment attribute at the world origin.
     * Both carry rain and thunder.
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
     * The sky's brightness in the world on screen, or -1 when no world is loaded. For the
     * self-test's report, not for drawing.
     */
    static float liveSkyBrightness() {
        var level = Minecraft.getInstance().level;
        return level == null ? -1.0F : skyBrightness(level);
    }

    /**
     * The auto rule itself, as a pure function, so it can be checked without a world.
     * @param hasSkyLight whether the dimension declares a sky at all
     * @param skyBrightness the sky's brightness, 0 to 1
     */
    static boolean autoIsLight(boolean hasSkyLight, float skyBrightness) {
        return hasSkyLight && skyBrightness > TURNOVER_SKY_BRIGHTNESS;
    }

    private static int pick(int dark, int light) {
        return light() ? light : dark;
    }


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

    /** The hairline around overlayPanel(). */
    public static int overlayEdge() {
        return pick(0x60FFFFFF, 0x33000000);
    }

    /**
     * A ring around something in the overlay under the cursor; stronger than overlayEdge in both
     * styles, because it must say the click will hit here.
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


    /** A screen's own name, the one piece of text meant to be looked at first. */
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

    /** Body text that has to win against text() beside it, such as a value in a list row. */
    public static int textStrong() {
        return pick(0xFFF2F6FA, 0xFF202124);
    }

    /** The rank under text(): a note, a count, a unit. */
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

    /** The same rank as textFaint(), for the colours written a shade lighter. */
    public static int textDim() {
        return pick(0xFF909AA4, 0xFF70757A);
    }

    /** The aside drawn after a name to say where the name came from. */
    public static int sourceNote() {
        return pick(0xFFAAB4BE, 0xFF70757A);
    }

    /**
     * Text on a filled selection. White in both palettes: the selection is the same blue in both,
     * and white on it is the pair a reader expects.
     */
    public static int onSelection() {
        return 0xFFFFFFFF;
    }

    public static int onSelectionDetail() {
        return pick(0xFFD0E0FF, 0xFFD2E3FC);
    }

    /**
     * The fill of a chosen control (chip, toggle), unlike a chosen row: a row can carry the full
     * accent, but four solid blue chips would read as four primary actions, so the light style
     * uses pale blue with a link-blue label.
     */
    public static int selectionSoft() {
        return pick(0xFF1F6FEB, 0xFFE8F0FE);
    }

    /** Text on selectionSoft(), and only there. */
    public static int onSelectionSoft() {
        return pick(0xFFFFFFFF, 0xFF174EA6);
    }



    /** The fill of an ordinary tag chip: grey, with white text. */
    public static int chipTag() {
        return 0xFF5F6368;
    }

    /** The fill of a source chip: blue, with white text. */
    public static int chipSource() {
        return 0xFF1A73E8;
    }

    /** The fill of a type chip: orange, the same hue as place(), the thing it classifies. */
    public static int chipType() {
        return 0xFFE8710A;
    }

    /** Text on a chip whose fill is one of the three fixed chip colours. */
    public static int onChip() {
        return 0xFFFFFFFF;
    }

    /**
     * Text on a chip filled with an arbitrary colour, white or near-black by brightness.
     * A line chip takes the line's own bright colour, so white on pale blue or black on dark purple
     * fails. Threshold on perceived brightness (alpha masked off), not HSL lightness. Not per theme:
     * the fill is the line's colour in either style.
     */
    public static int onChipFor(int argb) {
        int rgb = argb & 0xFFFFFF;
        double brightness = 0.299 * ((rgb >> 16) & 0xFF) / 255.0
                + 0.587 * ((rgb >> 8) & 0xFF) / 255.0
                + 0.114 * (rgb & 0xFF) / 255.0;
        return brightness >= CHIP_DARK_TEXT_FROM ? CHIP_DARK_TEXT : 0xFFFFFFFF;
    }

    private static final double CHIP_DARK_TEXT_FROM = 0.6;

    private static final int CHIP_DARK_TEXT = 0xFF202124;


    /** Anything that can be acted on, and the accent line of a card. */
    public static int accent() {
        return pick(0xFF2FD0FF, 0xFF1967D2);
    }

    /** accent() under the cursor. */
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
     * The fill of a clickable row at rest: a list with no selection needs a band, or rows read as
     * bare text. Hover and active tints are laid over it; the dark style steps up from its sheet so
     * the band does not read as a hole.
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
     * Text on buttonOn. White on the dark style's white wash; near-black on the light style's pale
     * blue, where white would be unreadable.
     */
    public static int onButtonOn() {
        return pick(0xFFFFFFFF, 0xFF202124);
    }

    public static int button() {
        return pick(0xFF232B34, 0xFFF1F3F4);
    }

    public static int buttonHover() {
        return pick(0xFF303A45, 0xFFE8EAED);
    }

    /**
     * Text on a neutral filled button. Not onSelection: its face is the pale sheet or a dark grey,
     * so it takes the ordinary text colour; accent and danger buttons use onSelection.
     */
    public static int onButton() {
        return pick(0xFFF2F6FA, 0xFF202124);
    }

    /** The groove of a scrollbar. */
    public static int scrollTrack() {
        return pick(0x30FFFFFF, 0x1A202124);
    }

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


    /** A destructive button's face. */
    public static int danger() {
        return pick(0xFFB4553F, 0xFFD93025);
    }

    /** danger() under the cursor. */
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


    /**
     * The part of a route travelled on foot, including the legs either side of a ride.
     * Blue distinguishes walking from riding at a glance; riding stretches take their line's own colour.
     */
    public static int route() {
        return pick(0xFF2FD0FF, 0xFF1A73E8);
    }

    /**
     * The part of a route ridden in a vehicle with no line colour: driven.
     * Green, kept apart from the walking blue and the violet of an unresolvable line. A ride with a
     * line takes the line's colour; the green stays readable on both palettes.
     */
    public static int routeRide() {
        return pick(0xFF3FBF6A, 0xFF1B7F3B);
    }

    /**
     * A ride whose line cannot be named (it was deleted mid-journey). Neither walking blue nor
     * driving green is truthful, and grey would mean done, so a violet that reads on both palettes.
     */
    public static int routeLineFallback() {
        return pick(0xFFB98CFF, 0xFF6A3AB8);
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
     * The place colour used as text.
     * The marker orange is 2.8:1 on the light map, fine for a five-pixel square and not for a name; the
     * word form is the same hue taken down until it reads.
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


    /** The edge of a filter panel's check box. */
    public static int checkboxEdge() {
        return pick(0xFF101418, 0xFFBDC1C6);
    }

    /** The inside of a filter panel's unticked box. */
    public static int checkboxOff() {
        return pick(0xFF20262E, 0xFFFFFFFF);
    }
}
