package bili.dongsz.howtogo.api;

import bili.dongsz.howtogo.client.Destinations;
import bili.dongsz.howtogo.route.Destination;
import bili.dongsz.howtogo.route.DestinationSource;

import java.util.ArrayList;
import java.util.List;

/**
 * The addon API's registries, its self-check list and its tick deferral, as an addon would meet them.
 *
 * <h2>What is checked</h2>
 * Not that the API exists -- the compiler says that -- but the rules an addon depends on and cannot
 * see: that the four built-in sources keep the order they have always been listed in, that a
 * registered source joins that list rather than replacing anything, that an id already in use is
 * refused, that "is this a place" and "may this be searched" are the source's own answers rather than
 * a list of ids inside the mod, that a check which throws becomes a failed line in the report rather
 * than a report that never gets written, and that work queued for the next client tick runs on that
 * tick and no sooner.
 *
 * <h2>What is deliberately not checked</h2>
 * {@code Destinations.all()} and {@code Destinations.places()} are not called: they ask every source,
 * and the mod's own sources read the running game through {@code RoadStore}, which the offline
 * harness does not have. What can be checked without a game is checked; the two lines that
 * concatenate the sources are read, not run, and the {@code /howtogo selftest} rig is where the whole
 * path is exercised in a world.
 *
 * <p>The sources and checks registered here stay registered for the rest of the run, which is exactly
 * what registration means: there is no unregister, because a source is a caller in this process and
 * not a resource with a lifetime.
 */
public final class AddonApiCheck {

    private static int checks;
    private static int failures;

    private AddonApiCheck() {
    }

    public static int[] run() {
        System.out.println("== the addon API's registries ==");
        scenarioBuiltInsKeepTheirOrder();
        scenarioAnAddonSourceJoinsTheList();
        scenarioPriorityPlacesASource();
        scenarioADuplicateIdIsRefused();
        scenarioBadRegistrationsAreRefused();
        scenarioPlacesAndSearchAreTheSourcesOwnAnswer();
        scenarioTheNoteKeyFallsBackToTheConvention();
        System.out.println("== the addon API's self checks ==");
        scenarioSelfChecksRunAndReport();
        System.out.println("== the addon API's tick deferral ==");
        scenarioDeferredWorkRunsOnTheNextTick();
        scenarioNestedDeferralWaitsATick();
        System.out.println(failures == 0
                ? "addon api ok (" + checks + " checks)"
                : "addon api FAILED: " + failures + " of " + checks + " checks");
        return new int[]{checks, failures};
    }

    /** The four built-ins, in the order the picker has always listed them. */
    private static void scenarioBuiltInsKeepTheirOrder() {
        List<String> ids = ids(DestinationSources.all());
        expect("the mod's four sources are the first four in the list", ids.size() >= 4);
        expect("and they are in the order they have always been listed",
                ids.subList(0, 4).equals(
                        List.of("poi", "create_station", "mtr_station", "xaero_waypoint")));
        expect("the picker sees the registry's list, not a copy of its own",
                ids(Destinations.sources()).equals(ids));
        expect("the api says the mod is here", HowToGoApi.isPresent());
        expect("and this check was written against the api version the mod reports",
                HowToGoApi.API_VERSION == 1);
    }

    /** A registered source is in the list, counted, and after the built-ins. */
    private static void scenarioAnAddonSourceJoinsTheList() {
        int before = DestinationSources.registeredCount();
        DestinationSources.register(new TestSource("check_addon"));
        expect("a registered source can be found by id",
                DestinationSources.byId("check_addon") != null);
        expect("and it is counted", DestinationSources.registeredCount() == before + 1);
        expect("and it is in the list the picker walks",
                ids(DestinationSources.all()).contains("check_addon"));
        expect("and it sits after the mod's own sources, which is the default priority",
                ids(DestinationSources.all()).indexOf("check_addon") >= 4);
    }

    /** Priority, and only priority, moves a source among the built-ins. */
    private static void scenarioPriorityPlacesASource() {
        DestinationSources.register(new TestSource("check_early", 5));
        List<String> ids = ids(DestinationSources.all());
        expect("a source that asks to come early sits after the one that asked to come first",
                ids.indexOf("check_early") > ids.indexOf("poi"));
        expect("and before the built-in it outranks",
                ids.indexOf("check_early") < ids.indexOf("create_station"));
        expect("while the order is still smallest priority first",
                ids.indexOf("poi") < ids.indexOf("check_early")
                        && ids.indexOf("check_early") < ids.indexOf("create_station"));
    }

    /** A second source answering to a taken id is refused, not substituted. */
    private static void scenarioADuplicateIdIsRefused() {
        DestinationSource first = DestinationSources.byId("check_addon");
        int before = DestinationSources.registeredCount();
        DestinationSources.register(new TestSource("check_addon"));
        expect("a second source with a taken id is refused",
                DestinationSources.registeredCount() == before);
        expect("and the first one is still the one the list holds",
                DestinationSources.byId("check_addon") == first);

        DestinationSource builtIn = DestinationSources.byId("poi");
        DestinationSources.register(new TestSource("poi"));
        expect("a built-in's id cannot be taken either",
                DestinationSources.byId("poi") == builtIn);
        expect("and that attempt added nothing",
                DestinationSources.registeredCount() == before);
    }

    /** Bad registrations are refused loudly or not at all, never silently half-done. */
    private static void scenarioBadRegistrationsAreRefused() {
        int before = DestinationSources.registeredCount();
        DestinationSources.register(new TestSource("   "));
        expect("a source with no id is refused", DestinationSources.registeredCount() == before);

        boolean threw = false;
        try {
            DestinationSources.register(null);
        } catch (NullPointerException expected) {
            threw = true;
        }
        expect("a null source is a bug in the caller rather than an empty list", threw);
        expect("and the registry is unchanged by either", DestinationSources.registeredCount() == before);
    }

    /** Places and search are the source's answers, not a list of ids kept inside the mod. */
    private static void scenarioPlacesAndSearchAreTheSourcesOwnAnswer() {
        expect("the player's own places are places", DestinationSources.marksPlaces("poi"));
        expect("Create stations are places", DestinationSources.marksPlaces("create_station"));
        expect("MTR stations are places", DestinationSources.marksPlaces("mtr_station"));
        expect("waypoints are not, because they belong to Xaero",
                !DestinationSources.marksPlaces("xaero_waypoint"));
        expect("and an id nobody owns is not a place, rather than a guess",
                !DestinationSources.marksPlaces("check_missing"));
        expect("the id-only question agrees with the source's own answer",
                Destinations.isPlaceSource("poi") && !Destinations.isPlaceSource("xaero_waypoint"));

        DestinationSources.register(new TestSource("check_unsearchable", 100, false, false, ""));
        expect("a source that asks not to be searched is not",
                !DestinationSources.searchable("check_unsearchable"));
        expect("but it is still listed", DestinationSources.byId("check_unsearchable") != null);
        expect("an unknown source answers searchable, because that is the harmless way to be wrong",
                DestinationSources.searchable("check_missing"));

        DestinationSources.register(new TestSource("check_place", 100, true, true, ""));
        expect("a source that brings places says so",
                DestinationSources.marksPlaces("check_place"));
    }

    /** The row note: the source's own key where it named one, the convention otherwise. */
    private static void scenarioTheNoteKeyFallsBackToTheConvention() {
        expect("a built-in source names no key of its own",
                DestinationSources.noteKey("poi").isEmpty());
        expect("and neither does an id nobody owns",
                DestinationSources.noteKey("check_missing").isEmpty());
        DestinationSources.register(new TestSource("check_noted", 100, false, true,
                "check.howtogo.note"));
        expect("a source that names its own key is asked for it",
                "check.howtogo.note".equals(DestinationSources.noteKey("check_noted")));
    }

    /** A check that throws is one failed line; the report is still produced. */
    private static void scenarioSelfChecksRunAndReport() {
        int before = SelfChecks.count();
        SelfChecks.register(new SelfCheck() {
            @Override
            public String name() {
                return "check.pass";
            }

            @Override
            public Result run() {
                return Result.pass("all good");
            }
        });
        expect("a registered check is counted", SelfChecks.count() == before + 1);

        int after = SelfChecks.count();
        SelfChecks.register(new SelfCheck() {
            @Override
            public String name() {
                return "check.pass";
            }

            @Override
            public Result run() {
                return Result.pass("the second one, which is refused");
            }
        });
        expect("a second check with a taken name is refused", SelfChecks.count() == after);

        SelfChecks.register(new SelfCheck() {
            @Override
            public String name() {
                return "check.throws";
            }

            @Override
            public Result run() {
                throw new IllegalStateException("check.boom");
            }
        });

        List<SelfChecks.Outcome> outcomes = SelfChecks.runAll();
        SelfChecks.Outcome passing = outcome(outcomes, "check.pass");
        SelfChecks.Outcome throwing = outcome(outcomes, "check.throws");
        expect("a check that passed reports so, with what it saw",
                passing != null && passing.ok() && "all good".equals(passing.detail()));
        expect("a check that threw is a failed line rather than a failed report",
                throwing != null && !throwing.ok());
        expect("and the line names the throwable, which is all the report can honestly say",
                throwing != null && throwing.detail().contains("check.boom"));
        expect("and both checks are in the report", outcomes.size() == SelfChecks.count());
    }

    /** Queued work runs once, on the next tick, not where it was queued. */
    private static void scenarioDeferredWorkRunsOnTheNextTick() {
        List<String> ran = new ArrayList<>();
        ClientScheduler.nextTick(() -> ran.add("queued"));
        expect("work queued for the next tick has not run yet", ran.isEmpty());
        expect("and is waiting to", ClientScheduler.pending() == 1);
        ClientScheduler.tick();
        expect("it runs on the tick", ran.equals(List.of("queued")));
        expect("and the queue is empty afterwards", ClientScheduler.pending() == 0);
        ClientScheduler.tick();
        expect("and it does not run a second time", ran.equals(List.of("queued")));

        boolean threw = false;
        try {
            ClientScheduler.nextTick(null);
        } catch (NullPointerException expected) {
            threw = true;
        }
        expect("queueing nothing is a bug in the caller", threw);
    }

    /** Work queued from work waits a tick: one pass cannot drain a queue that keeps refilling. */
    private static void scenarioNestedDeferralWaitsATick() {
        List<String> ran = new ArrayList<>();
        ClientScheduler.nextTick(() -> {
            ran.add("outer");
            ClientScheduler.nextTick(() -> ran.add("inner"));
        });
        ClientScheduler.tick();
        expect("the outer action runs in the tick it was queued for",
                ran.equals(List.of("outer")));
        ClientScheduler.tick();
        expect("and the action it queued waits for the tick after, rather than this one",
                ran.equals(List.of("outer", "inner")));
    }

    private static SelfChecks.Outcome outcome(List<SelfChecks.Outcome> outcomes, String name) {
        for (SelfChecks.Outcome outcome : outcomes) {
            if (outcome.name().equals(name)) {
                return outcome;
            }
        }
        return null;
    }

    private static List<String> ids(List<DestinationSource> sources) {
        List<String> ids = new ArrayList<>(sources.size());
        for (DestinationSource source : sources) {
            ids.add(source.id());
        }
        return ids;
    }

    /**
     * A source that exists only in this check, answering exactly what it was constructed to answer.
     */
    private static final class TestSource implements DestinationSource {

        private final String id;
        private final int priority;
        private final boolean places;
        private final boolean searchable;
        private final String noteKey;

        TestSource(String id) {
            this(id, 100, false, true, "");
        }

        TestSource(String id, int priority) {
            this(id, priority, false, true, "");
        }

        TestSource(String id, int priority, boolean places, boolean searchable, String noteKey) {
            this.id = id;
            this.priority = priority;
            this.places = places;
            this.searchable = searchable;
            this.noteKey = noteKey;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public String displayName() {
            return "check." + id;
        }

        @Override
        public List<Destination> destinations() {
            return List.of(new Destination("Check " + id, 1, 64, 2, id));
        }

        @Override
        public int priority() {
            return priority;
        }

        @Override
        public boolean marksPlaces() {
            return places;
        }

        @Override
        public boolean searchable() {
            return searchable;
        }

        @Override
        public String noteKey() {
            return noteKey;
        }
    }

    private static void expect(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
        }
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
    }
}
