package bili.dongsz.howtogo.transit;

import bili.dongsz.howtogo.road.ColorHex;
import bili.dongsz.howtogo.road.RoadClass;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A public transport line: a name, a kind of transport, and the stops it calls at in order.
 * The order is the service: it says which way a train travels, the length of each ride, which stops lie between two, and where a transfer gets off -- none of it recoverable from a set. Two lines meet where they call at the same block, which is why stops() compares by position rather than identity.
 * The kind is a RoadClass because the pace of every leg is already decided by that class everywhere else; a separate notion of rail would need a second table and would drift from the first.
 */
public final class TransitLine {

    /**
     * The kinds of line a player may build, in the order offered. Highway and path are left out: they are the same road at a different width, and a line on a footpath would only ever be walked.
     */
    private static final List<RoadClass> KINDS = List.of(
            RoadClass.RAIL, RoadClass.WATER, RoadClass.ICE, RoadClass.ROAD);

    private final String id;
    private final List<LineStop> stops = new ArrayList<>();
    private String name;
    private RoadClass kind;

    /**
     * The colour this line is drawn in, ARGB, or null when it has none of its own.
     * Null rather than a sentinel because black is a colour; every drawing site asks colorOr(fallback) with its kind's config colour, so a line without one keeps following config. A line needs its own colour because an MTR route has one and two rail lines through one interchange must be told apart -- the colour carries across the map, the editor list and the place card's line chips.
     */
    private Integer color;

    /**
     * Which way along stops this line may be ridden; two-way until told otherwise. A fact about the service, not one journey, so it lives with the name, kind and stops and survives saves and imports.
     */
    private LineDirection direction = LineDirection.TWO_WAY;

    public TransitLine(String id, String name, RoadClass kind) {
        this.id = id == null || id.isBlank() ? java.util.UUID.randomUUID().toString() : id;
        this.name = name == null ? "" : name;
        this.kind = kind == null ? RoadClass.ROAD : kind;
    }

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

    /** The name to show, falling back to the line's position in a list when it has none. */
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
     * This line's own colour in ARGB, or null when it has none. The fallback is the caller's: only it knows whether it is drawing a line (the kind's config colour) or listing one in a screen that already resolved a colour.
     */
    public Integer color() {
        return color;
    }

    public boolean hasColor() {
        return color != null;
    }

    /**
     * The colour to draw this line in: its own when it has one, the given fallback otherwise. The one call every drawing site uses, so the fallback rule is stated once.
     */
    public int colorOr(int fallback) {
        return color == null ? fallback : color;
    }

    /**
     * Sets this line's own colour, or clears it when given null. Alpha is forced opaque because a transparent colour is a line that is not there; the RGB is kept as read.
     */
    public void setColor(Integer argb) {
        this.color = argb == null ? null : 0xFF000000 | (argb & 0xFFFFFF);
    }

    /**
     * The colour as #RRGGBB, or the empty string when there is none. Six digits is what every colour picker and other mod's data speaks; alpha is added back on read.
     */
    public String colorHex() {
        return color == null ? "" : ColorHex.formatRgb(color);
    }

    public List<LineStop> stops() {
        return Collections.unmodifiableList(stops);
    }

    /**
     * Which way along stops() this line may be ridden. What the planner asks before adding a ride; see LineDirection.
     */
    public LineDirection direction() {
        return direction;
    }

    public boolean oneWay() {
        return direction.isOneWay();
    }

    /**
     * Sets which ways along the stops the line may be ridden, two-way when given null. Null is two-way, not a refusal, so a caller with no opinion leaves the line answering as every line used to.
     */
    public void setDirection(LineDirection direction) {
        this.direction = direction == null ? LineDirection.TWO_WAY : direction;
    }

    public int stopCount() {
        return stops.size();
    }

    /**
     * Appends a stop unless the line already calls there; calling twice at one block would offer the same boarding twice and make the next stop ambiguous.
     * @return whether the line changed
     */
    public boolean addStop(LineStop stop) {
        if (stop == null || indexOf(stop) >= 0) {
            return false;
        }
        stops.add(stop);
        return true;
    }

    /**
     * @return whether a stop was removed; the index is ignored when out of range
     */
    public boolean removeStop(int index) {
        if (index < 0 || index >= stops.size()) {
            return false;
        }
        stops.remove(index);
        return true;
    }

    /**
     * Renames a stop, keeping everything else about it. For a place this is only how the line remembers the name -- the place's own name is what the map uses and is renamed there. For a station Create owns there is nowhere else to put a name, so the player's name is stored here and never written back to Create.
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
     * Moves a stop to a new block, keeping everything else about it.
     * The position has two owners: the line calls at the block, but the place is what may move afterwards, because dragging a station is an edit to the place. A line that kept the block it was built with would be drawn through where its stops used to be, so the caller that moves a place must tell the lines; name and node id are kept so the stop stays the same stop.
     * @return whether the stop moved
     */
    public boolean repositionStop(int index, int x, int z) {
        if (index < 0 || index >= stops.size()) {
            return false;
        }
        LineStop stop = stops.get(index);
        if (stop.x() == x && stop.z() == z) {
            return false;
        }
        stops.set(index, new LineStop(stop.nodeId(), stop.name(), x, z));
        return true;
    }

    /**
     * Moves a stop one place earlier or later in the calling order. Clamped rather than refused: dragging the first stop up means first, not delete.
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

    /** Index of the stop at the given block, or -1 when the line does not call there. */
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
        // The direction is part of what the line is, like the colour: a copy of a one-way loop is a second one-way loop, not a two-way line.
        copy.setDirection(direction);
        return copy;
    }
}
