import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.road.RoadNode;
import bili.dongsz.howtogo.road.RoadSegment;
import bili.dongsz.howtogo.road.RoadStorage;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Scratch probe: what is drawn at each near-coincident node, and what crosses what there. */
public final class JoinProbe {

    public static void main(String[] args) {
        RoadNetwork net = RoadStorage.load(Path.of(args[0]));
        List<RoadNode> nodes = net.nodesSnapshot();
        List<RoadSegment> segments = net.segmentsSnapshot();

        Map<Integer, List<String>> atNode = new HashMap<>();
        Map<Integer, Set<String>> classesAt = new HashMap<>();
        for (RoadSegment segment : segments) {
            for (int id : new int[]{segment.fromNode(), segment.toNode()}) {
                if (id == RoadSegment.NO_NODE) {
                    continue;
                }
                atNode.computeIfAbsent(id, k -> new ArrayList<>())
                        .add("seg" + segment.id() + "(" + segment.roadClass()
                                + (segment.name() == null ? "" : " " + segment.name())
                                + " L" + segment.layer()
                                + (segment.fromNode() == id ? " from" : " to") + ")");
                classesAt.computeIfAbsent(id, k -> new HashSet<>())
                        .add(segment.roadClass().name());
            }
        }

        System.out.println("=== nodes with no segment at all ===");
        int orphans = 0;
        for (RoadNode node : nodes) {
            if (!atNode.containsKey(node.id())) {
                orphans++;
                System.out.println("   node " + node.id() + " (" + node.x() + "," + node.y() + ","
                        + node.z() + ") type " + node.type() + " name " + node.name());
            }
        }
        System.out.println("   " + orphans + " of " + nodes.size());

        System.out.println("=== near-coincident pairs that the pass can join ===");
        List<RoadNode> list = new ArrayList<>(nodes);
        for (int i = 0; i < list.size(); i++) {
            for (int j = i + 1; j < list.size(); j++) {
                RoadNode a = list.get(i);
                RoadNode b = list.get(j);
                double gap = Math.hypot(a.x() - b.x(), a.z() - b.z());
                if (gap > 3.0
                        || !classesAt.containsKey(a.id()) || !classesAt.containsKey(b.id())) {
                    continue;
                }
                System.out.println("node " + a.id() + " (" + a.x() + "," + a.y() + "," + a.z() + ")"
                        + " gap " + String.format("%.1f", gap) + " rise "
                        + Math.abs(a.y() - b.y()) + "  <->  node " + b.id() + " (" + b.x() + ","
                        + b.y() + "," + b.z() + ")");
                System.out.println("      at " + a.id() + ": " + atNode.get(a.id()));
                System.out.println("      at " + b.id() + ": " + atNode.get(b.id()));
            }
        }

        System.out.println("=== the highway segments ===");
        for (RoadSegment segment : segments) {
            if (segment.roadClass().name().equals("HIGHWAY")) {
                System.out.println("   seg" + segment.id() + " name=" + segment.name() + " L"
                        + segment.layer() + " from node" + segment.fromNode() + " to node"
                        + segment.toNode() + " (" + segment.vertexCount() + " vertices)"
                        + " len " + Math.round(segment.length()));
                for (int v = 0; v < segment.vertexCount(); v++) {
                    System.out.println("        v" + v + " (" + segment.x(v) + "," + segment.z(v) + ")");
                }
            }
        }
    }
}
