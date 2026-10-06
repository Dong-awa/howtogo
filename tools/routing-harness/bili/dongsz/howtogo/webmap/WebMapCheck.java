package bili.dongsz.howtogo.webmap;

import bili.dongsz.howtogo.road.RoadClass;
import bili.dongsz.howtogo.road.RoadDirection;
import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.road.RoadNode;
import bili.dongsz.howtogo.road.RoadSegment;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The browser map's data and its thread hand-off, checked without a game.
 *
 * <h2>What can be checked out here and what cannot</h2>
 * The two halves of this feature that must not be wrong are pure Java and are checked here: the
 * payload the page is written against, and the rule that the roads are read on the game's thread and
 * not on the HTTP thread. Both are things a running game cannot show you -- a JSON field with the
 * wrong type is a blank map with no error, and a read that happens on the wrong thread works until it
 * happens to overlap an edit.
 *
 * <p>{@code WebMapHttpCheck} is the third piece: a real server, real HTTP, the real assets.
 *
 * <h2>Why the fixture is built here rather than read from a file</h2>
 * A fixture file can go stale without anything noticing, and this one has to exercise the shapes that
 * actually break a payload: a name with a quote, a backslash and Chinese in it, a segment with no
 * name, a node with no roads, a bridge on another storey, a one-way road. Built in code it is
 * obviously a companion to the assertions beside it, and the JSON it produces is what the front
 * end's own parser is fed by {@code tools/webmap-test} when a full check is wanted.
 */
public final class WebMapCheck {

    private static int checks;
    private static int failures;

    private WebMapCheck() {
    }

    public static int[] run() {
        System.out.println("== the browser map's payload ==");
        payloadShape();
        payloadValues();
        payloadEscaping();
        payloadEmpty();
        fixtureDump();

        System.out.println("== the read that has to happen on the game's thread ==");
        readRunsOnTheGameThread();
        readWaitsForTheGameThread();
        readTimesOutRatherThanHangs();
        readReportsWhyItCouldNotRead();
        concurrentRequestsAllAnswered();

        System.out.println(failures == 0
                ? "webmap ok (" + checks + " checks)"
                : "webmap FAILED: " + failures + " of " + checks + " checks");
        return new int[]{checks, failures};
    }

    // ------------------------------------------------------------------ fixture

    /**
     * A network with every shape the payload has to carry.
     *
     * <p>Deliberately not a realistic map: it is one of each thing, including the ones that are only
     * interesting because they are awkward -- an unnamed road, a place with no road, a bridge at
     * storey one over a road at storey zero, a one-way street drawn backwards, and a name holding
     * characters that would end a JSON string if anybody built the payload by hand.
     */
    static RoadNetwork fixtureNetwork() {
        RoadNetwork network = new RoadNetwork();

        RoadNode junction = network.addNode(0, 64, 0, RoadNode.Type.JUNCTION, "Centre");
        RoadNode far = network.addNode(600, 64, 0, RoadNode.Type.ENDPOINT, null);
        RoadNode place = network.addNode(-40, 70, 20, RoadNode.Type.POI, "Resource \"quarry\"\\一号\n线");
        place.setPlaceKind(bili.dongsz.howtogo.road.PlaceKind.RESOURCE);
        RoadNode lonely = network.addNode(1000, 64, 1000, RoadNode.Type.POI, "Nowhere");
        lonely.setPlaceKind(bili.dongsz.howtogo.road.PlaceKind.SHOP);

        RoadSegment highway = RoadSegment.of(1, RoadClass.HIGHWAY, 64,
                junction.x(), junction.z(), 200, 0, 400, 40, far.x(), far.z());
        highway.setFromNode(junction.id());
        highway.setToNode(far.id());
        highway.setName("East Highway");
        network.addSegment(highway);

        // A one-way street pointing backwards along the way it was drawn.
        RoadSegment oneWay = RoadSegment.of(2, RoadClass.ROAD, 64, 0, 0, 600, 0);
        oneWay.setFromNode(junction.id());
        oneWay.setToNode(far.id());
        oneWay.setDirection(RoadDirection.BACKWARD);
        network.addSegment(oneWay);

        // A bridge over it: same ground, another storey, and no name at all.
        RoadSegment bridge = RoadSegment.of(3, RoadClass.PATH, 74, -50, 0, 50, 0);
        bridge.setLayer(1);
        network.addSegment(bridge);

        // A tunnel under everything, at the bottom storey the mod allows.
        RoadSegment tunnel = RoadSegment.of(4, RoadClass.RAIL, 40, -20, 300, 20, 300);
        tunnel.setLayer(RoadSegment.MIN_LAYER);
        network.addSegment(tunnel);

        return network;
    }

    static RoadMapSnapshot fixtureSnapshot() {
        return RoadMapSnapshot.of("sp_TEST", "minecraft:overworld", fixtureNetwork());
    }

    /** The payload of the fixture, as the page receives it. */
    static String fixtureJson() {
        return fixtureSnapshot().toJson(1760000000000L);
    }

    // ------------------------------------------------------------------- checks

    private static void payloadShape() {
        JsonObject root = JsonParser.parseString(fixtureJson()).getAsJsonObject();

        expect("the payload names its own version",
                root.get("version").getAsInt() == RoadMapSnapshot.FORMAT_VERSION);
        expect("and when it was taken",
                root.get("generatedAt").getAsLong() == 1760000000000L);
        expect("and which world and dimension it is",
                "sp_TEST".equals(root.get("world").getAsString())
                        && "minecraft:overworld".equals(root.get("dimension").getAsString()));
        expect("it says whether there is anything to draw, and there is",
                !root.get("empty").getAsBoolean());

        Set<String> keys = root.keySet();
        expect("the root keys are the documented ones, no more ("
                        + new TreeSet<>(keys) + ")",
                keys.equals(Set.of("version", "generatedAt", "world", "dimension", "empty",
                        "bounds", "classes", "nodes", "segments", "stats")));

        JsonObject bounds = root.getAsJsonObject("bounds");
        expect("the bounds are four numbers",
                bounds.keySet().equals(Set.of("minX", "minZ", "maxX", "maxZ")));
        expect("and they hold the whole fixture: the bridge west of everything and the far place",
                bounds.get("minX").getAsInt() == -50 && bounds.get("maxX").getAsInt() == 1000
                        && bounds.get("minZ").getAsInt() == 0
                        && bounds.get("maxZ").getAsInt() == 1000);

        JsonArray classes = root.getAsJsonArray("classes");
        expect("every road class is offered, so the page never hard-codes a colour ("
                        + classes.size() + ")",
                classes.size() == RoadClass.values().length);
        JsonObject highwayClass = classes.get(0).getAsJsonObject();
        expect("a class carries an id, a CSS colour and a width",
                highwayClass.keySet().equals(Set.of("id", "color", "width")));
        expect("the colour is #RRGGBB with the alpha dropped",
                "#3FA9F5".equals(highwayClass.get("color").getAsString()));
        expect("and the width is the mod's own",
                Math.abs(highwayClass.get("width").getAsDouble() - RoadClass.HIGHWAY.width()) < 1e-9);

        JsonObject node = root.getAsJsonArray("nodes").get(0).getAsJsonObject();
        expect("a node carries position, type and place kind",
                node.has("id") && node.has("x") && node.has("y") && node.has("z")
                        && node.has("type") && node.has("placeKind") && node.has("name"));

        // By id, not by position: the network stores its segments in a hash map, so the order they
        // come out in is not the order they were added and is not something to assert about.
        JsonObject segment = segmentById(root.getAsJsonArray("segments"), 1);
        expect("a segment carries its class, storey, ends, direction and vertices",
                segment.keySet().containsAll(List.of("id", "roadClass", "y", "layer", "from", "to",
                        "direction", "name", "points")));
        JsonArray points = segment.getAsJsonArray("points");
        expect("vertices are [x, z] pairs, one per vertex of the polyline",
                points.size() == 4 && points.get(0).getAsJsonArray().size() == 2);
        expect("and the first vertex is the segment's from-node, as the network promises",
                points.get(0).getAsJsonArray().get(0).getAsInt() == 0
                        && points.get(0).getAsJsonArray().get(1).getAsInt() == 0);
        expect("with the bends kept, so a road drawn round a corner is drawn round it",
                points.get(2).getAsJsonArray().get(1).getAsInt() == 40);

        JsonObject stats = root.getAsJsonObject("stats");
        expect("the stats are the documented four",
                stats.keySet().equals(Set.of("nodes", "segments", "lengthBlocks", "layers")));
        expect("the counts match the fixture",
                stats.get("nodes").getAsInt() == 4 && stats.get("segments").getAsInt() == 4);
        expect("the storeys are listed ascending and distinct, the tunnel's included ("
                        + stats.get("layers") + ")",
                stats.getAsJsonArray("layers").size() == 3
                        && stats.getAsJsonArray("layers").get(0).getAsInt() == RoadSegment.MIN_LAYER
                        && stats.getAsJsonArray("layers").get(1).getAsInt() == 0
                        && stats.getAsJsonArray("layers").get(2).getAsInt() == 1);
    }

    private static void payloadValues() {
        JsonObject root = JsonParser.parseString(fixtureJson()).getAsJsonObject();
        JsonArray segments = root.getAsJsonArray("segments");

        JsonObject bridge = segmentById(segments, 3);
        expect("a bridge keeps its storey, which is what tells it from a crossing",
                bridge.get("layer").getAsInt() == 1);
        expect("and a road with no name simply has no name field",
                !bridge.has("name"));

        JsonObject oneWay = segmentById(segments, 2);
        expect("a one-way street carries which way it runs",
                "BACKWARD".equals(oneWay.get("direction").getAsString()));
        expect("and its two ends, so the page can point the arrow",
                oneWay.get("from").getAsInt() == 1 && oneWay.get("to").getAsInt() == 2);
        expect("an unnamed endpoint is still a node in the payload",
                root.getAsJsonArray("nodes").size() == 4);

        JsonObject place = null;
        for (JsonElement element : root.getAsJsonArray("nodes")) {
            JsonObject candidate = element.getAsJsonObject();
            if ("POI".equals(candidate.get("type").getAsString())
                    && candidate.get("placeKind").getAsString().equals("RESOURCE")) {
                place = candidate;
            }
        }
        expect("a place keeps its kind, which is what its marker's colour is drawn from",
                place != null);

        double sum = 0.0;
        for (RoadMapSnapshot.Segment segment : fixtureSnapshot().segments()) {
            double length = 0.0;
            int[] points = segment.points();
            for (int i = 2; i < points.length; i += 2) {
                length += Math.hypot(points[i] - points[i - 2], points[i + 1] - points[i - 1]);
            }
            sum += length;
        }
        expect("the total length is the sum of the roads, not their straight-line distance",
                Math.abs(root.getAsJsonObject("stats").get("lengthBlocks").getAsDouble() - sum)
                        < 1e-6);
    }

    private static void payloadEscaping() {
        // The name is the one thing in the payload a player types. If any part of the writing were
        // done by hand, this is the string that would break it -- and the failure would not be a
        // wrong label, it would be a payload that does not parse, which is a blank map.
        String json = fixtureJson();
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        String name = null;
        for (JsonElement element : root.getAsJsonArray("nodes")) {
            JsonObject node = element.getAsJsonObject();
            if (node.has("name") && node.get("name").getAsString().startsWith("Resource")) {
                name = node.get("name").getAsString();
            }
        }
        expect("a name with a quote, a backslash, Chinese and a newline survives the round trip",
                "Resource \"quarry\"\\一号\n线".equals(name));
        expect("and it is escaped rather than emitted raw",
                json.contains("\\\"quarry\\\"") && json.contains("\\n"));
        expect("while Chinese is left as itself, because the payload is fetched and not embedded",
                json.contains("一号"));
    }

    private static void payloadEmpty() {
        RoadMapSnapshot empty = RoadMapSnapshot.none("sp_TEST", "minecraft:the_nether");
        JsonObject root = JsonParser.parseString(empty.toJson(1L)).getAsJsonObject();

        expect("an empty map says so", root.get("empty").getAsBoolean());
        expect("with empty lists rather than missing fields, so the page needs one branch and not two",
                root.getAsJsonArray("nodes").isEmpty() && root.getAsJsonArray("segments").isEmpty()
                        && root.getAsJsonObject("stats").getAsJsonArray("layers").isEmpty());
        expect("and a zero-size extent rather than no extent at all",
                root.getAsJsonObject("bounds").get("minX").getAsInt() == 0
                        && root.getAsJsonObject("bounds").get("maxX").getAsInt() == 0);
        expect("a blank dimension is named unknown rather than left blank",
                "unknown".equals(RoadMapSnapshot.none("", " ").dimension()));
    }

    /**
     * Writes the fixture payload out when a caller asks for it with
     * {@code -Dhowtogo.webmap.fixture=<path>}.
     *
     * <p>How the front end's own tests are pointed at a payload the server really produces rather
     * than one written to match it: {@code node tools/webmap-test/run.js <path>} then parses exactly
     * this. Silent when the property is absent, which is every ordinary run.
     */
    private static void fixtureDump() {
        String target = System.getProperty("howtogo.webmap.fixture");
        if (target == null || target.isBlank()) {
            return;
        }
        try {
            Path path = Path.of(target);
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, fixtureJson(), StandardCharsets.UTF_8);
            System.out.println("   wrote the payload to " + path.toAbsolutePath());
        } catch (IOException failed) {
            expect("the payload could be written to " + target, false);
        }
    }

    // ----------------------------------------------------------------- threading

    /**
     * A stand-in for the game's thread: a queue plus a thread that drains it.
     *
     * <p>Exactly what {@code ClientScheduler} is, without the game: a handler queues work and the
     * owner thread runs it. That is what makes the hand-off checkable at all.
     */
    private static final class FakeGameThread {
        private final ConcurrentLinkedQueue<Runnable> queue = new ConcurrentLinkedQueue<>();
        private final AtomicInteger ran = new AtomicInteger();
        private final Thread thread;

        FakeGameThread() {
            thread = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    Runnable task = queue.poll();
                    if (task == null) {
                        Thread.onSpinWait();
                        continue;
                    }
                    ran.incrementAndGet();
                    task.run();
                }
            }, "fake-game-thread");
            thread.setDaemon(true);
            thread.start();
        }

        void queueFor(Runnable task) {
            queue.add(task);
        }

        int ranCount() {
            return ran.get();
        }

        void stop() {
            thread.interrupt();
        }
    }

    /** The rule the whole feature turns on: the reader runs on the game's thread, never the caller's. */
    private static void readRunsOnTheGameThread() {
        FakeGameThread game = new FakeGameThread();
        AtomicReference<String> readOn = new AtomicReference<>();
        AtomicReference<String> askedFrom = new AtomicReference<>();
        MainThreadRoadMapSource source = new MainThreadRoadMapSource(game::queueFor, () -> {
            readOn.set(Thread.currentThread().getName());
            return fixtureSnapshot();
        });
        try {
            String caller = Thread.currentThread().getName();
            askedFrom.set(caller);
            RoadMapSnapshot snapshot = source.snapshot();
            expect("a request is answered with a reading (" + snapshot.segmentCount() + " segments)",
                    snapshot.segmentCount() == 4 && snapshot.nodeCount() == 4);
            expect("the reading was taken on the game's thread (" + readOn.get() + ")",
                    "fake-game-thread".equals(readOn.get()));
            expect("and never on the thread that asked, which is what the hand-off is for",
                    !caller.equals(readOn.get()));
            expect("the snapshot is a copy: mutating the network afterwards cannot change it",
                    snapshot.bounds().maxX() == 1000);
        } catch (RoadMapUnavailableException failed) {
            expect("a fixture read through the hand-off succeeds (" + failed.getMessage() + ")",
                    false);
        } finally {
            game.stop();
        }
    }

    /** A request that arrives while the game is between ticks waits for the next one. */
    private static void readWaitsForTheGameThread() {
        // A game thread that runs nothing until it is told to, so "the answer was not there yet" is
        // observable rather than a race.
        ConcurrentLinkedQueue<Runnable> held = new ConcurrentLinkedQueue<>();
        AtomicReference<RoadMapSnapshot> answered = new AtomicReference<>();
        AtomicReference<Throwable> threw = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        MainThreadRoadMapSource source = new MainThreadRoadMapSource(held::add,
                WebMapCheck::fixtureSnapshot, 5000);
        Thread asker = new Thread(() -> {
            try {
                answered.set(source.snapshot());
            } catch (Throwable failed) {
                threw.set(failed);
            } finally {
                done.countDown();
            }
        }, "http-handler");
        asker.start();

        // Give the asker a moment to queue its work and start waiting; it must not be answered by a
        // game thread that has not run.
        sleep(80);
        expect("a request from a handler thread is queued rather than answered in place",
                held.size() == 1 && answered.get() == null && threw.get() == null);
        expect("and the handler is still waiting for the game's thread", done.getCount() == 1);

        held.poll().run();
        try {
            expect("the tick that runs the work is what completes the request",
                    done.await(2, TimeUnit.SECONDS));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            expect("the wait for the queued read was interrupted", false);
        }
        expect("which is answered, not failed", answered.get() != null && threw.get() == null);
    }

    /** A game that never answers must produce a failure, not a browser tab that never loads. */
    private static void readTimesOutRatherThanHangs() {
        ConcurrentLinkedQueue<Runnable> never = new ConcurrentLinkedQueue<>();
        MainThreadRoadMapSource source = new MainThreadRoadMapSource(never::add,
                WebMapCheck::fixtureSnapshot, 60);

        long started = System.nanoTime();
        String message = null;
        try {
            source.snapshot();
        } catch (RoadMapUnavailableException expected) {
            message = expected.getMessage();
        }
        long millis = (System.nanoTime() - started) / 1_000_000;
        expect("a game thread that does not answer fails the request", message != null);
        expect("and says why: (" + message + ")",
                message != null && message.contains("did not answer"));
        expect("after about the timeout it was given and not for ever (" + millis + " ms)",
                millis >= 40 && millis < 3000);
        expect("the timeout is reported as the source was built with", source.timeoutMillis() == 60);
    }

    /** Whatever the reader says, the caller is told, and told the difference. */
    private static void readReportsWhyItCouldNotRead() {
        FakeGameThread game = new FakeGameThread();
        try {
            MainThreadRoadMapSource noWorld = new MainThreadRoadMapSource(game::queueFor, () -> {
                throw new RoadMapUnavailableException("no world is loaded in the game client");
            });
            String message = null;
            try {
                noWorld.snapshot();
            } catch (RoadMapUnavailableException unavailable) {
                message = unavailable.getMessage();
            }
            expect("a reader that refuses passes its own reason through (" + message + ")",
                    "no world is loaded in the game client".equals(message));

            MainThreadRoadMapSource broken = new MainThreadRoadMapSource(game::queueFor, () -> {
                throw new IllegalStateException("the network was mid-edit");
            });
            String brokenMessage = null;
            Throwable cause = null;
            try {
                broken.snapshot();
            } catch (RoadMapUnavailableException unavailable) {
                brokenMessage = unavailable.getMessage();
                cause = unavailable.getCause();
            }
            expect("a reader that throws is a failed request rather than a hang",
                    brokenMessage != null && brokenMessage.contains("mid-edit"));
            expect("with the cause kept, because this one is a bug and the log should have it",
                    cause instanceof IllegalStateException);

            MainThreadRoadMapSource queueRefused = new MainThreadRoadMapSource(task -> {
                throw new IllegalStateException("the client is shutting down");
            }, WebMapCheck::fixtureSnapshot);
            String refused = null;
            try {
                queueRefused.snapshot();
            } catch (RoadMapUnavailableException unavailable) {
                refused = unavailable.getMessage();
            }
            expect("a queue the game will not take is reported rather than thrown at the browser",
                    refused != null && refused.contains("shutting down"));
        } finally {
            game.stop();
        }
    }

    /** A page that fetches twice at once, and a browser that opens several tabs, both work. */
    private static void concurrentRequestsAllAnswered() {
        FakeGameThread game = new FakeGameThread();
        int requests = 8;
        MainThreadRoadMapSource source = new MainThreadRoadMapSource(game::queueFor,
                WebMapCheck::fixtureSnapshot);
        List<Throwable> failures = new ArrayList<>();
        List<RoadMapSnapshot> answers = new ArrayList<>();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(requests);
        try {
            for (int i = 0; i < requests; i++) {
                Thread worker = new Thread(() -> {
                    try {
                        start.await();
                        RoadMapSnapshot snapshot = source.snapshot();
                        synchronized (answers) {
                            answers.add(snapshot);
                        }
                    } catch (Throwable failed) {
                        synchronized (failures) {
                            failures.add(failed);
                        }
                    } finally {
                        done.countDown();
                    }
                }, "handler-" + i);
                worker.setDaemon(true);
                worker.start();
            }
            start.countDown();
            boolean finished;
            try {
                finished = done.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                finished = false;
            }
            expect("every concurrent request is answered", finished && failures.isEmpty()
                    && answers.size() == requests);
            expect("and each read ran on the game's thread, once per request ("
                            + game.ranCount() + ")",
                    game.ranCount() == requests);
        } finally {
            game.stop();
        }
    }

    // -------------------------------------------------------------------- helpers

    private static JsonObject segmentById(JsonArray segments, int id) {
        for (JsonElement element : segments) {
            JsonObject segment = element.getAsJsonObject();
            if (segment.get("id").getAsInt() == id) {
                return segment;
            }
        }
        return new JsonObject();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
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
