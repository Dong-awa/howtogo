package bili.dongsz.howtogo.transit;

import bili.dongsz.howtogo.road.ColorHex;
import bili.dongsz.howtogo.road.RoadClass;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A public transport line: a name, a kind of transport, and the stops it calls at in order.
 *
 * <h2>Why the stops are ordered</h2>
 * A line is not a set of stations, it is a service that runs along them. The order is what says
 * which way a train travels, how far the ride between two stops is, which stops lie between them,
 * and where a journey that changes lines has to get off -- none of which can be recovered from a
 * set. It is also what the player is actually describing when they say "this is the line, calling
 * here, then here".
 *
 * <h2>Why the kind is a {@link RoadClass}</h2>
 * Because the pace of every leg is already decided by that type everywhere else in the mod: the
 * mode tables say what a minecart on a rail, a boat on ice and a bus on a made road each travel at.
 * A line that carried its own separate notion of "rail" would need a second table and would sooner
 * or later disagree with the first one, so the line names the road class it runs on instead.
 *
 * <h2>Transferring</h2>
 * Two lines meet where they call at the same block. Nothing else is needed to describe a transfer:
 * the stop is in both lines' lists, so a journey can end one ride there and start the next one from
 * the same place. That is why {@link #stops()} is compared by position rather than by identity.
 */
public final class TransitLine {

    /**
     * The kinds of line a player may build, in the order they are offered.
     *
     * <p>The four classes a public transport vehicle actually travels on. Highway and path are left
     * out because they are the same road at a different width -- a bus line is a road line -- and
     * because a line that ran on a footpath would only ever be walked.
     */
    private static final List<RoadClass> KINDS = List.of(
            RoadClass.RAIL, RoadClass.WATER, RoadClass.ICE, RoadClass.ROAD);

    private final String id;
    private final List<LineStop> stops = new ArrayList<>();
    private String name;
    private RoadClass kind;

    /**
     * The colour this line is drawn in, ARGB, or null when it has none of its own.
     *
     * <h2>Why a line may have a colour at all</h2>
     * Until now a line's colour was a property of its kind -- every rail line the same colour, every
     * boat line another -- and that is still the default. An MTR route, though, has a colour of its
     * own, and a player who runs two rail lines through one interchange needs them told apart on the
     * map; the colour is the thing that carries across the whole map, the line editor's list and the
     * place card's line chips, so it lives on the line rather than in a view.
     *
     * <h2>Why null rather than a sentinel</h2>
     * Black is a colour, and a line drawn in it is a choice rather than "unset". Null therefore means
     * exactly one thing -- this line has no colour of its own -- and every caller that draws it asks
     * {@link #colorOr(int)} with the colour its kind's config says, so a line without a colour keeps
     * following the config and a line with one keeps it.
     */
    private Integer color;

    public TransitLine(String id, String name, RoadClass kind) {
        this.id = id == null || id.isBlank() ? java.util.UUID.randomUUID().toString() : id;
        this.name = name == null ? "" : name;
        this.kind = kind == null ? RoadClass.ROAD : kind;
    }

    /** The kinds of line that may be built, for the type row of the line editor. */
    public static List<RoadClass> kinds() {
        return KINDS;
    }

    /** Stable identity, so a saved line is the same line after a reload. */
    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public void setName(String name) {
        this.name = name == null ? "" : name;
    }

    /** The name to show, falling back to the position of the line in a list when it has none. */
    public String label() {
        return name.isBlank() ? id : name;
    }

    public RoadClass kind() {
        return kind;
    }

    public void setKind(RoadClass kind) {
        if (kind != null) {
            this.kind = kind;
        }
    }

    /**
     * This line's own colour in ARGB, or null when it has none of its own.
     *
     * <p>What the editor's field shows and what the map draws; the fallback for a line without one is
     * the caller's, because only the caller knows whether it is drawing a line (the kind's own
     * config colour) or listing one in a screen that has already resolved a colour.
     */
    public Integer color() {
        return color;
    }

    /** Whether this line carries a colour of its own, rather than following its kind's. */
    public boolean hasColor() {
        return color != null;
    }

    /**
     * The colour to draw this line in: its own when it has one, the given fallback otherwise.
     *
     * <p>The one call every drawing site uses. It exists so that "a line without its own colour falls
     * back to the colour of its kind" is stated here rather than in each of the map, the editor and
     * the place card, which would sooner or later disagree about a line whose colour was cleared.
     */
    public int colorOr(int fallback) {
        return color == null ? fallback : color;
    }

    /**
     * Sets this line's own colour, or clears it when given null.
     *
     * <p>The alpha is forced opaque because a line colour is read out of a field a player types into
     * and out of another mod's data, and a transparent colour is a line that is not there -- the same
     * rule {@code ColorHex} applies to six-digit road colours. The RGB is kept as given, so the value
     * written back out is the one that was read in.
     */
    public void setColor(Integer argb) {
        this.color = argb == null ? null : 0xFF000000 | (argb & 0xFFFFFF);
    }

    /**
     * The colour as {@code #RRGGBB}, or the empty string when the line has none.
     *
     * <p>What the data file stores and what the editor's field is seeded with: six digits is what
     * every colour picker and every other mod's colour data speaks, and the alpha is this line's to
     * add back when it reads the value.
     */
    public String colorHex() {
        return color == null ? "" : ColorHex.formatRgb(color);
    }

    /** The stops in travel order, which is the order a ride along this line calls at them. */
    public List<LineStop> stops() {
        return Collections.unmodifiableList(stops);
    }

    public int stopCount() {
        return stops.size();
    }

    /**
     * Appends a stop, unless the line already calls there.
     *
     * <p>A line that called twice at one block would offer the same boarding twice and would make
     * "the next stop" ambiguous, so the second one is refused rather than silently kept.
     *
     * @return whether the line changed
     */
    public boolean addStop(LineStop stop) {
        if (stop == null || indexOf(stop) >= 0) {
            return false;
        }
        stops.add(stop);
        return true;
    }

    /** @return whether a stop was removed; the index is ignored when it is out of range */
    public boolean removeStop(int index) {
        if (index < 0 || index >= stops.size()) {
            return false;
        }
        stops.remove(index);
        return true;
    }

    /**
     * Renames a stop, keeping everything else about it.
     *
     * <p>Two different things depending on where the stop came from, which is why the name lives here at
     * all. For a place this is only how the line remembers the name -- the place's own name is what the
     * map and the place editor use, and is renamed there. For a station Create's track graph reports
     * there is nowhere else to put a name: Create owns the station, so what is stored here is the
     * player's own name for it, remembered by position and never written back to Create.
     */
    public boolean renameStop(int index, String name) {
        if (index < 0 || index >= stops.size()) {
            return false;
        }
        LineStop stop = stops.get(index);
        stops.set(index, new LineStop(stop.nodeId(), name, stop.x(), stop.z()));
        return true;
    }

    /**
     * Moves a stop one place earlier or later in the calling order.
     *
     * <p>Clamped rather than refused: dragging the first stop up is the same gesture as asking for it
     * to be first, and the player pressing on at the end of a list does not mean "delete it".
     *
     * @return whether the stop moved
     */
    public boolean moveStop(int index, int delta) {
        int target = index + delta;
        if (index < 0 || index >= stops.size() || target < 0 || target >= stops.size()) {
            return false;
        }
        stops.add(target, stops.remove(index));
        return true;
    }

    /** The index of the stop at the given block, or {@code -1} when the line does not call there. */
    public int indexOf(LineStop stop) {
        for (int i = 0; i < stops.size(); i++) {
            if (stops.get(i).samePlace(stop)) {
                return i;
            }
        }
        return -1;
    }

    /** Whether this line calls at the given block, which is what makes a transfer possible. */
    public boolean callsAt(int x, int z) {
        return indexOf(new LineStop(LineStop.NO_NODE, "", x, z)) >= 0;
    }

    /** A copy that may be edited without touching this one, for an editor that can be cancelled. */
    public TransitLine copy() {
        TransitLine copy = new TransitLine(id, name, kind);
        copy.stops.addAll(stops);
        copy.setColor(color);
        return copy;
    }
}
