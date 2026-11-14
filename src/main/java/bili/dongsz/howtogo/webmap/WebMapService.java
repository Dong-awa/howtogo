package bili.dongsz.howtogo.webmap;

import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.RoadConfig;
import bili.dongsz.howtogo.api.ClientScheduler;
import bili.dongsz.howtogo.client.AddonRoadStore;
import bili.dongsz.howtogo.client.Destinations;
import bili.dongsz.howtogo.client.MtrTransit;
import bili.dongsz.howtogo.client.PlaceDetailStore;
import bili.dongsz.howtogo.client.RailTrackStore;
import bili.dongsz.howtogo.client.RoadStore;
import bili.dongsz.howtogo.client.RoadWeaverLayer;
import bili.dongsz.howtogo.client.UiTheme;
import bili.dongsz.howtogo.client.WorldFiles;
import bili.dongsz.howtogo.place.PlaceDetails;
import bili.dongsz.howtogo.place.PlaceKeys;
import bili.dongsz.howtogo.place.PlaceSummary;
import bili.dongsz.howtogo.road.PlaceKind;
import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.road.RoadSegment;
import bili.dongsz.howtogo.route.Destination;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The browser map's lifecycle, and the only place that knows how a request reaches the game.
 *
 * One WebMapServer per session, serving whatever world is loaded. Its source is a
 * MainThreadRoadMapSource queued on ClientScheduler, so the HTTP handler only ever sees a snapshot
 * copied on the client thread; the place endpoint uses the same hand-off.
 *
 * The reading is not cached (RoadNetwork#revision() does not bump on rename) and the page's theme
 * travels in the roads payload, which the page already polls.
 */
public final class WebMapService {

    private static final Object LOCK = new Object();

    /**
     * How many characters of a place's description the map payload carries.
     * The page keeps line breaks; 查看完整详情 fetches the rest from /api/place for the open card.
     */
    private static final int PLACE_DESCRIPTION_CHARS = 200;

    /** The source id of a point picked on the map for one trip; never in Destinations.all(). */
    private static final String MAP_PIN_SOURCE = "map";

    /** Serialiser for the place endpoint; no HTML escaping so Chinese stays readable in the body. */
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private static WebMapServer server;

    /** Whether auto-start has been tried and failed; a session does not retry every tick. */
    private static final AtomicBoolean autoStartFailed = new AtomicBoolean();

    private static boolean shutdownHookInstalled;

    private WebMapService() {
    }

    /**
     * Starts the server on the configured port, or the next free one.
     * @return the URL to open
     * @throws IOException when no port could be bound
     */
    public static String start() throws IOException {
        synchronized (LOCK) {
            if (server != null && server.running()) {
                return server.url();
            }
            WebMapServer started = WebMapServer.start(
                    new MainThreadRoadMapSource(ClientScheduler::nextTick,
                            WebMapService::readRoads, WebMapService::readPlaceDetail),
                    RoadConfig.webMapPort());
            server = started;
            installShutdownHook();
            return started.url();
        }
    }

    /** Stops the server if it is running. @return whether there was one to stop */
    public static boolean stop() {
        synchronized (LOCK) {
            WebMapServer running = server;
            server = null;
            if (running == null) {
                return false;
            }
            running.stop();
            return true;
        }
    }

    /** The URL the map is served at, or null when the server is not running. */
    public static String url() {
        synchronized (LOCK) {
            return server != null && server.running() ? server.url() : null;
        }
    }

    public static boolean running() {
        return url() != null;
    }

    /**
     * The per-tick job: starts the server once a world is loaded when the config asks for that.
     * Started at client setup the socket would exist through the title screen; a failed start is
     * remembered rather than retried because the cause (ports taken) will not change.
     */
    public static void tick() {
        if (autoStartFailed.get() || !RoadConfig.webMapAutoStart()) {
            return;
        }
        if (running() || Minecraft.getInstance().level == null) {
            return;
        }
        try {
            String url = start();
            HowToGo.LOGGER.info("[HowToGo] webmap | auto-started at {}", url);
        } catch (IOException couldNotStart) {
            autoStartFailed.set(true);
            HowToGo.LOGGER.warn("[HowToGo] webmap | could not auto-start: {}", couldNotStart.toString());
        } catch (RuntimeException unexpected) {
            autoStartFailed.set(true);
            HowToGo.LOGGER.error("[HowToGo] webmap | auto-start failed", unexpected);
        }
    }

    /** Stops serving, for the JVM going down. Never throws. */
    public static void shutdown() {
        try {
            stop();
        } catch (Throwable ignored) {
            // The process is exiting; throwing here would only print a stack trace nobody can act on.
        }
    }

    /**
     * The reading the browser map would be served, for a caller already on the game's thread.
     * The in-game self-test cannot go through the API because it runs on that thread and would wait for
     * itself; this is the same reader, including the read-only layers.
     */
    public static RoadMapSnapshot liveSnapshot() throws RoadMapUnavailableException {
        return readRoads();
    }

    /** Reads the roads on the game's client thread; that is why it can touch RoadStore and Minecraft. */
    private static RoadMapSnapshot readRoads() throws RoadMapUnavailableException {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            throw new RoadMapUnavailableException("no world is loaded in the game client");
        }
        RoadNetwork network = RoadStore.get();
        String dimension = minecraft.level.dimension().location().toString();
        // Read here, on the game's thread, because the browser map is served from another thread.
        return RoadMapSnapshot.of(WorldFiles.currentWorldKey(), dimension, network,
                RoadConfig.roadColors(), readOnlyLayers(), pageTheme(), readPlaces());
    }

    /**
     * The places the browser map marks, from the same sources the picker lists.
     * Built from Destinations#all(), not places(): the browser is a read-only second view and Xaero
     * waypoints with descriptions belong there. Map pins are skipped -- no detail record, not places.
     * Runs on the client thread.
     */
    private static List<RoadMapSnapshot.Place> readPlaces() {
        List<Destination> destinations = Destinations.all();
        List<RoadMapSnapshot.Place> places = new ArrayList<>(destinations.size());
        for (Destination destination : destinations) {
            if (destination == null || MAP_PIN_SOURCE.equals(destination.source())) {
                continue;
            }
            PlaceDetails details = PlaceDetailStore.detailsOf(destination);
            // The truncated payload form, not the one-line preview: the card can wrap, and the flag tells the
            // page to offer the full text.
            PlaceSummary description = details.truncated(PLACE_DESCRIPTION_CHARS);
            places.add(new RoadMapSnapshot.Place(
                    PlaceKeys.stableId(destination),
                    destination.name(),
                    destination.x(), destination.y(), destination.z(),
                    kindName(destination),
                    destination.source(),
                    details.tags(),
                    details.favorite(),
                    description.text(),
                    description.truncated()));
        }
        return places;
    }

    /**
     * The full detail of one place as the /api/place body, or null when it is not here.
     * Found by source and source-stable id in the current list the payload was built from, so a place
     * the page can see can be answered; one deleted after load comes back null (404).
     * Runs on the client thread.
     */
    private static String readPlaceDetail(String source, String id)
            throws RoadMapUnavailableException {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            throw new RoadMapUnavailableException("no world is loaded in the game client");
        }
        for (Destination destination : Destinations.all()) {
            if (destination == null || source == null || !source.equals(destination.source())) {
                continue;
            }
            if (id == null || !id.equals(PlaceKeys.stableId(destination))) {
                continue;
            }
            PlaceDetails details = PlaceDetailStore.detailsOf(destination);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("id", id);
            body.put("source", source);
            body.put("name", destination.name() == null ? "" : destination.name());
            body.put("x", destination.x());
            body.put("y", destination.y());
            body.put("z", destination.z());
            body.put("kind", kindName(destination));
            body.put("tags", details.tags());
            body.put("favorite", details.favorite());
            body.put("description", details.description());
            body.put("updatedAt", details.updatedAt());
            return GSON.toJson(body);
        }
        return null;
    }

    /** A place's kind as the payload spells it. */
    private static String kindName(Destination destination) {
        PlaceKind kind = destination.kind();
        return kind == null ? PlaceKind.PLACE.name() : kind.name();
    }

    /**
     * The palette the browser page draws in, as the payload spells it.
     * UiTheme#light() must be asked on the client thread (theme setting, dimension, sky brightness),
     * and the handler only sees the resulting word. Folded into the roads payload because the page
     * already fetches it, so no second request can race the roads.
     */
    private static String pageTheme() {
        return UiTheme.light() ? RoadMapSnapshot.LIGHT_THEME : RoadMapSnapshot.DARK_THEME;
    }

    /**
     * The layers the game's world map draws over the player's roads, in its paint order.
     * Read on the client thread from the stores that own them; each is replaced whole, so the snapshot
     * is one consistent layer. Empty layers are omitted.
     */
    private static List<RoadMapSnapshot.ReadOnlyLayer> readOnlyLayers() {
        List<RoadMapSnapshot.ReadOnlyLayer> layers = new ArrayList<>(4);
        addReadOnlyLayer(layers, "ROADWEAVER", RoadWeaverLayer.drawnSegments());
        addReadOnlyLayer(layers, "CREATE", RailTrackStore.segments());
        RoadNetwork mtr = MtrTransit.railLayer();
        if (mtr != null && mtr.segmentCount() > 0) {
            addReadOnlyLayer(layers, "MTR", mtr.segmentsSnapshot());
        }
        addReadOnlyLayer(layers, "ADDON", AddonRoadStore.segments());
        return layers;
    }

    private static void addReadOnlyLayer(List<RoadMapSnapshot.ReadOnlyLayer> layers, String source,
                                         Collection<RoadSegment> segments) {
        if (segments == null || segments.isEmpty()) {
            return;
        }
        layers.add(new RoadMapSnapshot.ReadOnlyLayer(source, segments));
    }

    private static void installShutdownHook() {
        if (shutdownHookInstalled) {
            return;
        }
        shutdownHookInstalled = true;
        Runtime.getRuntime().addShutdownHook(new Thread(WebMapService::shutdown,
                "HowToGo-webmap-shutdown"));
    }
}
