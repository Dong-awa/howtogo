package bili.dongsz.howtogo.place;

import bili.dongsz.howtogo.road.RoadClass;
import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.road.RoadNode;
import bili.dongsz.howtogo.road.RoadSegment;

/**
 * The address of a place: which road is nearest, what it is called, which side and how far.
 *
 * <h2>Why this is checked off the game</h2>
 * An address is derived, not drawn: the card says "人民路 东侧 12 格" and there is no screen where a
 * player can compare that with the truth. The rules that produce it -- the nearest segment, the
 * named-chain fallback, the dominant axis, the radius -- are all pure geometry, so they are checked
 * here on networks small enough to state the expected answer by hand. That is also why the class
 * under test has no Minecraft import: the half worth holding is the half that can run without a
 * window.
 *
 * <h2>What is held</h2>
 * The ordinary answer (name, class, side, distance), the fallback that prefers a named road over a
 * nearer unnamed one, the promise that the fallback does not reach past the same radius, the three
 * ways of having no name (no road at all, an unnamed road, a named chain), all four sides plus the
 * two cases that must not guess one, the whole-block distance and the radius boundary, and the value
 * object's own normalisation. The chain case uses two segments joined end to end with the name on
 * the far one, because "the whole chain has the name" is the rule a bend would otherwise break.
 */
public final class PlaceAddressCheck {

    private static int checks;
    private static int failures;

    private PlaceAddressCheck() {
    }

    public static int[] run() {
        System.out.println("== place addresses ==");
        nearestNamedRoad();
        unnamedNearestFallsBackToANamedRoad();
        theFallbackStaysInsideTheRadius();
        theClosestNamedRoadWins();
        noRoadInRange();
        noRoadsAtAll();
        sides();
        aNameOnTheChainNamesEveryPiece();
        standingOnTheRoad();
        radiusBoundary();
        theValueObject();
        System.out.println(failures == 0
                ? "place addresses ok (" + checks + " checks)"
                : "place addresses FAILED: " + failures + " of " + checks + " checks");
        return new int[]{checks, failures};
    }

    /** The ordinary answer: the nearest road carries a name, so the address is that name. */
    private static void nearestNamedRoad() {
        RoadNetwork network = new RoadNetwork();
        add(network, 1, RoadClass.ROAD, "人民路", 0, 0, 100, 0);
        PlaceAddress.Address address = PlaceAddress.nearest(network, 50, -10, 64.0);
        expect("the nearest road's name is the address",
                address.onRoad() && "人民路".equals(address.roadName()));
        expect("the class is the road's own",
                address.roadClass() == RoadClass.ROAD);
        expect("a place north of an east-west road is on its north side",
                address.side() == PlaceAddress.Side.NORTH);
        expect("the distance is in whole blocks",
                address.distanceBlocks() == 10);
    }

    /**
     * The fallback: the nearest road has no name, so the address names a road that has one.
     *
     * <p>This is the case the feature exists for -- a driveway in front of a named street -- and the
     * answer must be the street, including the street's class, not the nearer unnamed road's.
     */
    private static void unnamedNearestFallsBackToANamedRoad() {
        RoadNetwork network = new RoadNetwork();
        add(network, 1, RoadClass.ROAD, null, 0, 0, 100, 0);
        add(network, 2, RoadClass.HIGHWAY, "人民路", 0, 30, 100, 30);
        PlaceAddress.Address address = PlaceAddress.nearest(network, 50, 5, 64.0);
        expect("a nearer unnamed road does not win over a named one within the radius",
                "人民路".equals(address.roadName()));
        expect("the named road's class is the one reported, not the nearer road's",
                address.roadClass() == RoadClass.HIGHWAY);
        expect("the side is taken from the named road",
                address.side() == PlaceAddress.Side.NORTH);
        expect("and so is the distance",
                address.distanceBlocks() == 25);
    }

    /**
     * The fallback is bounded by the same radius as the first answer.
     *
     * <p>Otherwise a place beside a nameless road would be given an address across the map, which is
     * worse than being told the road has no name: it would name a road the player is not near.
     */
    private static void theFallbackStaysInsideTheRadius() {
        RoadNetwork network = new RoadNetwork();
        add(network, 1, RoadClass.ROAD, null, 0, 0, 100, 0);
        add(network, 2, RoadClass.ROAD, "很远的路", 0, 100, 100, 100);
        PlaceAddress.Address address = PlaceAddress.nearest(network, 50, 5, 64.0);
        expect("a named road past the radius is not borrowed for the address",
                !address.onRoad() && address.roadName().isEmpty());
        expect("while the nearby unnamed road still says how far it is",
                address.nearRoad() && address.roadClass() == RoadClass.ROAD
                        && address.distanceBlocks() == 5);
    }

    /** Two named roads in range: the nearer one is the address, whichever order they were added in. */
    private static void theClosestNamedRoadWins() {
        RoadNetwork network = new RoadNetwork();
        add(network, 1, RoadClass.ROAD, null, 0, 0, 100, 0);
        add(network, 2, RoadClass.ROAD, "远路", 0, 50, 100, 50);
        add(network, 3, RoadClass.ROAD, "近路", 0, 30, 100, 30);
        PlaceAddress.Address address = PlaceAddress.nearest(network, 50, 5, 64.0);
        expect("the nearest named road wins",
                "近路".equals(address.roadName()) && address.distanceBlocks() == 25);
    }

    /** Nothing within the radius: the empty address, not a guess. */
    private static void noRoadInRange() {
        RoadNetwork network = new RoadNetwork();
        add(network, 1, RoadClass.ROAD, "人民路", 0, 0, 100, 0);
        PlaceAddress.Address address = PlaceAddress.nearest(network, 50, 100, 64.0);
        expect("a road past the radius is no address",
                address.equals(PlaceAddress.Address.NONE) && !address.nearRoad()
                        && !address.onRoad() && address.roadName().isEmpty()
                        && address.side() == PlaceAddress.Side.NONE
                        && address.distanceBlocks() == -1);
    }

    /** No roads to ask about at all, and a radius that cannot contain anything. */
    private static void noRoadsAtAll() {
        expect("a null network has no address",
                PlaceAddress.nearest(null, 0, 0, 64.0).equals(PlaceAddress.Address.NONE));
        expect("an empty network has no address",
                PlaceAddress.nearest(new RoadNetwork(), 0, 0, 64.0)
                        .equals(PlaceAddress.Address.NONE));
        RoadNetwork network = new RoadNetwork();
        add(network, 1, RoadClass.ROAD, "人民路", 0, 0, 100, 0);
        expect("a radius of zero reaches nothing",
                PlaceAddress.nearest(network, 50, 0, 0.0).equals(PlaceAddress.Address.NONE));
        expect("and neither does a negative one",
                PlaceAddress.nearest(network, 50, 0, -5.0).equals(PlaceAddress.Address.NONE));
    }

    /** All four sides by the dominant axis, on both orientations of a road. */
    private static void sides() {
        // Minecraft's +X is east and +Z is south; the place is measured from the road point, so a
        // place at +X of a north-south road is east of it.
        RoadNetwork northSouth = new RoadNetwork();
        add(northSouth, 1, RoadClass.ROAD, "中山路", 0, 0, 0, 100);
        expect("a place east of a north-south road is on its east side",
                PlaceAddress.nearest(northSouth, 12, 50, 64.0).side()
                        == PlaceAddress.Side.EAST);
        expect("a place west of it is on its west side",
                PlaceAddress.nearest(northSouth, -12, 50, 64.0).side()
                        == PlaceAddress.Side.WEST);

        RoadNetwork eastWest = new RoadNetwork();
        add(eastWest, 1, RoadClass.ROAD, "人民路", 0, 0, 100, 0);
        expect("a place south of an east-west road is on its south side",
                PlaceAddress.nearest(eastWest, 50, 12, 64.0).side()
                        == PlaceAddress.Side.SOUTH);
        expect("a place north of it is on its north side",
                PlaceAddress.nearest(eastWest, 50, -12, 64.0).side()
                        == PlaceAddress.Side.NORTH);
    }

    /**
     * A name on any segment of a chain names the whole road.
     *
     * <p>Two pieces joined at a pass-through node: the near piece has no name and the far one does,
     * which is how a road drawn with bends is stored. The address of a point beside the nameless
     * piece must still be the road's name -- otherwise a bend would end the address quality.
     */
    private static void aNameOnTheChainNamesEveryPiece() {
        RoadNetwork network = new RoadNetwork();
        RoadNode start = network.addNode(0, 64, 0, RoadNode.Type.ENDPOINT, null);
        RoadNode middle = network.addNode(50, 64, 0, RoadNode.Type.ENDPOINT, null);
        RoadNode end = network.addNode(100, 64, 0, RoadNode.Type.ENDPOINT, null);
        RoadSegment first = RoadSegment.of(1, RoadClass.ROAD, 64, 0, 0, 50, 0);
        first.setFromNode(start.id());
        first.setToNode(middle.id());
        network.addSegment(first);
        RoadSegment second = RoadSegment.of(2, RoadClass.ROAD, 64, 50, 0, 100, 0);
        second.setFromNode(middle.id());
        second.setToNode(end.id());
        second.setName("文化路");
        network.addSegment(second);

        PlaceAddress.Address nearTheNamelessPiece =
                PlaceAddress.nearest(network, 10, 5, 64.0);
        expect("a name on the far piece of a chain names the piece with no name",
                "文化路".equals(nearTheNamelessPiece.roadName())
                        && nearTheNamelessPiece.distanceBlocks() == 5);
        expect("and the named piece answers the same name from the other end",
                "文化路".equals(PlaceAddress.nearest(network, 90, 5, 64.0).roadName()));
    }

    /** A place standing on the road: a named road, distance zero, and no side to guess. */
    private static void standingOnTheRoad() {
        RoadNetwork network = new RoadNetwork();
        add(network, 1, RoadClass.ROAD, "人民路", 0, 0, 100, 0);
        PlaceAddress.Address address = PlaceAddress.nearest(network, 50, 0, 64.0);
        expect("a place on the road has the road's name and no side",
                address.onRoad() && "人民路".equals(address.roadName())
                        && address.side() == PlaceAddress.Side.NONE
                        && address.distanceBlocks() == 0);
    }

    /** The radius boundary: exactly the radius is in, a shade past it is out. */
    private static void radiusBoundary() {
        RoadNetwork network = new RoadNetwork();
        add(network, 1, RoadClass.ROAD, "人民路", 0, 0, 100, 0);
        expect("a road exactly at the radius is still the address",
                PlaceAddress.nearest(network, 50, 10, 10.0).nearRoad()
                        && PlaceAddress.nearest(network, 50, 10, 10.0).distanceBlocks() == 10);
        expect("one block further is not",
                PlaceAddress.nearest(network, 50, 11, 10.0)
                        .equals(PlaceAddress.Address.NONE));
    }

    /** The value object: three states that cannot be half-set, and the distance never negative. */
    private static void theValueObject() {
        expect("the empty address has no class, no side and no distance",
                !PlaceAddress.Address.NONE.nearRoad()
                        && PlaceAddress.Address.NONE.roadClass() == null
                        && PlaceAddress.Address.NONE.side() == PlaceAddress.Side.NONE
                        && PlaceAddress.Address.NONE.distanceBlocks() == -1);
        PlaceAddress.Address unnamed = new PlaceAddress.Address("", RoadClass.ROAD,
                PlaceAddress.Side.WEST, 12);
        expect("an unnamed road is a road that cannot be named",
                !unnamed.onRoad() && unnamed.nearRoad()
                        && unnamed.distanceBlocks() == 12);
        PlaceAddress.Address cleared = new PlaceAddress.Address(null, null,
                PlaceAddress.Side.EAST, 40);
        expect("a cleared class clears the rest rather than leaving half an answer",
                cleared.equals(PlaceAddress.Address.NONE));
        PlaceAddress.Address negative = new PlaceAddress.Address("人民路", RoadClass.ROAD,
                PlaceAddress.Side.EAST, -3);
        expect("a negative distance is clamped to zero", negative.distanceBlocks() == 0);
        PlaceAddress.Address padded = new PlaceAddress.Address("  人民路  ", RoadClass.ROAD,
                PlaceAddress.Side.EAST, 12);
        expect("a name is trimmed before it is drawn",
                "人民路".equals(padded.roadName()));
    }

    // ------------------------------------------------------------------ helpers

    /**
     * One two-point road, named or not.
     *
     * <p>Ids are passed in rather than taken from the network's counter so the tie-break -- which
     * prefers the lower id -- is visible in the checks that care about it, and so a check's network
     * reads the way it was written.
     */
    private static void add(RoadNetwork network, int id, RoadClass roadClass, String name,
                            int fromX, int fromZ, int toX, int toZ) {
        RoadSegment segment = RoadSegment.of(id, roadClass, 64, fromX, fromZ, toX, toZ);
        if (name != null) {
            segment.setName(name);
        }
        network.addSegment(segment);
    }

    private static void expect(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
        }
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
    }
}
