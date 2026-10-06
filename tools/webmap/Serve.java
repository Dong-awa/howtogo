import bili.dongsz.howtogo.road.RoadClass;
import bili.dongsz.howtogo.road.RoadDirection;
import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.road.RoadNode;
import bili.dongsz.howtogo.road.RoadSegment;
import bili.dongsz.howtogo.webmap.RoadMapSnapshot;
import bili.dongsz.howtogo.webmap.WebMapServer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Serves the browser map without Minecraft, against a made-up city.
 *
 * <h2>Why this exists</h2>
 * The page, its scripts and the export button are the half of this feature that a JVM cannot check:
 * what they need is a browser. Starting a whole game to look at them costs a minute and a world
 * load, and makes "the label ran into the road name beside it" a bug you can only see with a
 * modpack installed.
 *
 * <p>So this stands in for the game's thread: it builds a {@link RoadNetwork} that has one of
 * everything the page has to cope with -- a dense grid of Chinese street names, an overpass over a
 * road at another storey, a tunnel, a river, a railway, one-way streets and named places -- flattens
 * it exactly as {@code WebMapService} does, and hands the finished snapshot to the real
 * {@link WebMapServer}. Everything after that is the shipping code path: the same assets out of the
 * same resource folder, the same HTTP surface, the same JSON.
 *
 * <h2>Running it</h2>
 * <pre>
 *   javac -encoding UTF-8 -cp "&lt;mod classes&gt;;&lt;runtime classpath&gt;" -d tools/webmap/build tools/webmap/Serve.java
 *   java -cp "tools/webmap/build;&lt;mod classes&gt;;src/main/resources;&lt;runtime classpath&gt;" Serve --port=7573 [--payload=out.json] [--seconds=600]
 * </pre>
 * The port defaults to 7573. With {@code --seconds} the server stays up for that long and then stops
 * on its own, which is what running it from a script or a background job needs -- otherwise it waits
 * for a line on standard input, and an unattended run has no input to give it. The arguments are named
 * rather than positional on purpose: a script that leaves one out would otherwise shift the rest along
 * and, say, write the payload into a file named after the seconds. Press Enter (or kill the process) to
 * stop it early. This is a tool, not part of the mod: nothing under {@code src} knows it exists.
 */
public final class Serve {

    /**
     * How long to serve for when there is no console to press Enter on.
     *
     * <p>A run with no console -- a script, a background job, a hidden window -- has no input to give
     * this process, and waiting for a line that can never arrive used to leave it alive for ever. That
     * mattered beyond a stray process: this JVM has the game's own jars on its classpath, and Windows
     * will not let a Gradle build rewrite a jar another process holds open, so an immortal demo server
     * is a build that fails in {@code createMinecraftArtifacts} for a reason nothing on screen
     * explains. So an unattended run serves for a bounded time and then lets go.
     */
    private static final int UNATTENDED_SECONDS = 300;

    public static void main(String[] args) throws IOException {
        int port = 7573;
        String payloadPath = null;
        int seconds = 0;
        for (String arg : args) {
            if (arg.startsWith("--port=")) {
                port = Integer.parseInt(arg.substring("--port=".length()).trim());
            } else if (arg.startsWith("--payload=")) {
                payloadPath = arg.substring("--payload=".length()).trim();
            } else if (arg.startsWith("--seconds=")) {
                seconds = Integer.parseInt(arg.substring("--seconds=".length()).trim());
            } else {
                System.out.println("ignoring an argument this tool does not know: " + arg);
            }
        }

        RoadMapSnapshot snapshot = RoadMapSnapshot.of("sp_demo_city", "minecraft:overworld", city());

        if (payloadPath != null && !payloadPath.isBlank()) {
            Path out = Path.of(payloadPath);
            Files.writeString(out, snapshot.toJson(System.currentTimeMillis()), StandardCharsets.UTF_8);
            System.out.println("payload written to " + out.toAbsolutePath());
        }

        WebMapServer server = WebMapServer.start(() -> snapshot, port);
        System.out.println();
        System.out.println("HowToGo browser map (standalone, no game): " + server.url());
        System.out.println("payload: " + server.url() + "api/roads");
        System.out.println("NOTE: this JVM keeps the game's jars open, so a Gradle build cannot rewrite");
        System.out.println("      them while it runs. Stop this before building.");

        if (seconds <= 0 && System.console() == null) {
            seconds = UNATTENDED_SECONDS;
            System.out.println("no console to press Enter on: serving for " + seconds + " s instead");
        }
        if (seconds > 0) {
            System.out.println("serving for " + seconds + " s");
            System.out.println();
            try {
                Thread.sleep(seconds * 1000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        } else {
            System.out.println("press Enter to stop");
            System.out.println();
            System.in.read();
        }
        server.stop();
    }

    /**
     * A city with one of everything, laid out so that the awkward cases are next to each other.
     */
    private static RoadNetwork city() {
        RoadNetwork network = new RoadNetwork();
        int[] nextId = {1};

        // A grid of named streets, dense enough that the labels genuinely compete for room.
        String[] avenues = {"中山大道", "人民路", "解放路", "建设大道", "长江路", "和平路",
                "First Avenue", "Second Street", "Third Street", "Fourth Street"};
        for (int i = 0; i < avenues.length; i++) {
            int z = -500 + i * 110;
            polyline(network, nextId, RoadClass.ROAD, z == 0 ? 1 : 0, avenues[i],
                    -500, z, -250, z, 0, z, 250, z, 500, z);
        }
        String[] streets = {"北环路", "文化街", "market street", "南山路", "河边街",
                "五一路", "六一路", "七一路", "八一路"};
        for (int i = 0; i < streets.length; i++) {
            int x = -480 + i * 120;
            polyline(network, nextId, RoadClass.ROAD, 0, streets[i],
                    x, -500, x, -200, x, 100, x, 400, x, 520);
        }

        // A highway running across the whole city east to west, and a footpath that shadows it.
        polyline(network, nextId, RoadClass.HIGHWAY, 0, "东西高速 East-West Expressway",
                -900, 60, -600, 60, -300, 60, 0, 60, 300, 60, 600, 60, 900, 60);
        polyline(network, nextId, RoadClass.PATH, 0, "河堤小径",
                -800, 90, -400, 90, 0, 90, 400, 90, 800, 90);

        // A river with bends, crossed by the highway on its own storey: the bridge is at layer 1 and
        // the water below it at layer 0, which is the pair the page has to draw the right way round.
        polyline(network, nextId, RoadClass.WATER, 0, "青川河 Qing River",
                -700, -700, -350, -350, -100, -80, 150, 200, 400, 480, 700, 780);
        RoadSegment bridge = polyline(network, nextId, RoadClass.HIGHWAY, 1, "青川大桥",
                -600, 60, -300, 60, 0, 60, 300, 60, 600, 60);
        bridge.setLayer(1);

        // A tunnel under the river, at the bottom storey.
        RoadSegment tunnel = polyline(network, nextId, RoadClass.ROAD, RoadSegment.MIN_LAYER,
                "过江隧道", -200, -200, 0, -200, 200, -200);
        tunnel.setLayer(RoadSegment.MIN_LAYER);

        // A railway along the south edge, and a short branch that leaves it.
        polyline(network, nextId, RoadClass.RAIL, 0, "环城铁路", -600, 620, -200, 620, 200, 620, 600, 620);
        polyline(network, nextId, RoadClass.RAIL, 0, "支线", 200, 620, 200, 800, 350, 900);

        // One-way streets, both ways round, so the arrows can be seen to point the right way.
        RoadSegment oneWay = polyline(network, nextId, RoadClass.ROAD, 0, "限行路（向东）",
                -300, 170, 0, 170, 300, 170);
        oneWay.setDirection(RoadDirection.FORWARD);
        RoadSegment otherWay = polyline(network, nextId, RoadClass.ROAD, 0, "限行路（向西）",
                -300, 280, 0, 280, 300, 280);
        otherWay.setDirection(RoadDirection.BACKWARD);

        // An ice road across the north, a class nothing else uses.
        polyline(network, nextId, RoadClass.ICE, 0, "冰道", -400, -560, 0, -560, 400, -560);

        // Named places, including one of each kind and a name long enough to need moving.
        place(network, "中央车站 Central Station", 0, 60, bili.dongsz.howtogo.road.PlaceKind.STATION);
        place(network, "矿场", -450, -470, bili.dongsz.howtogo.road.PlaceKind.RESOURCE);
        place(network, "市集 market", 260, 300, bili.dongsz.howtogo.road.PlaceKind.SHOP);
        place(network, "青川河观景台（很长的名字）", 150, 200,
                bili.dongsz.howtogo.road.PlaceKind.PLACE);
        place(network, "北山营地", -300, -300, bili.dongsz.howtogo.road.PlaceKind.PLACE);

        return network;
    }

    /** Adds a road of the given class, running through the given x/z pairs, and names it. */
    private static RoadSegment polyline(RoadNetwork network, int[] nextId, RoadClass roadClass,
                                        int y, String name, int... xz) {
        RoadSegment segment = RoadSegment.of(nextId[0]++, roadClass, y, xz);
        segment.setName(name);
        segment.setFromNode(nodeAt(network, nextId, xz[0], y, xz[1]).id());
        segment.setToNode(nodeAt(network, nextId, xz[xz.length - 2], y, xz[xz.length - 1]).id());
        return network.addSegment(segment);
    }

    /** A node for a road end: an existing one at that spot, or a new endpoint. */
    private static RoadNode nodeAt(RoadNetwork network, int[] nextId, int x, int y, int z) {
        RoadNode near = network.nearestNode(x, z, 1.0);
        if (near != null) {
            return near;
        }
        return network.addNode(x, y, z, RoadNode.Type.ENDPOINT, null);
    }

    /** A named place, on its own: no road joins it, which is the case the map must still show. */
    private static void place(RoadNetwork network, String name, int x, int z,
                              bili.dongsz.howtogo.road.PlaceKind kind) {
        RoadNode node = network.addNode(x, 64, z, RoadNode.Type.POI, name);
        node.setPlaceKind(kind);
    }
}
