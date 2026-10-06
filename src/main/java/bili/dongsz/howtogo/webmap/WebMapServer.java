package bili.dongsz.howtogo.webmap;

import bili.dongsz.howtogo.HowToGo;
import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The browser map's little web server: a page, its assets, and one JSON reading of the roads.
 *
 * <h2>What it is</h2>
 * {@code com.sun.net.httpserver.HttpServer}, bound to the loopback interface on a port the player
 * can set, serving this mod's own files out of the jar and one API call that answers with
 * {@link RoadMapSnapshot}. No servlet container, no dependency, no internet: the whole feature is
 * three hundred kilobytes of page and one HTTP port opened by the game the player is already running,
 * which is what makes it work on a train.
 *
 * <h2>Why loopback and only loopback</h2>
 * The page draws the world the player is standing in, and it is not behind a password. Binding
 * {@code 0.0.0.0} to be helpful would publish a player's map, place names and coordinates to every
 * machine on whatever network they happen to be joined to -- a hotel Wi-Fi, a school, a LAN party --
 * and there is no version of "just trust the local network" that is worth that. So the address is
 * {@code 127.0.0.1}, hard-coded, named in the chat message in full, and there is no configuration key
 * that widens it.
 *
 * <h2>Why the request paths are a fixed list</h2>
 * A static file server's classic bug is a path that walks out of its directory. There is no directory
 * to walk out of here: the table below maps request paths to resource names one for one, so anything
 * not in it -- {@code /../..}, a percent-encoded slash, a name that does not exist -- is a 404 by
 * construction rather than by a check that has to be right. It also means a page asking for an asset
 * that no longer exists fails loudly in the harness, because the harness fetches every entry.
 *
 * <h2>Threading</h2>
 * Handlers run on this server's own daemon threads, never on the game's. The reading they hand back
 * was taken on the game's thread by the {@link RoadMapSource} they were built with -- see
 * {@link MainThreadRoadMapSource} for why that matters -- and the two-thread executor exists so that
 * a handler waiting on a busy game cannot also block the page's other requests, which is exactly what
 * would happen with the default single dispatcher thread.
 */
public final class WebMapServer implements AutoCloseable {

    /** Where this feature's files live in the jar. */
    private static final String RESOURCE_ROOT = "/assets/howtogo/webmap/";

    /** How many connections may be waiting to be accepted. Small: this serves one browser. */
    private static final int BACKLOG = 16;

    /** How many consecutive ports to try when the configured one is taken. */
    private static final int PORT_ATTEMPTS = 10;

    private static final String JSON = "application/json; charset=utf-8";
    private static final String HTML = "text/html; charset=utf-8";
    private static final String CSS = "text/css; charset=utf-8";
    private static final String JS = "text/javascript; charset=utf-8";

    /**
     * Request path to resource name, exactly.
     *
     * <p>An ordered map rather than a {@code Map.of}, so that the list a reader sees is the list the
     * server serves and the harness reports missing entries in a stable order.
     */
    private static final Map<String, String> ASSETS = new LinkedHashMap<>();

    static {
        ASSETS.put("/", "index.html");
        ASSETS.put("/index.html", "index.html");
        ASSETS.put("/style.css", "style.css");
        ASSETS.put("/js/model.js", "js/model.js");
        ASSETS.put("/js/geom.js", "js/geom.js");
        ASSETS.put("/js/labels.js", "js/labels.js");
        ASSETS.put("/js/render.js", "js/render.js");
        ASSETS.put("/js/app.js", "js/app.js");
        ASSETS.put("/vendor/html2canvas.min.js", "vendor/html2canvas.min.js");
    }

    private static final Gson GSON = new Gson();

    private final RoadMapSource source;
    private final ExecutorService workers;
    private final int port;
    private final String url;
    private final List<HttpServer> bound;
    private volatile boolean running = true;

    private WebMapServer(RoadMapSource source, List<HttpServer> bound, ExecutorService workers,
                         int port) {
        this.source = source;
        this.bound = List.copyOf(bound);
        this.workers = workers;
        this.port = port;
        this.url = "http://127.0.0.1:" + port + "/";
    }

    /**
     * Starts the server on the first free port at or after {@code preferredPort}.
     *
     * <p>The port is tried and then the nine above it, because the configured one being taken is
     * ordinary -- a previous session that has not let go of the socket, or another mod's own little
     * server -- and answering "could not start" to a player who never chose the port is a worse
     * failure than running one port along. Which port it actually got is reported: the chat message
     * and {@link #url()} both name it, so a bookmark can be right even after the fallback.
     *
     * <h2>Why two sockets and not one address</h2>
     * A player who is told {@code http://127.0.0.1:7573/} will sometimes type
     * {@code http://localhost:7573/} instead, and on a dual-stack machine -- Windows resolves
     * localhost to {@code ::1} first and {@code 127.0.0.1} second, which is the default -- that means
     * the browser tries an address nothing is listening on and only reaches the map after its
     * IPv6-to-IPv4 fallback delay. Every new connection, so every page load, starts with a few hundred
     * milliseconds of nothing. So both loopback addresses are bound on the same port: the one the chat
     * message names, and the other spelling of it. Both are still loopback -- this is not a widening of
     * who can reach the page, and there is no key that widens it.
     *
     * <p>The IPv6 socket is the optional one: a machine with IPv6 switched off has no {@code ::1} to
     * bind, which is not a reason to refuse to serve the map, so a failure there is logged and the
     * server runs on the IPv4 address alone. A failure to bind the IPv4 one means the port is taken.
     *
     * @throws IOException if no port in that range could be bound on the loopback address
     */
    public static WebMapServer start(RoadMapSource source, int preferredPort) throws IOException {
        int first = Math.max(1024, Math.min(65535, preferredPort));
        IOException failure = null;
        for (int candidate = first; candidate < first + PORT_ATTEMPTS && candidate <= 65535;
                candidate++) {
            List<HttpServer> bound = new ArrayList<>(2);
            try {
                bound.add(bind("127.0.0.1", candidate));
            } catch (BindException taken) {
                failure = taken;
                continue;
            }
            try {
                bound.add(bind("::1", candidate));
            } catch (IOException noIpv6) {
                HowToGo.diagnostic("[HowToGo] webmap | no IPv6 loopback to bind as well: {}",
                        noIpv6.toString());
            }
            ExecutorService workers = Executors.newFixedThreadPool(2,
                    daemonThreads("HowToGo-webmap-" + candidate + "-"));
            WebMapServer started = new WebMapServer(source, bound, workers, candidate);
            for (HttpServer server : bound) {
                server.createContext("/", started::handle);
                server.setExecutor(workers);
                server.start();
            }
            HowToGo.LOGGER.info("[HowToGo] webmap | listening on {} (loopback only, {} socket(s))",
                    started.url, bound.size());
            return started;
        }
        throw new IOException("no free port between " + first + " and "
                + (first + PORT_ATTEMPTS - 1) + "; the last failure was: " + failure, failure);
    }

    /**
     * One listening socket on one named loopback address.
     *
     * <p>The address is named rather than taken from {@code InetAddress.getLoopbackAddress()}, which
     * on a dual-stack machine hands back whichever of the two the JVM prefers: a page opened at the
     * other spelling would then not connect, and "localhost" would work on some machines and not
     * others. Both are bound deliberately instead; see {@link #start}.
     */
    private static HttpServer bind(String loopback, int port) throws IOException {
        return HttpServer.create(
                new InetSocketAddress(InetAddress.getByName(loopback), port), BACKLOG);
    }

    /** The URL to open, with the port that was actually bound. */
    public String url() {
        return url;
    }

    public int port() {
        return port;
    }

    public boolean running() {
        return running;
    }

    /** The exact request paths this server answers; for the harness, and for documentation. */
    public static Set<String> servedPaths() {
        return ASSETS.keySet();
    }

    /** The API paths, which are not files; kept apart so the asset check does not look for them. */
    public static Set<String> apiPaths() {
        return Set.of("/api/roads", "/api/status");
    }

    /** Stops serving and releases the port. Idempotent. */
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        for (HttpServer server : bound) {
            try {
                server.stop(0);
            } catch (RuntimeException failed) {
                // One socket failing to close is not a reason to leave the other one listening.
                HowToGo.LOGGER.warn("[HowToGo] webmap | stopping a socket threw {}",
                        failed.toString());
            }
        }
        workers.shutdownNow();
        HowToGo.LOGGER.info("[HowToGo] webmap | stopped");
    }

    @Override
    public void close() {
        stop();
    }

    // ------------------------------------------------------------------ requests

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            if (!"GET".equals(method)) {
                exchange.getResponseHeaders().set("Allow", "GET");
                respond(exchange, 405, JSON, error("only GET is served here"));
                return;
            }
            if (path == null || path.indexOf("..") >= 0) {
                // Not reachable through the table below either way -- an exact-match miss is a 404 --
                // but a request that tries is worth a line in the log.
                HowToGo.LOGGER.warn("[HowToGo] webmap | refused a suspicious path: {}", path);
                respond(exchange, 404, JSON, error("no such path"));
                return;
            }
            switch (path) {
                case "/api/roads" -> serveRoads(exchange);
                case "/api/status" -> serveStatus(exchange);
                default -> serveAsset(exchange, path);
            }
        } catch (Throwable failed) {
            // A handler that throws leaves the browser with no response at all, which reads as the
            // server being dead. Say 500 and log the rest.
            HowToGo.LOGGER.error("[HowToGo] webmap | a request failed", failed);
            quietly(exchange, 500, error("the mod could not answer this request"));
        } finally {
            exchange.close();
        }
    }

    /** The one call the page really needs: the roads, straight from the game's thread. */
    private void serveRoads(HttpExchange exchange) throws IOException {
        RoadMapSnapshot snapshot;
        try {
            snapshot = source.snapshot();
        } catch (RoadMapUnavailableException unavailable) {
            // 503 rather than an empty map: "there is nothing to draw yet" and "there is nothing to
            // draw at all" are different answers, and only one of them is worth retrying.
            HowToGo.diagnostic("[HowToGo] webmap | roads unavailable: {}", unavailable.getMessage());
            respond(exchange, 503, JSON, error(unavailable.getMessage()));
            return;
        }
        byte[] body = snapshot.toJson(System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8);
        respond(exchange, 200, JSON, body);
    }

    /** A tiny liveness answer, so a page can tell "server down" from "no world loaded". */
    private void serveStatus(HttpExchange exchange) throws IOException {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("ok", true);
        status.put("port", port);
        status.put("source", "HowToGo");
        status.put("address", "127.0.0.1");
        respond(exchange, 200, JSON, GSON.toJson(status).getBytes(StandardCharsets.UTF_8));
    }

    private void serveAsset(HttpExchange exchange, String path) throws IOException {
        String resource = ASSETS.get(path);
        if (resource == null) {
            respond(exchange, 404, JSON, error("no such path"));
            return;
        }
        byte[] body = readResource(resource);
        if (body == null) {
            HowToGo.LOGGER.error("[HowToGo] webmap | {} is missing from the mod jar", resource);
            respond(exchange, 500, JSON, error("this build is missing " + resource));
            return;
        }
        respond(exchange, 200, contentType(resource), body);
    }

    /** Reads a bundled file, or null when this build does not have it. */
    private static byte[] readResource(String name) throws IOException {
        try (InputStream stream = WebMapServer.class.getResourceAsStream(RESOURCE_ROOT + name)) {
            if (stream == null) {
                return null;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(1024, stream.available()));
            stream.transferTo(out);
            return out.toByteArray();
        }
    }

    private static String contentType(String resource) {
        if (resource.endsWith(".html")) {
            return HTML;
        }
        if (resource.endsWith(".css")) {
            return CSS;
        }
        if (resource.endsWith(".js")) {
            return JS;
        }
        return "application/octet-stream";
    }

    /** Writes a response, replacing a body that has already been sent rather than throwing. */
    private static void respond(HttpExchange exchange, int status, String contentType, byte[] body)
            throws IOException {
        // No caching anywhere: the jar the page came from changes when the game restarts, and a
        // stale app.js next to a fresh payload is a bug report about a version the player is not
        // running.
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    /** A response for a path that failed for a reason we only want to log, not to send. */
    private static void quietly(HttpExchange exchange, int status, byte[] body) {
        try {
            respond(exchange, status, JSON, body);
        } catch (IOException alreadyGone) {
            // The browser closed the tab, or the socket went away while the handler was failing. There
            // is nobody left to tell and nothing to clean up.
        }
    }

    /** An error body in the documented shape: {@code {"error": "..."}}. */
    private static byte[] error(String message) {
        return GSON.toJson(Map.of("error", message == null ? "unknown error" : message))
                .getBytes(StandardCharsets.UTF_8);
    }

    private static ThreadFactory daemonThreads(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, prefix + counter.incrementAndGet());
            // Daemon: the server must never be the reason the game will not exit. Its socket is
            // closed explicitly when the client shuts down, and this is the belt to that pair of
            // braces -- a launcher that kills the client rather than closing it still gets to exit.
            thread.setDaemon(true);
            return thread;
        };
    }
}
