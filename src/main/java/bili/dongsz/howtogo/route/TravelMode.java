package bili.dongsz.howtogo.route;

import bili.dongsz.howtogo.RoadConfig;
import bili.dongsz.howtogo.road.RoadClass;
import net.minecraft.network.chat.Component;

import java.util.Map;

/**
 * How the player is travelling: a routing input, not a label on the estimate. A mode owns which road classes it may use, its pace on each of them, and how far off the network it tolerates being. Pace belongs to the (mode, class) pair -- ice is fast by boat and ordinary on foot -- so allows() is simply "has a pace on this class".
 */
public enum TravelMode {

    /**
     * On foot: any walkable surface, and the most willing to leave the network.
     * A made road is fast on foot because a player can sprint along it without watching every step.
     * Walking's reach at either end is a practical infinity: the first and last hop is walked anyway, and refusing a trip because the destination is slightly off the network answered "no route" to a place in plain sight.
     * walk_offroad_blocks bounds the middle of a trip, where a walker crosses between two road ends that do not quite meet.
     */
    WALK("walk", 2.0, Map.of(
            RoadClass.HIGHWAY, 5.612,
            RoadClass.ROAD, 5.612,
            RoadClass.PATH, 4.0,
            RoadClass.ICE, 4.317)),
    /**
     * By vehicle: proper roads only; the connector distance is the widest half-road it may use, so a car may start anywhere on the carriageway but does not cross the field beside it, and a trip whose end would leave the road that far is refused rather than drawn.
     * A highway is faster than a road in the table: sharing one pace made "fastest time" mean "shortest distance" for every driver, so a longer but quicker highway detour never won. "Shortest distance" still follows the shorter line.
     */
    DRIVE("drive", 12.0, Map.of(
            RoadClass.HIGHWAY, 12.0,
            RoadClass.ROAD, 9.0)),
    /**
     * Public transport: the rail, water and ice lines (ice is how a boat travels fast).
     * This table is only the riding half: walking to and from a station is planned in WALK over the same network and joined at stations (TransitPlanner). Nothing walkable is in the transit table, or a route could be satisfied by walking the whole way and still be labelled transit.
     * The riding part follows its line; the only distance at the ends is the platform's own reach (station_snap_blocks). Past that the hop is not boarding but a line across the country, and walking legs use walking's allowance.
     */
    TRANSIT("transit", 12.0, Map.of(
            RoadClass.RAIL, 8.0,
            RoadClass.WATER, 8.0,
            RoadClass.ICE, 40.0));

    private static final TravelMode[] VALUES = values();

    /**
     * Pace of the first and last hop, which is walked whatever the mode; timing it like a minecart would flatter every route whose ends are far from a road.
     */
    private static final double CONNECTOR_SPEED = 4.317;

    /**
     * Walking's reach at either end of a trip, in blocks. Effectively no limit -- the player is already standing there -- but finite so a stray coordinate does not become a route to the edge of the world. A walker's middle crossing is bounded by walk_offroad_blocks instead.
     */
    private static final double WALK_CONNECTOR_REACH = 4096.0;

    private final String id;
    private final double offRoadCostFactor;
    private final Map<RoadClass, Double> speeds;

    TravelMode(String id, double offRoadCostFactor, Map<RoadClass, Double> speeds) {
        this.id = id;
        this.offRoadCostFactor = offRoadCostFactor;
        this.speeds = speeds;
    }

    /** Stable identifier, used for lang and config keys rather than the enum constant name. */
    public String id() {
        return id;
    }

    /** Localised name, for the readout and the tooltip. */
    public String label() {
        return Component.translatable("hud.howtogo.mode." + id).getString();
    }

    /** Whether this mode may travel along the given class of road at all. */
    public boolean allows(RoadClass roadClass) {
        return speeds.containsKey(roadClass);
    }

    /**
     * Pace in blocks per second on the given class, or zero when the mode may not use it. The only speed the router and the estimate know.
     */
    public double speedOn(RoadClass roadClass) {
        return speeds.getOrDefault(roadClass, 0.0);
    }

    /** Walking pace, for the first and last hop of a trip in every mode. */
    public double connectorSpeed() {
        return CONNECTOR_SPEED;
    }

    /**
     * How far from a usable road this mode will still consider one, in blocks. It caps which start and goal roads the search looks at, and a longer connector is refused rather than drawn as a straight line.
     * Walking's two ends are always reachable (see WALK_CONNECTOR_REACH), so walk_offroad_blocks is not read here; a car's allowance is its widest half-width and a ride's is the platform's reach.
     */
    public double maxConnectorDistance() {
        return switch (this) {
            case WALK -> WALK_CONNECTOR_REACH;
            // A car may not leave its road: the only distance it gets is the road's own half-width, so standing anywhere on the carriageway still starts a drive.
            case DRIVE -> widestHalfWidth();
            // A ride may not leave its line either; it gets the platform's reach, since a station is a place whose stop can stand well off the track's centreline.
            case TRANSIT -> RoadConfig.stationSnapBlocks();
        };
    }

    /** Half the width of the widest road this mode may use, in blocks. */
    private double widestHalfWidth() {
        double widest = 0;
        for (RoadClass roadClass : speeds.keySet()) {
            widest = Math.max(widest, roadClass.width());
        }
        return widest / 2.0;
    }

    /**
     * Multiplier applied to off-road distance while choosing a route: leaving the network is much worse for a vehicle or a transit line than for boots. A planning penalty only; the reported ETA always times connectors at walking pace.
     */
    public double offRoadCostFactor() {
        return offRoadCostFactor;
    }

    /** The next mode, cycling, so a single hotkey can walk through all of them. */
    public TravelMode next() {
        return VALUES[(ordinal() + 1) % VALUES.length];
    }

    /** Mode for a configured id; a typo falls back to WALK rather than making navigation unusable. */
    public static TravelMode byId(String id) {
        if (id != null) {
            for (TravelMode mode : VALUES) {
                if (mode.id.equalsIgnoreCase(id.trim())) {
                    return mode;
                }
            }
        }
        return WALK;
    }

    /**
     * Whether one and the same mode can travel on both classes, i.e. whether two road ends close together may be one place to the router. Not the same question as "are they the same class".
     */
    public static boolean shareAMode(RoadClass a, RoadClass b) {
        if (a == null || b == null) {
            return false;
        }
        for (TravelMode mode : VALUES) {
            if (mode.allows(a) && mode.allows(b)) {
                return true;
            }
        }
        return false;
    }
}
