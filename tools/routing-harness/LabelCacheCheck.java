package bili.dongsz.howtogo.road;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Checks the reading of a network's chains that the three maps label from: which piece of a road
 * carries the name, what the whole road is made of, and when that reading has to be thrown away.
 *
 * <h2>Why this is checked</h2>
 * The maps used to work both answers out for themselves, by walking the chain of every named road
 * on every frame -- a walk that rebuilds an adjacency index of the entire network before it answers
 * anything. That was measured at megabytes of garbage per frame on a small network and at the whole
 * frame budget on a large one. The reading is now kept until the geometry changes, which makes two
 * things worth pinning down: that the kept answer is the one the walk gave (the drawing must not
 * change), and that it really is thrown away when the roads move (the drawing must not go stale).
 *
 * <p>In the {@code road} package because it is about the model rather than about a map: no rendering
 * is involved, and a screen cannot be built in an offline check.
 */
public final class LabelCacheCheck {

    private static int checks;
    private static int failures;

    private LabelCacheCheck() {
    }

    /**
     * Runs the checks.
     *
     * @return the number of checks made and the number that failed, in that order
     */
    public static int[] run() {
        checks = 0;
        failures = 0;
        System.out.println("== chain reading for labels ==");

        agreesWithTheWalk();
        oneCarrierPerRoad();
        unnamedRoadsStillChooseOne();
        keptUntilTheRoadsMove();
        oneReadingPerNetwork();

        return new int[] {checks, failures};
    }

    /** Every answer the grouping gives is the answer the walk gives. */
    private static void agreesWithTheWalk() {
        RoadNetwork net = street();
        RoadChains.Grouping grouping = RoadChains.group(net);

        boolean carriersAgree = true;
        boolean chainsAgree = true;
        boolean keysAgree = true;
        for (RoadSegment segment : net.segments()) {
            List<Integer> walked = RoadChains.chainContaining(net, segment.id());
            carriersAgree = carriersAgree
                    && grouping.carriesLabel(segment) == (RoadChains.middleSegment(walked) == segment.id());
            chainsAgree = chainsAgree && sameOrder(walked, grouping.chainOf(segment));
            keysAgree = keysAgree && grouping.keyOf(segment)
                    == (walked.isEmpty() ? segment.id() : walked.get(0));
        }
        expect("whether a piece carries the name is what the walk said", carriersAgree);
        expect("and the road a piece belongs to is the walk's own chain, in its own order", chainsAgree);
        expect("as is the road's key, which routing announces turns by", keysAgree);
    }

    /** A road of three pieces draws one name, not three -- and at its middle piece. */
    private static void oneCarrierPerRoad() {
        RoadNetwork net = street();
        RoadChains.Grouping grouping = RoadChains.group(net);

        RoadSegment[] bent = chainOf(net, 3);
        expect("the fixture has a bent street of three pieces", bent.length == 3);
        if (bent.length == 3) {
            expect("and it is one road",
                    grouping.keyOf(bent[0]) == grouping.keyOf(bent[1])
                            && grouping.keyOf(bent[1]) == grouping.keyOf(bent[2]));
            expect("whose middle piece carries the name, and only it",
                    !grouping.carriesLabel(bent[0]) && grouping.carriesLabel(bent[1])
                            && !grouping.carriesLabel(bent[2]));
            expect("and the whole road is what the reading hands back",
                    grouping.chainOf(bent[0]).length == 3
                            && grouping.chainOf(bent[0])[1] == bent[1].id());
        }

        // A piece that is a road on its own, added after the reading was taken: it is a geometry
        // change, so the reading is rebuilt and the new piece answers for itself.
        RoadSegment lone = RoadSegment.of(900, RoadClass.PATH, 64, 5000, 0, 5100, 0);
        lone.setFromNode(nodeAt(net, 5000, 0));
        lone.setToNode(nodeAt(net, 5100, 0));
        net.addSegment(lone);
        expect("a piece that is a road on its own carries its own name",
                RoadChains.cachedGrouping(net).carriesLabel(lone));
    }

    /** The rule is geometry, so an unnamed road still has the piece that would be named. */
    private static void unnamedRoadsStillChooseOne() {
        RoadNetwork net = street();
        for (RoadSegment segment : net.segments()) {
            segment.setName(null);
        }
        RoadChains.Grouping grouping = RoadChains.group(net);
        int carriers = 0;
        for (RoadSegment segment : net.segments()) {
            if (grouping.carriesLabel(segment)) {
                carriers++;
            }
        }
        expect("an unnamed network still has exactly one label point per road",
                carriers == roadCount(net, grouping));
    }

    /**
     * The reading is kept while the roads are unchanged, and dropped when they move.
     *
     * <p>A rename is the case in the middle: it changes what is drawn and nothing about the geometry,
     * so the reading is deliberately kept -- which is only safe because the name drawn is read off the
     * segment itself rather than out of the reading.
     */
    private static void keptUntilTheRoadsMove() {
        RoadNetwork net = street();
        RoadChains.Grouping first = RoadChains.cachedGrouping(net);
        expect("an unchanged network is not read again",
                RoadChains.cachedGrouping(net) == first);

        RoadSegment named = net.segment(101);
        expect("the fixture has the piece the reading was asked about", named != null);
        if (named != null) {
            named.setName("renamed");
            expect("a rename does not throw the reading away, since nothing moved",
                    RoadChains.cachedGrouping(net) == first);
            expect("and the name that gets drawn is the segment's own, not the reading's",
                    "renamed".equals(named.name()));
        }

        RoadSegment added = RoadSegment.of(950, RoadClass.ROAD, 64, 9000, 0, 9100, 0);
        added.setFromNode(nodeAt(net, 9000, 0));
        added.setToNode(nodeAt(net, 9100, 0));
        net.addSegment(added);
        RoadChains.Grouping after = RoadChains.cachedGrouping(net);
        expect("a new road makes the network read again", after != first);
        expect("and the reading includes it", after.carriesLabel(added));
        expect("while still agreeing with the walk about everything else", agrees(net, after));
    }

    /** A frame asks about two or three networks, and none of them may evict the others. */
    private static void oneReadingPerNetwork() {
        RoadNetwork roads = street();
        RoadNetwork rails = street();
        RoadChains.Grouping roadsReading = RoadChains.cachedGrouping(roads);
        RoadChains.Grouping railsReading = RoadChains.cachedGrouping(rails);
        expect("two networks get two readings", roadsReading != railsReading);
        expect("and asking for the first again is still the reading it had",
                RoadChains.cachedGrouping(roads) == roadsReading);
        expect("as is the second", RoadChains.cachedGrouping(rails) == railsReading);
    }

    // ----------------------------------------------------------------- helpers

    /**
     * The shapes a map has to label: a bent street of three pieces, a straight pair, and a fork whose
     * three arms are each a road of one piece.
     */
    private static RoadNetwork street() {
        RoadNetwork net = new RoadNetwork();
        // A bent street: 0,0 -> 200,0 -> 200,200 -> 400,200. Both turns are pass-through nodes, so
        // this is one road of three pieces.
        road(net, 100, "bent", 0, 0, 200, 0);
        road(net, 101, "bent", 200, 0, 200, 200);
        road(net, 102, "bent", 200, 200, 400, 200);
        // A straight road of two pieces, joined at a pass-through node of its own.
        road(net, 110, null, 2000, 0, 2200, 0);
        road(net, 111, null, 2200, 0, 2400, 0);
        // A fork: three arms meeting at one node, so each arm is a road of one piece.
        road(net, 120, null, 4000, 0, 4200, 0);
        road(net, 121, null, 4200, 0, 4400, 0);
        road(net, 122, null, 4200, 0, 4200, 200);
        return net;
    }

    /** One piece between two new nodes, joining wherever a node already sits at an end. */
    private static void road(RoadNetwork net, int id, String name, int x1, int z1, int x2, int z2) {
        RoadSegment segment = RoadSegment.of(id, RoadClass.ROAD, 64, x1, z1, x2, z2);
        segment.setName(name);
        segment.setFromNode(nodeAt(net, x1, z1));
        segment.setToNode(nodeAt(net, x2, z2));
        net.addSegment(segment);
    }

    private static int nodeAt(RoadNetwork net, int x, int z) {
        RoadNode existing = net.nearestNode(x, z, 0.0);
        return existing != null ? existing.id()
                : net.addNode(x, 64, z, RoadNode.Type.JUNCTION, null).id();
    }

    /** The pieces of the first chain with the wanted number of pieces, in chain order. */
    private static RoadSegment[] chainOf(RoadNetwork net, int wanted) {
        Set<Integer> seen = new HashSet<>();
        for (RoadSegment segment : net.segments()) {
            if (seen.contains(segment.id())) {
                continue;
            }
            List<Integer> chain = RoadChains.chainContaining(net, segment.id());
            seen.addAll(chain);
            if (chain.size() == wanted) {
                RoadSegment[] members = new RoadSegment[chain.size()];
                for (int i = 0; i < members.length; i++) {
                    members[i] = net.segment(chain.get(i));
                }
                return members;
            }
        }
        return new RoadSegment[0];
    }

    /** How many roads the network is made of, according to the reading itself. */
    private static int roadCount(RoadNetwork net, RoadChains.Grouping grouping) {
        Set<Integer> keys = new HashSet<>();
        for (RoadSegment segment : net.segments()) {
            keys.add(grouping.keyOf(segment));
        }
        return keys.size();
    }

    private static boolean agrees(RoadNetwork net, RoadChains.Grouping grouping) {
        for (RoadSegment segment : net.segments()) {
            List<Integer> walked = RoadChains.chainContaining(net, segment.id());
            if (grouping.carriesLabel(segment) != (RoadChains.middleSegment(walked) == segment.id())) {
                return false;
            }
            if (!sameOrder(walked, grouping.chainOf(segment))) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameOrder(List<Integer> chain, int[] members) {
        if (chain.size() != members.length) {
            return false;
        }
        for (int i = 0; i < members.length; i++) {
            if (chain.get(i) != members[i]) {
                return false;
            }
        }
        return true;
    }

    private static void expect(String what, boolean condition) {
        checks++;
        if (!condition) {
            failures++;
        }
        System.out.println((condition ? "  ok   " : "  FAIL ") + what);
    }
}
