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
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The browser map's web server: a page, its assets and two read-only JSON answers.
 *
 * com.sun.net.httpserver.HttpServer on the loopback interface, serving this mod's files from the jar plus /api/roads (RoadMapSnapshot) and /api/place (one place's full detail when its card opens). No servlet container and no dependency; the game the player already runs opens the port, which is what makes it work on a train.
 * Loopback only, hard-coded to 127.0.0.1 with no config key: the page shows the player's world, place names and coordinates unprotected, so binding 0.0.0.0 would publish them to whatever network the machine joined.
 * Request paths are a fixed table mapping path to resource name one for one: no path can walk out of a directory, and anything not listed is a 404 by construction rather than by a check.
 * Handlers run on the server's daemon threads, never the game's; the snapshot they hand back was read on the game thread by the RoadMapSource they were built with (see MainThreadRoadMapSource). The two-thread executor keeps a handler waiting on a busy game from blocking the page's other requests.
 */
public final class WebMapServer implements AutoCloseable {

    private static final String RESOURCE_ROOT = "/assets/howtogo/webmap/";

    private static final int BACKLOG = 16;

    private static final int PORT_ATTEMPTS = 10;

    /**
     * How long stop() waits for a running handler, in milliseconds.
     *
     * One second, spent on the client's shutdown path: a handler still waiting is waiting on a game thread that is itself going away, so drop the request and let the daemon follow the JVM down.
     */
    private static final long SHUTDOWN_WAIT_MILLIS = 1000L;

    /**
     * The longest a place query parameter may be, in characters, before the request is refused.
     *
     * Real ids are a dozen characters at most, while 256 is far below a length that could bloat a log line, so a longer request is not a client of this page and is answered with a 400 rather than given work.
     */
    private static final int MAX_PLACE_PARAM_LENGTH = 256;

    private static final String JSON = "application/json; charset=utf-8";
    private static final String HTML = "text/html; charset=utf-8";
    private static final String CSS = "text/css; charset=utf-8";
    private static final String JS = "text/javascript; charset=utf-8";

    /**
     * Request path to resource name, exactly. Ordered so the served list and the harness's missing-entry report are stable.
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
     * Starts the server on the first free port at or after preferredPort.
     *
     * The configured port being taken is ordinary -- a previous session still holding the socket, another mod's server -- so the next nine are tried too; the port actually bound is named by the chat message and url(). Both loopback addresses are bound on the same port because a browser told 127.0.0.1:port will sometimes try localhost, which on dual-stack Windows resolves to ::1 first and otherwise stalls every connection a few hundred ms. This widens no access.
     * The IPv6 socket is optional: a machine without IPv6 has no ::1 to bind, which is not a reason to refuse serving, so the failure is logged. Failing to bind the IPv4 address means the port is taken.
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
            HowToGo.logInfo("[HowToGo] webmap | listening on {} (loopback only, {} socket(s))",
                    started.url, bound.size());
            return started;
        }
        throw new IOException("no free port between " + first + " and "
                + (first + PORT_ATTEMPTS - 1) + "; the last failure was: " + failure, failure);
    }

    /**
     * One listening socket on one named loopback address.
     *
     * Named deliberately: InetAddress.getLoopbackAddress() returns whichever address the JVM prefers on a dual-stack machine, so the other spelling would not connect. Both are bound deliberately; see start.
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

    /** The exact request paths this server answers; for the harness and for documentation. */
    public static Set<String> servedPaths() {
        return ASSETS.keySet();
    }

    /**
     * The API paths, kept apart so the asset check does not look for them.
     *
     * /api/place is listed without its query string: a request is routed by path and then reads ?source=...&id=... from the query. A caller comparing a URL must strip the query first, which WebMapHttpCheck does.
     */
    public static Set<String> apiPaths() {
        return Set.of("/api/roads", "/api/status", "/api/place");
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
        // The sockets are closed, so nothing new can arrive; what is left is a handler waiting on the game's
        // thread. The wait is deliberately short because this runs on the client's shutdown path -- a mod
        // that slows the game's exit is worse than a dropped request. The threads are daemons for the same
        // reason.
        workers.shutdown();
        try {
            if (!workers.awaitTermination(SHUTDOWN_WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
                List<Runnable> dropped = workers.shutdownNow();
                if (!dropped.isEmpty()) {
                    HowToGo.LOGGER.warn("[HowToGo] webmap | {} queued requests dropped on shutdown",
                            dropped.size());
                }
            }
        } catch (InterruptedException interrupted) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
        HowToGo.logInfo("[HowToGo] webmap | stopped");
    }

    @Override
    public void close() {
        stop();
    }


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
                // Not reachable through the table below either way -- an exact-match miss is a 404 -- but a request
                // that tries is worth a log line.
                HowToGo.LOGGER.warn("[HowToGo] webmap | refused a suspicious path: {}", path);
                respond(exchange, 404, JSON, error("no such path"));
                return;
            }
            switch (path) {
                case "/api/roads" -> serveRoads(exchange);
                case "/api/status" -> serveStatus(exchange);
                case "/api/place" -> servePlace(exchange);
                default -> serveAsset(exchange, path);
            }
        } catch (Throwable failed) {
            // A handler that throws leaves the browser with no response, which reads as a dead server. Say 500
            // and log the rest.
            HowToGo.LOGGER.error("[HowToGo] webmap | a request failed", failed);
            quietly(exchange, 500, error("the mod could not answer this request"));
        } finally {
            exchange.close();
        }
    }

    private void serveRoads(HttpExchange exchange) throws IOException {
        RoadMapSnapshot snapshot;
        try {
            snapshot = source.snapshot();
        } catch (RoadMapUnavailableException unavailable) {
            // 503 rather than an empty map: "there is nothing to draw yet" and "there is nothing to draw at all"
            // are different answers, and only one is worth retrying.
            HowToGo.diagnostic("[HowToGo] webmap | roads unavailable: {}", unavailable.getMessage());
            respond(exchange, 503, JSON, error(unavailable.getMessage()));
            return;
        }
        byte[] body = snapshot.toJson(System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8);
        respond(exchange, 200, JSON, body);
    }

    private void serveStatus(HttpExchange exchange) throws IOException {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("ok", true);
        status.put("port", port);
        status.put("source", "HowToGo");
        status.put("address", "127.0.0.1");
        respond(exchange, 200, JSON, GSON.toJson(status).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The full detail of one place, read on the game's thread, for a card the player just opened.
     *
     * A malformed request is a 400, a place this dimension does not have is a 404 (the id may be one an edit has removed), and no world loaded is the 503 /api/roads gives. A 200 is whatever body the source built; nothing here formats JSON itself, so a name with a quote cannot break parsing.
     */
    private void servePlace(HttpExchange exchange) throws IOException {
        Map<String, String> query;
        try {
            query = queryOf(exchange.getRequestURI().getRawQuery());
        } catch (IllegalArgumentException malformed) {
            respond(exchange, 400, JSON,
                    error("the place query could not be read: " + malformed.getMessage()));
            return;
        }
        String placeSource = query.get("source");
        String placeId = query.get("id");
        if (!acceptablePlaceParam(placeSource) || !acceptablePlaceParam(placeId)) {
            respond(exchange, 400, JSON, error("the place endpoint needs a source and an id, both "
                    + "non-blank and at most " + MAX_PLACE_PARAM_LENGTH + " characters"));
            return;
        }

        String body;
        try {
            body = source.placeJson(placeSource, placeId);
        } catch (RoadMapUnavailableException unavailable) {
            // The same answer as a roads request with no world loaded, because it is the same situation: there
            // is nothing to read yet, and retrying once the game is ready works.
            HowToGo.diagnostic("[HowToGo] webmap | place unavailable: {}", unavailable.getMessage());
            respond(exchange, 503, JSON, error(unavailable.getMessage()));
            return;
        }
        if (body == null) {
            // Not an error in the page: a marker from an older reading, or a place deleted behind the open tab.
            // The card shows the message and stops asking.
            respond(exchange, 404, JSON,
                    error("no place " + placeSource + "/" + placeId + " in this dimension"));
            return;
        }
        respond(exchange, 200, JSON, body.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean acceptablePlaceParam(String value) {
        return value != null && !value.isBlank() && value.length() <= MAX_PLACE_PARAM_LENGTH;
    }

    /**
     * A query string as a map of decoded values.
     *
     * Only the standard form encoding a browser applies is needed: both parameters are mod-produced ids the page URL-encodes. First value wins on a duplicate.
     */
    private static Map<String, String> queryOf(String rawQuery) {
        Map<String, String> values = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return values;
        }
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int equals = pair.indexOf('=');
            String name = equals < 0 ? pair : pair.substring(0, equals);
            String value = equals < 0 ? "" : pair.substring(equals + 1);
            values.putIfAbsent(URLDecoder.decode(name, StandardCharsets.UTF_8),
                    URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return values;
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

    private static void respond(HttpExchange exchange, int status, String contentType, byte[] body)
            throws IOException {
        // No caching anywhere: the jar the page came from changes when the game restarts, and a stale app.js
        // next to a fresh payload is a bug report about a version the player is not running.
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    private static void quietly(HttpExchange exchange, int status, byte[] body) {
        try {
            respond(exchange, status, JSON, body);
        } catch (IOException alreadyGone) {
            // The browser closed the tab, or the socket went away while the handler was failing; nobody is left
            // to tell.
        }
    }

    private static byte[] error(String message) {
        return GSON.toJson(Map.of("error", message == null ? "unknown error" : message))
                .getBytes(StandardCharsets.UTF_8);
    }

    private static ThreadFactory daemonThreads(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, prefix + counter.incrementAndGet());
            // Daemon: the server must never be the reason the game will not exit. Its socket is closed
            // explicitly when the client shuts down, and this is the belt to that pair of braces.
            thread.setDaemon(true);
            return thread;
        };
    }
}
