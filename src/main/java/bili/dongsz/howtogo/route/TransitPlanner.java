package bili.dongsz.howtogo.route;

import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.road.PlaceKind;
import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.road.RoadNode;
import bili.dongsz.howtogo.transit.LineStop;
import bili.dongsz.howtogo.transit.TransitLine;

import java.util.ArrayList;
import java.util.List;

/**
 * The front door of public transport routing: a journey over the player's lines, or nothing.
 *
 * <h2>There is no fallback, and that is the point</h2>
 * This used to fall back to a search over station <em>pairs</em> when the lines could not carry a
 * journey, and that fallback was worse than useless. It boarded at the nearest point of whatever
 * track happened to be closest -- a line nobody chose -- rode it as far as it went, and could not
 * reach the destination at all. From the player's side that is indistinguishable from the mod
 * ignoring the lines they built: "it sent me to the nearest station even though it cannot get
 * there", and the long way round that came with it.
 *
 * <p>Public transport here means the lines the player configured. If none of them can carry the
 * journey, the honest answer is that there is no public transport journey, and the walking comparison
 * upstream is free to offer the walk instead. A route that pretends to be public transport while
 * ignoring every line is not a worse answer, it is a wrong one.
 *
 * <h2>Where the routing lives</h2>
 * {@link LinePlanner}: the stops are the nodes of a directed weighted graph, the links between
 * neighbouring stops on a line are its edges, and Dijkstra over it is the search. This class only
 * finds the player's own stations, which the line editor offers as candidates.
 */
public final class TransitPlanner {

    private TransitPlanner() {
    }

    /**
     * Plans a journey over the lines, or reports that there is none.
     *
     * @return the journey, or {@link Trip#empty()} when no line can carry it
     */
    public static Trip plan(RoadNetwork network, List<TransitLine> lines, double startX, double startZ,
                            double goalX, double goalZ, String destinationName,
                            RoutePreferences preferences) {
        if (lines.isEmpty()) {
            HowToGo.LOGGER.info("[HowToGo] public transport: no lines configured");
            return Trip.empty();
        }
        return LinePlanner.plan(network, lines, startX, startZ, goalX, goalZ, destinationName,
                preferences);
    }

    /**
     * The whole journey as one route, for the map, the readout and the estimate.
     *
     * <p>One route rather than a chain of them because that is what everything downstream
     * understands: the map draws {@code points()}, the readout measures along it, the estimate sums
     * it. Each leg's own pace travels with it inside the route's per-piece paces, so a walked stretch
     * is timed as a walk and a ridden one as a ride without any of those callers knowing there was
     * more than one mode involved.
     *
     * <p>What is lost by flattening is the ability to say <em>which</em> line is in force at a given
     * moment -- a route carries one mode, so the panel says "public transport" while the player walks
     * to a stop. The boarding, alighting and interchange points are unaffected: they were fixed when
     * the legs were planned, from stop coordinates.
     *
     * @return the journey, or {@link Route#empty()} when no line can carry it
     */
    public static Route planRoute(RoadNetwork network, List<TransitLine> lines, double startX,
                                  double startZ, double goalX, double goalZ, String destinationName,
                                  RoutePreferences preferences) {
        Trip trip = plan(network, lines, startX, startZ, goalX, goalZ, destinationName, preferences);
        if (!trip.isPresent()) {
            return Route.empty();
        }
        List<Route> parts = new ArrayList<>(trip.legs().size());
        for (Trip.Leg leg : trip.legs()) {
            parts.add(leg.route());
            // One line per leg, with its length. "The route goes the long way round" is not diagnosable
            // from the whole journey's length: a walk that detours and a ride that loops look identical
            // from outside, and only the per-leg figures say which of them did it.
            HowToGo.LOGGER.info("[HowToGo] public transport leg: {} {} blocks", leg.mode().id(),
                    Math.round(leg.route().totalLength()));
        }
        return Route.concat(parts, TravelMode.TRANSIT, destinationName);
    }

    /**
     * The player's own stations: place nodes marked as stations, in no particular order.
     *
     * <p>The other half of the world's stations -- the ones Create's track graph reports -- are not
     * place nodes at all; they arrive as ordinary rail vertices, so no search over the road network
     * could pick them out. {@code TransitStops} is where the two are put together.
     */
    public static List<LineStop> markedStations(RoadNetwork network) {
        List<LineStop> result = new ArrayList<>();
        for (RoadNode node : network.nodes()) {
            if (node.type() == RoadNode.Type.POI && node.placeKind() == PlaceKind.STATION) {
                result.add(LineStop.ofPlace(node.id(), node.name(), node.x(), node.z()));
            }
        }
        return result;
    }
}
