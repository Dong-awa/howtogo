package bili.dongsz.howtogo.route;

import bili.dongsz.howtogo.road.RoadClass;
import bili.dongsz.howtogo.road.RoadEditor;
import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.road.RoadNode;
import bili.dongsz.howtogo.road.RoadSegment;
import bili.dongsz.howtogo.road.RoadStorage;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * Runs the real router over a real saved road network, outside the game.
 *
 * <p>In the route package on purpose: it needs {@link RoadConflation} and {@link RoadRouter.Workspace},
 * which are package-private. It is a diagnostic, not part of the mod -- it lives with the harness and
 * is compiled the same way.
 *
 * <p>Usage: NetworkInspector &lt;path to a saved network json&gt;
 */
public final class NetworkInspector {

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("usage: NetworkInspector <road json> [x1 z1 x2 z2 ...]");
            return;
        }
        RoadNetwork net = RoadStorage.load(Path.of(args[0]));
        if (args.length >= 5) {
            realPoints(net, args);
            return;
        }
        report(net);
    }

    /**
     * The trips themselves, given as origin and goal coordinates: what a plan from the player's own
     * position to the destination they picked actually answers.
     */
    private static void realPoints(RoadNetwork net, String[] args) {
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (RoadNode node : net.nodesSnapshot()) {
            minX = Math.min(minX, node.x());
            maxX = Math.max(maxX, node.x());
            minZ = Math.min(minZ, node.z());
            maxZ = Math.max(maxZ, node.z());
        }
        System.out.println("road network spans x " + minX + ".." + maxX + ", z " + minZ + ".." + maxZ);

        for (int i = 1; i + 3 < args.length; i += 4) {
            double fromX = Double.parseDouble(args[i]);
            double fromZ = Double.parseDouble(args[i + 1]);
            double toX = Double.parseDouble(args[i + 2]);
            double toZ = Double.parseDouble(args[i + 3]);
            System.out.println();
            System.out.println("trip (" + (int) fromX + "," + (int) fromZ + ") -> (" + (int) toX + ","
                    + (int) toZ + ")");
            for (TravelMode mode : new TravelMode[] {TravelMode.WALK, TravelMode.DRIVE}) {
                RoadNetwork probe = net.deepCopy();
                Route route = RoadRouter.findRoute(probe, fromX, fromZ, toX, toZ, "probe", mode,
                        RoutePreferences.DEFAULTS);
                double nearestFrom = nearestWalkable(net, fromX, fromZ, mode);
                double nearestTo = nearestWalkable(net, toX, toZ, mode);
                System.out.println("   " + mode.id() + ": " + (route.isPresent()
                        ? "route, " + Math.round(route.totalLength()) + " blocks"
                        : "NO ROUTE") + "   (start is " + Math.round(nearestFrom)
                        + " blocks from a usable road, goal is " + Math.round(nearestTo)
                        + ", cap " + Math.round(mode.maxConnectorDistance()) + ")");
            }
        }
    }

    private static double nearestWalkable(RoadNetwork net, double x, double z, TravelMode mode) {
        double best = Double.MAX_VALUE;
        for (RoadSegment segment : net.segmentsSnapshot()) {
            if (!mode.allows(segment.roadClass())) {
                continue;
            }
            if (segment.fromNode() == RoadSegment.NO_NODE || segment.toNode() == RoadSegment.NO_NODE) {
                continue;
            }
            if (net.node(segment.fromNode()) == null || net.node(segment.toNode()) == null) {
                continue;
            }
            for (int i = 1; i < segment.vertexCount(); i++) {
                double ax = segment.x(i - 1);
                double az = segment.z(i - 1);
                double ex = segment.x(i) - ax;
                double ez = segment.z(i) - az;
                double lengthSq = ex * ex + ez * ez;
                double t = lengthSq < 1.0E-9 ? 0
                        : Math.max(0, Math.min(1, ((x - ax) * ex + (z - az) * ez) / lengthSq));
                double px = ax + ex * t;
                double pz = az + ez * t;
                best = Math.min(best, Math.hypot(px - x, pz - z));
            }
        }
        return best;
    }

    private static void report(RoadNetwork net) {
        System.out.println("nodes=" + net.nodeCount() + "  segments=" + net.segmentCount());

        Map<String, Integer> byClass = new TreeMap<>();
        int unwired = 0;
        int missingNode = 0;
        int selfLoop = 0;
        int oneWay = 0;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        int shortSegments = 0;
        for (RoadSegment segment : net.segmentsSnapshot()) {
            byClass.merge(segment.roadClass().name(), 1, Integer::sum);
            if (segment.fromNode() == RoadSegment.NO_NODE || segment.toNode() == RoadSegment.NO_NODE) {
                unwired++;
                continue;
            }
            if (net.node(segment.fromNode()) == null || net.node(segment.toNode()) == null) {
                missingNode++;
            }
            if (segment.fromNode() == segment.toNode()) {
                selfLoop++;
            }
            if (segment.oneWay()) {
                oneWay++;
            }
            minY = Math.min(minY, segment.y());
            maxY = Math.max(maxY, segment.y());
            if (segment.length() < 1.0) {
                shortSegments++;
            }
        }
        System.out.println("classes=" + byClass);
        System.out.println("unwired=" + unwired + "  missingNode=" + missingNode + "  selfLoop="
                + selfLoop + "  oneWay=" + oneWay + "  shorterThanOneBlock=" + shortSegments);
        System.out.println("segment y range=" + minY + ".." + maxY);

        // Height spread among nodes that are close together: how much the height tolerance, when it
        // was there, would have refused.
        List<RoadNode> nodes = net.nodesSnapshot();
        int nearPairs = 0;
        int farApartVertically = 0;
        for (int i = 0; i < nodes.size(); i++) {
            for (int j = i + 1; j < nodes.size(); j++) {
                RoadNode a = nodes.get(i);
                RoadNode b = nodes.get(j);
                double d = Math.hypot(a.x() - b.x(), a.z() - b.z());
                if (d <= 3.0) {
                    nearPairs++;
                    if (Math.abs(a.y() - b.y()) > 4) {
                        farApartVertically++;
                    }
                }
            }
        }
        System.out.println("node pairs within 3 blocks=" + nearPairs
                + ", of which more than 4 blocks apart vertically=" + farApartVertically);

        // Connectivity, over the segments walking is allowed on.
        Map<Integer, Integer> component = walkComponents(net);
        Map<Integer, Integer> sizes = new HashMap<>();
        for (int root : component.values()) {
            sizes.merge(root, 1, Integer::sum);
        }
        List<Integer> sorted = new ArrayList<>(sizes.values());
        sorted.sort((a, b) -> b - a);
        System.out.println("walkable components=" + sorted.size() + "  largest sizes="
                + sorted.subList(0, Math.min(8, sorted.size())));

        // Then the same graph after the repair, to see what the repair does to it.
        RoadNetwork repaired = net.deepCopy();
        int splits = RoadConflation.conflate(repaired, RoadEditor.withoutUndo(repaired));
        Map<Integer, Integer> repairedComponents = walkComponents(repaired);
        Map<Integer, Integer> repairedSizes = new HashMap<>();
        for (int root : repairedComponents.values()) {
            repairedSizes.merge(root, 1, Integer::sum);
        }
        List<Integer> repairedSorted = new ArrayList<>(repairedSizes.values());
        repairedSorted.sort((a, b) -> b - a);
        System.out.println("repair made " + splits + " splits; segments now " + repaired.segmentCount()
                + ", nodes now " + repaired.nodeCount());
        System.out.println("walkable components after repair=" + repairedSorted.size()
                + "  largest sizes=" + repairedSorted.subList(0, Math.min(8, repairedSorted.size())));

        probes(net, "as drawn", component);
        if (splits > 0) {
            probes(repaired, "as repaired", repairedComponents);
        }
    }

    /** Routes between many pairs of nodes that are connected, and says how many come back empty. */
    private static void probes(RoadNetwork net, String label, Map<Integer, Integer> component) {
        List<RoadNode> nodes = new ArrayList<>();
        for (RoadNode node : net.nodesSnapshot()) {
            if (component.containsKey(node.id())) {
                nodes.add(node);
            }
        }
        if (nodes.size() < 2) {
            System.out.println("[" + label + "] no walkable nodes to probe");
            return;
        }
        Random random = new Random(20261002L);
        int pairs = 0;
        int sameComponent = 0;
        int found = 0;
        int emptyButConnected = 0;
        List<String> examples = new ArrayList<>();
        for (int attempt = 0; attempt < 3000 && pairs < 300; attempt++) {
            RoadNode from = nodes.get(random.nextInt(nodes.size()));
            RoadNode to = nodes.get(random.nextInt(nodes.size()));
            if (from.id() == to.id()) {
                continue;
            }
            boolean connected = component.get(from.id()).equals(component.get(to.id()));
            pairs++;
            Route route = RoadRouter.findRoute(net, from.x(), from.z(), to.x(), to.z(), "probe",
                    TravelMode.WALK, RoutePreferences.DEFAULTS);
            if (route.isPresent()) {
                found++;
            }
            if (connected) {
                sameComponent++;
                if (!route.isPresent()) {
                    emptyButConnected++;
                    if (examples.size() < 5) {
                        examples.add("(" + from.x() + "," + from.z() + ") -> (" + to.x() + "," + to.z()
                                + ")");
                    }
                }
            }
        }
        System.out.println("[" + label + "] " + found + "/" + pairs + " pairs routed; "
                + sameComponent + " were connected in the graph, of which " + emptyButConnected
                + " came back with no route");
        for (String example : examples) {
            System.out.println("      connected but unroutable: " + example);
        }
    }

    /** Union-find over the nodes, joined by every segment walking may use. */
    private static Map<Integer, Integer> walkComponents(RoadNetwork net) {
        Map<Integer, Integer> parent = new LinkedHashMap<>();
        for (RoadSegment segment : net.segmentsSnapshot()) {
            if (!TravelMode.WALK.allows(segment.roadClass())) {
                continue;
            }
            int from = segment.fromNode();
            int to = segment.toNode();
            if (from == RoadSegment.NO_NODE || to == RoadSegment.NO_NODE) {
                continue;
            }
            if (net.node(from) == null || net.node(to) == null) {
                continue;
            }
            parent.putIfAbsent(from, from);
            parent.putIfAbsent(to, to);
            union(parent, from, to);
        }
        Map<Integer, Integer> root = new HashMap<>();
        for (int id : parent.keySet()) {
            root.put(id, find(parent, id));
        }
        return root;
    }

    private static int find(Map<Integer, Integer> parent, int id) {
        int root = id;
        while (parent.get(root) != root) {
            root = parent.get(root);
        }
        while (parent.get(id) != root) {
            int next = parent.get(id);
            parent.put(id, root);
            id = next;
        }
        return root;
    }

    private static void union(Map<Integer, Integer> parent, int a, int b) {
        int rootA = find(parent, a);
        int rootB = find(parent, b);
        if (rootA != rootB) {
            parent.put(rootA, rootB);
        }
    }
}
