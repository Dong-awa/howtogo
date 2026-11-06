package bili.dongsz.howtogo.route;

import bili.dongsz.howtogo.RoadConfig;
import bili.dongsz.howtogo.road.RoadClass;
import net.minecraft.network.chat.Component;

import java.util.Map;

/**
 * How the player is travelling.
 *
 * <p>The mode is a routing input, not a label on the estimate: a driver must not be sent down a
 * footpath and a walker should not be routed along a rail line, so the same road network yields a
 * genuinely different line -- and a different time -- for each mode. Everything a mode needs to
 * decide both lives here: which classes of road it may use and how fast it moves on each of them,
 * and how far it will tolerate being away from them.
 *
 * <p>Pace belongs to the pair, not to either half. A boat on ice is the fastest line on the map
 * while boots on the same ice are ordinary, and a minecart on a rail is quick while a walker beside
 * it is not, so one speed per mode cannot say what a trip will take. The table below is that
 * pairing, and it is also what makes {@link #allows(RoadClass)} true: a class the mode has no pace
 * on is a class it does not travel on.
 */
public enum TravelMode {

    /**
     * On foot: any walkable surface, and the most willing to leave the network at either end.
     *
     * <p>A made road is fast on foot because a player can sprint along it without watching every
     * step, which a footpath does not allow.
     *
     * <h2>Why walking has no connector distance to speak of</h2>
     * The first and last hop of every trip is walked, and a walker standing forty blocks from the
     * nearest road is not a routing failure -- they simply walk those forty blocks. Refusing the trip
     * because the start or the destination is a little off the network answered "no route" to a place
     * plainly in sight, which is the one answer the player cannot act on: the trip is still exactly
     * what they asked for, with a longer walk at that end. So walking's reach is a practical infinity
     * here; what {@code walk_offroad_blocks} limits is the middle of a trip, where a walker crosses
     * between two road ends that do not quite meet, and a beeline there would be an invented shortcut
     * rather than an honest last step. See {@code RoadRouter}'s coincident-node pass.
     */
    WALK("walk", 2.0, Map.of(
            RoadClass.HIGHWAY, 5.612,
            RoadClass.ROAD, 5.612,
            RoadClass.PATH, 4.0,
            RoadClass.ICE, 4.317)),
    /**
     * By vehicle: proper roads only, and a car does not cross a field.
     *
     * <p>Its connector distance is the widest half-road this mode may use, and no more: a car may
     * start parked anywhere on the carriageway or beside it, but it does not cross the field next to
     * the road. A drive whose first or last hop would leave the road that far is refused rather than
     * drawn as a straight line, and the player is offered the walk instead.
     *
     * <h2>Why a highway is faster than a road</h2>
     * These two classes used to share a pace here, which quietly made "fastest time" mean "shortest
     * distance" for every driver: two routes of the same class cost exactly the same per block, so
     * the shorter line won and a highway that is a little longer but much quicker was never offered.
     * That is the reported bug -- a highway detour losing to a winding mountain road because it was
     * a few blocks longer. A highway is built to be driven along and a road is not, so the table now
     * says so: the same blocks are worth a quarter less time on a highway, and a highway may be up to
     * a third longer than a road and still win under "fastest". "Shortest distance" is untouched and
     * still follows the shorter line, because that is what its name promises.
     */
    DRIVE("drive", 12.0, Map.of(
            RoadClass.HIGHWAY, 12.0,
            RoadClass.ROAD, 9.0)),
    /**
     * Public transport: the rail, water and ice lines, which are no use to anyone not riding them.
     *
     * <p>Ice is in the set because that is how a boat travels fast: a frozen line is public transport
     * in the same sense a canal is, not open country.
     *
     * <h2>The table is only the riding half</h2>
     * Walking to and from a station is not in this table and must not be: a mode is one pace per
     * class, so a route containing a walkable class can be satisfied by walking the whole way, and
     * the result is labelled public transport while being nothing of the sort. It also cannot say
     * where the riding begins, which is the actual requirement -- a transit trip is entered and left
     * at a station.
     *
     * <p>That is why a transit trip is planned as a journey of legs instead of as one path. The walk
     * legs are planned in {@link #WALK} over the same network, the riding legs here, and the two are
     * joined at stations, so the walking that is genuinely unavoidable is walked at walking pace and
     * is visible as walking. {@code TransitPlanner} is where that happens; the station data it needs
     * is {@code PlaceKind.STATION}.
     *
     * <h2>Why a ride may not leave its line</h2>
     * The riding part of a journey follows its line and nothing else. The only distance allowed at
     * the ends is the platform's own: a station is a place, not a point, and its stop can stand a
     * good many blocks from the track (see {@code station_snap_blocks}). Past that the hop is not
     * boarding any more, it is a line across the country. The walking legs use walking's own
     * allowance instead, so a trip that needs to cross a field to reach the station still can -- on
     * foot, where that is honest.
     */
    TRANSIT("transit", 12.0, Map.of(
            RoadClass.RAIL, 8.0,
            RoadClass.WATER, 8.0,
            RoadClass.ICE, 40.0));

    private static final TravelMode[] VALUES = values();

    /**
     * Pace of the first and last hop, which is walked whatever the mode.
     *
     * <p>A trip does not begin at the station: both ends are a short walk, and timing that hop like
     * a minecart or a gallop would flatter every route whose ends are far from a road.
     */
    private static final double CONNECTOR_SPEED = 4.317;

    /**
     * Walking's reach at either end of a trip, in blocks.
     *
     * <p>Effectively no limit, and deliberately so: the player is already standing where they are,
     * and the last step to a destination nobody drew a road to is still a step. The number stays
     * finite only so a coordinate nobody meant to type does not become a route to the edge of the
     * world -- the old value was four thousand ninety-six and nothing that matters changed when it
     * was lowered and raised again. A walker's *middle* crossing between two road ends is a different
     * question and is bounded by {@code walk_offroad_blocks}; see the class comment.
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
     * Pace in blocks per second on the given class, or zero when the mode may not use it.
     *
     * <p>This is the only speed the router and the estimate know about, so a road cannot be planned
     * at one pace and reported at another.
     */
    public double speedOn(RoadClass roadClass) {
        return speeds.getOrDefault(roadClass, 0.0);
    }

    /** Walking pace, for the first and last hop of a trip in every mode. */
    public double connectorSpeed() {
        return CONNECTOR_SPEED;
    }

    /**
     * How far from a usable road this mode will still consider one, in blocks.
     *
     * <p>Part of the mode because a car cannot begin its journey across open country and a bus
     * route cannot be walked to from anywhere. It caps which start and goal roads the search looks
     * at, and a route whose connector would be longer than this is refused rather than drawn: a
     * connector past the cap is not a hop onto the network, it is a straight line across open
     * country pretending to be a road trip.
     *
     * <p>Only the middle of a walking trip is a preference, so {@code walk_offroad_blocks} is not
     * read here: walking's two ends are always reachable, see {@link #WALK_CONNECTOR_REACH}. A car's
     * allowance is the width of the road it may use, and a ride's is the platform's reach
     * ({@code station_snap_blocks}) -- neither is something a player should be able to widen into a
     * field.
     */
    public double maxConnectorDistance() {
        return switch (this) {
            case WALK -> WALK_CONNECTOR_REACH;
            // A car may not leave its road: the only distance it gets is the road's own half-width,
            // so standing anywhere on the carriageway still starts a drive and standing in the field
            // beside it does not. Not a config value because it is not a preference -- it is how wide
            // the widest road this vehicle may use is drawn.
            case DRIVE -> widestHalfWidth();
            // A ride may not leave its line either. What it does get is the platform: a station is a
            // place, and the platform it is boarded from can stand a good many blocks from the
            // track's centreline (see station_snap_blocks). Past that the hop is not boarding any
            // more, it is a line across the country.
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
     * Multiplier applied to off-road distance while <em>choosing</em> a route.
     *
     * <p>Leaving the network is much worse for a vehicle or a transit line than for a pair of
     * boots, so the same crossroads can be a perfectly good shortcut on foot and a detour worth
     * driving around. This is a planning penalty only: the reported ETA always times the
     * connectors at walking pace, because the first and last hop is walked whatever the mode.
     */
    public double offRoadCostFactor() {
        return offRoadCostFactor;
    }

    /** The next mode, cycling, so a single hotkey can walk through all of them. */
    public TravelMode next() {
        return VALUES[(ordinal() + 1) % VALUES.length];
    }

    /**
     * Mode for a configured id.
     *
     * <p>Falling back to {@link #WALK} keeps a typo in the config file from leaving navigation
     * unusable, which is the failure the player would notice least and understand least.
     */
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
     * Whether one and the same mode can travel on both classes.
     *
     * <p>The answer to "may these two roads be one place", asked where the router decides whether two
     * nodes close together are worth an edge between them: a highway end and a road end are one place
     * to a driver, and a road end and a rail end are not.
     *
     * <p>Not the same question as "are they the same class", which is what the router used to ask: two
     * roads the same class can still be two roads no mode drives over both of at once, and a highway
     * and the road it was drawn up against are one place to every driver.
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
