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
 * <h2>What lives here</h2>
 * One {@link WebMapServer} for the session, started by the player's command or by the config, stopped
 * by the command, by the client exiting, or by the JVM going down. The server is kept rather than
 * rebuilt per world: it serves whatever world is loaded at the moment a request arrives, so opening
 * another world changes the map without touching the socket, and a bookmark keeps working.
 *
 * <h2>The thread hand-off</h2>
 * The source this service builds is a {@link MainThreadRoadMapSource} whose queue is
 * {@link ClientScheduler} -- the mod's own "run this on the next client tick" hook -- and whose reader
 * walks the network. That is the whole of the requirement that the data be read on the game's thread:
 * the HTTP handler never sees a {@link RoadNetwork}, only the flattened snapshot the reader built on
 * the client thread, which is a copy of values and shares nothing with the live network.
 *
 * <p>The place endpoint goes through the same hand-off: its reader walks {@code Destinations} and
 * {@code PlaceDetailStore} on the game's thread and returns one JSON string. A place's full text is
 * not in the map payload -- one line per place is -- so this is the second reader the source is
 * built with, not an exception to the rule.
 *
 * <h2>Why the reading is not cached</h2>
 * {@link RoadNetwork#revision()} would be the obvious cache key and is deliberately not used: this
 * mod's own storage contract says a rename does not bump it, so a cache keyed on it would keep serving
 * a road's old name for the whole session. Requests here are a player pressing refresh or loading a
 * page, which is a handful a minute, and flattening a network of a few thousand segments costs about
 * as long as the JSON takes to serialise -- so the reading is taken per request and the page is never
 * wrong about a name.
 *
 * <h2>Why the page's theme travels in the roads payload</h2>
 * The browser map follows the game's interface theme, and the theme is not a constant: {@code auto}
 * asks the dimension whether it has a sky and the sky how bright it is. That is a question for the
 * game's thread, so it is answered in {@link #readRoads()} beside the class colours and carried in
 * the same JSON. There is deliberately no second endpoint and no second poll: the page already fetches
 * the roads on load, on refresh and on its five-second poll, so the theme changes exactly when the
 * map does and a tab that is not refreshing cannot end up with a palette from another moment.
 */
public final class WebMapService {

    private static final Object LOCK = new Object();

    /**
     * How many characters of a place's description the map payload carries.
     *
     * <p>The payload is fetched on load, on refresh and on the five-second poll, and a full
     * description per place would be the largest field in it. What the page carries is the
     * description cut to this many characters -- with its line breaks kept, because the card can
     * wrap -- and {@code 查看完整详情} fetches the rest from {@code /api/place} for the one place whose
     * card is open.
     */
    private static final int PLACE_DESCRIPTION_CHARS = 200;

    /**
     * The source id of a point picked on the map for one trip.
     *
     * <p>It is not a registered {@link bili.dongsz.howtogo.route.DestinationSource} and never appears
     * in {@code Destinations.all()} today, but it is spelled here so that a future source with the
     * same idea cannot put a target with no details on the map as though it were a place.
     */
    private static final String MAP_PIN_SOURCE = "map";

    /**
     * The serialiser for the place endpoint's body.
     *
     * <p>Same reason as the map payload's: {@code disableHtmlEscaping} keeps Chinese readable in a
     * response a player may inspect in a browser's network tab, and the body is fetched rather than
     * embedded, so there is nothing for HTML escaping to protect here.
     */
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private static WebMapServer server;

    /** Whether auto-start has been tried and failed; a session does not retry every tick. */
    private static final AtomicBoolean autoStartFailed = new AtomicBoolean();

    private static boolean shutdownHookInstalled;

    private WebMapService() {
    }

    /**
     * Starts the server on the configured port, or the next free one.
     *
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
     * The per-tick job: starts the server once a world is loaded when the player has asked for that.
     *
     * <p>Waiting for a world is the point. Started at client setup the socket would exist through the
     * title screen and every "no world is loaded" 503 would be a request from a page the player
     * opened before a world existed -- so the config starts it when there is something to draw, and
     * an explicit command starts it whenever the player likes.
     *
     * <p>A failed start is remembered rather than retried: the usual cause is that every port in the
     * range is taken, which will not have changed a tick later, and a log line per tick is how a
     * quiet problem becomes an unreadable one.
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
            // The process is exiting; there is nothing useful left to do about a failure here, and
            // throwing out of a shutdown hook prints a stack trace nobody can act on.
        }
    }

    /**
     * The reading the browser map would be served, for a caller already on the game's thread.
     *
     * <p>The API takes this through {@link MainThreadRoadMapSource}; the in-game self-test cannot,
     * because it runs *on* the client thread and would be waiting for itself. This is the same
     * reader -- including the read-only layers the world map draws -- so a check through it sees
     * exactly what a browser tab would get.
     */
    public static RoadMapSnapshot liveSnapshot() throws RoadMapUnavailableException {
        return readRoads();
    }

    /**
     * Reads the roads. <b>Runs on the game's client thread</b>, queued there by the source.
     *
     * <p>Which is why it can touch {@link RoadStore} and {@link Minecraft} at all.
     */
    private static RoadMapSnapshot readRoads() throws RoadMapUnavailableException {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            throw new RoadMapUnavailableException("no world is loaded in the game client");
        }
        RoadNetwork network = RoadStore.get();
        String dimension = minecraft.level.dimension().location().toString();
        // Read here, on the game's thread, rather than left to the flattening: the browser map is
        // served from another thread, and a config value -- or, below, a level and its sky -- is not
        // something to be asking from there.
        return RoadMapSnapshot.of(WorldFiles.currentWorldKey(), dimension, network,
                RoadConfig.roadColors(), readOnlyLayers(), pageTheme(), readPlaces());
    }

    /**
     * The places the browser map is to mark, flattened from the same sources the picker lists.
     *
     * <p>Built from {@link Destinations#all()} rather than from {@link Destinations#places()} on
     * purpose: the in-game map deliberately leaves Xaero's waypoints to Xaero, but the browser map is
     * a read-only second view of the same places and a waypoint with a description is a place a
     * player wants to see there. Every entry carries only what the destination and its detail record
     * already know, so this cannot disagree with the picker about a name or a position.
     *
     * <p>Map pins are skipped: a point picked on the map for one trip has no detail record and no
     * source that could answer for it, so it is a navigation target rather than a place.
     *
     * <p>Runs on the game's client thread; {@link PlaceDetailStore} and the sources are not safe to
     * read anywhere else.
     */
    private static List<RoadMapSnapshot.Place> readPlaces() {
        List<Destination> destinations = Destinations.all();
        List<RoadMapSnapshot.Place> places = new ArrayList<>(destinations.size());
        for (Destination destination : destinations) {
            if (destination == null || MAP_PIN_SOURCE.equals(destination.source())) {
                continue;
            }
            PlaceDetails details = PlaceDetailStore.detailsOf(destination);
            // The cut payload form of the description rather than the one-line preview: the card
            // can wrap, so keeping the line breaks means a description that begins with a heading
            // reads as one, and the flag is what tells the page to offer the full text.
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
     * The full detail of one place, as the {@code /api/place} body, or null when it is not here.
     *
     * <p>Read on the game's thread, queued there by the source, exactly as {@link #readRoads()} is.
     * The place is found by its source and its source-stable id among the current destination list,
     * which is also the list the map payload was built from -- so a place the page can see is a place
     * this can answer for, and one that was deleted after the page loaded comes back as null (the
     * endpoint's 404) rather than as an empty record.
     *
     * <p>Runs on the game's client thread; {@link PlaceDetailStore} and the sources are not safe to
     * read anywhere else.
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

    /** A place's kind as the payload spells it, for a destination that somehow has none. */
    private static String kindName(Destination destination) {
        PlaceKind kind = destination.kind();
        return kind == null ? PlaceKind.PLACE.name() : kind.name();
    }

    /**
     * The palette the browser page is to draw in, as the payload spells it.
     *
     * <p>{@link UiTheme#light()} is asked here, on the client thread, because its answer depends on
     * the player's {@code ui_theme} setting, the dimension (the Nether and the End are always dark)
     * and the sky's own brightness in the {@code auto} mode, and none of that may be touched from an
     * HTTP handler. The handler only ever sees the resulting word.
     *
     * <p>The theme is folded into the roads payload rather than served from an endpoint of its own,
     * because the page fetches the roads on load, on refresh and on its poll already; a theme that
     * arrived on those fetches cannot be a theme the page forgot to ask for, and no second request
     * can race the roads it belongs with.
     */
    private static String pageTheme() {
        return UiTheme.light() ? RoadMapSnapshot.LIGHT_THEME : RoadMapSnapshot.DARK_THEME;
    }

    /**
     * The layers the game's world map draws over the player's roads, in its own paint order.
     *
     * <p>The browser map is meant to be the same map: a world whose roads were generated by another
     * mod showed an empty browser map because only {@link RoadStore} was read. They are read here, on
     * the client thread, from the stores that own them -- each is replaced whole when it is re-read,
     * so the snapshot is a copy of one consistent layer, never a half-updated one.
     *
     * <p>Empty layers are left out rather than sent as empty arrays, so a player without the optional
     * mods sends exactly the payload they always did.
     */
    private static List<RoadMapSnapshot.ReadOnlyLayer> readOnlyLayers() {
        List<RoadMapSnapshot.ReadOnlyLayer> layers = new ArrayList<>(4);
        addReadOnlyLayer(layers, "ROADWEAVER", RoadWeaverLayer.segments());
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
