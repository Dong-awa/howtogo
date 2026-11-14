package bili.dongsz.howtogo.client;

import java.util.List;

/**
 * Where a public transport line's name is offered and where it may finally stand: the placement rule,
 * checked without a game.
 *
 * <h2>Why this is checked off the game</h2>
 * A line's name is a fixed-size capsule drawn on six different maps, and the one thing the player
 * reported about it is invisible in code review: the plate was allowed to walk along the line in
 * <em>blocks</em> when its first place was taken, which at a close zoom is hundreds of pixels -- so a
 * name ended up over ground its line does not run along. Nothing on screen says which of the two rules
 * is in force; only the arithmetic does. Every question here is geometry with no Minecraft in it, so
 * the whole rule can be pinned here.
 *
 * <h2>What is held</h2>
 * That places are offered every 160 blocks and at both of the line's ends exactly (not 24 blocks past
 * them, which is the same drift in disguise); that every offered place is on the line; that a free
 * place is used as offered; that a taken place is lifted by exactly one plate height and no more, and
 * never moved along the line; that a candidate whose whole neighbourhood is off the map is left
 * unnamed rather than dragged onto it; and that the capsule's own size is the one {@link LineNameLabels}
 * states.
 */
public final class LineLabelCheck {

    private static int checks;
    private static int failures;

    private LineLabelCheck() {
    }

    /**
     * Runs the checks.
     *
     * @return the number of checks made and the number that failed, in that order
     */
    public static int[] run() {
        checks = 0;
        failures = 0;
        System.out.println("== transit line name plates ==");
        offeredPlaces();
        placedAtTheOfferedPlace();
        liftedWhenThePlaceIsTaken();
        leftUnnamedWhenEveryPlaceIsTaken();
        capsuleSize();
        if (failures == 0) {
            System.out.println("  transit line name plates ok (" + checks + " checks)");
        } else {
            System.out.println("  transit line name plates FAILED: " + failures + " of " + checks);
        }
        return new int[]{checks, failures};
    }

    /** A straight line eastwards, four hundred blocks long, with its points as the map draws them. */
    private static List<double[]> straightLine() {
        return List.of(new double[]{0, 0}, new double[]{100, 0}, new double[]{400, 0});
    }

    /**
     * The places offered: every 160 blocks, and the two ends themselves.
     *
     * <p>The ends are the part that used to be wrong: an offer 24 blocks about the terminus is a couple
     * of dozen pixels away on a zoomed-out map and nearly five hundred on a close one, which is the
     * drift the player saw. The end of the drawn line is where the plate belongs; the capsule's own
     * half-width is what reaches past the end.
     */
    private static void offeredPlaces() {
        List<LineNameLabels.Candidate> places = LineNameLabels.candidatePoints(straightLine());
        int expectedInterior = (int) Math.floor((400.0 - 1.0E-9) / LineNameLabels.SPACING_BLOCKS);
        expect("a name is offered every " + (int) LineNameLabels.SPACING_BLOCKS
                        + " blocks, plus both ends (" + (expectedInterior + 2) + " places)",
                places.size() == expectedInterior + 2);
        expect("the first place is the line's own start", near(places.get(0).x(), 0.0)
                && near(places.get(0).z(), 0.0));
        LineNameLabels.Candidate last = places.get(places.size() - 1);
        expect("and the last place is its own end, not a margin past it", near(last.x(), 400.0)
                && near(last.z(), 0.0));
        boolean onTheLine = true;
        for (LineNameLabels.Candidate place : places) {
            onTheLine &= near(place.z(), 0.0) && place.x() >= -1.0E-9 && place.x() <= 400.0 + 1.0E-9;
        }
        expect("and every place is on the line rather than beside it", onTheLine);
        expect("the interior places are the spacing apart",
                places.size() > 2 && near(places.get(1).distance(),
                        LineNameLabels.SPACING_BLOCKS));
    }

    /** A free place is used where it was offered: on the line, raised by the caller's own lift. */
    private static void placedAtTheOfferedPlace() {
        LineNameLabels.Candidate place = LineNameLabels.candidatePoints(straightLine()).get(1);
        double width = LineNameLabels.width(20);
        double height = LineNameLabels.height(9);
        double rise = 7.0;
        // One pixel a block, so the screen position of a place is its world position: an arithmetic
        // that is easy to read rather than a second projection to trust.
        Recording screen = new Recording(-1000.0, -1000.0, 1000.0, 1000.0);
        double[] spot = LineNameLabels.find(place, width, height, rise, screen);
        expect("a free place is used where it was offered", spot != null);
        if (spot != null) {
            expect("at the place itself, raised clear of the stroke",
                    near(spot[0], place.x()) && near(spot[1], place.z() - rise));
        }
    }

    /**
     * A taken place is lifted, not walked down the line.
     *
     * <p>The lift is one plate height and the sideways position does not move at all: that is the
     * difference between "beside the line" and the old hunt, which moved the plate along the line by
     * blocks and is the drift the player reported.
     */
    private static void liftedWhenThePlaceIsTaken() {
        LineNameLabels.Candidate place = LineNameLabels.candidatePoints(straightLine()).get(1);
        double width = LineNameLabels.width(20);
        double height = LineNameLabels.height(9);
        double rise = 7.0;
        Recording screen = new Recording(-1000.0, -1000.0, 1000.0, 1000.0);
        screen.takeFirstFit = 1;
        double[] spot = LineNameLabels.find(place, width, height, rise, screen);
        expect("a taken place is offered a second one", spot != null);
        if (spot != null) {
            expect("which is one plate height higher", near(spot[1],
                    place.z() - rise - height - LineNameLabels.PLACE_GAP_PX));
            expect("and never further along the line than the place it belongs to",
                    near(spot[0], place.x()));
        }
    }

    /**
     * A place the map cannot use at all leaves the stretch unnamed, rather than putting the name where
     * its line does not go.
     */
    private static void leftUnnamedWhenEveryPlaceIsTaken() {
        LineNameLabels.Candidate place = LineNameLabels.candidatePoints(straightLine()).get(1);
        double width = LineNameLabels.width(20);
        double height = LineNameLabels.height(9);
        Recording taken = new Recording(-1000.0, -1000.0, 1000.0, 1000.0);
        taken.alwaysTaken = true;
        expect("every place taken means no name on this stretch",
                LineNameLabels.find(place, width, height, 7.0, taken) == null);

        // A place whose whole neighbourhood is off the map: the map refuses it, and the placer must not
        // drag it back onto the screen -- the old hunt would have stepped back along the line and drawn
        // the name at a strange distance from its line.
        LineNameLabels.Candidate far = new LineNameLabels.Candidate(2000, 0, 2000, 1, 0);
        Recording small = new Recording(0.0, 0.0, 300.0, 300.0);
        expect("a place off the map is left unnamed rather than moved onto it",
                LineNameLabels.find(far, width, height, 7.0, small) == null);
    }

    /** The capsule's size is the stated factor of the font, and the same on every map. */
    private static void capsuleSize() {
        expect("a capsule is the name plus its padding, at the shared factor",
                near(LineNameLabels.width(10), (10 + LineNameLabels.PAD_X * 2.0)
                        * (double) LineNameLabels.NAME_FACTOR));
        expect("and its height the same way",
                near(LineNameLabels.height(9), (9 + LineNameLabels.PAD_Y * 2.0)
                        * (double) LineNameLabels.NAME_FACTOR));
    }

    /** A screen that records how many fits it was asked about, and can refuse the first ones. */
    private static final class Recording implements LineNameLabels.Screen {

        private final double left;
        private final double top;
        private final double right;
        private final double bottom;
        int takeFirstFit;
        boolean alwaysTaken;

        Recording(double left, double top, double right, double bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        @Override
        public double screenX(double worldX, double worldZ) {
            return worldX;
        }

        @Override
        public double screenY(double worldX, double worldZ) {
            return worldZ;
        }

        @Override
        public boolean fits(double boxLeft, double boxTop, double boxRight, double boxBottom) {
            if (alwaysTaken) {
                return false;
            }
            if (takeFirstFit > 0) {
                takeFirstFit--;
                return false;
            }
            return boxLeft >= left && boxRight <= right && boxTop >= top && boxBottom <= bottom;
        }
    }

    private static boolean near(double actual, double wanted) {
        return Math.abs(actual - wanted) <= 1.0E-4;
    }

    private static void expect(String what, boolean condition) {
        checks++;
        if (!condition) {
            failures++;
        }
        System.out.println((condition ? "  ok   " : "  FAIL ") + what);
    }
}
