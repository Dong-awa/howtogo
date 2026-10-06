package bili.dongsz.howtogo.webmap;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The browser map's server, started for real and fetched over real HTTP.
 *
 * <h2>Why this is not mocked</h2>
 * Almost everything that can go wrong with this feature is in the join between the browser and the
 * mod: a content type a browser refuses to execute, a path the page asks for that the server does not
 * serve, a traversal that gets out of the file table, a status code the page cannot tell from an
 * empty map, a handler that throws and answers nothing at all. None of that is visible to a unit test
 * of the JSON, and all of it is visible to {@code HttpClient} on a socket.
 *
 * <p>It also checks the page's own wiring without a browser: every asset the server advertises is
 * fetched, and the paths {@code index.html} refers to are required to be among the ones the server
 * serves -- which is the check that catches a renamed script, the failure that otherwise shows up as
 * a blank page with one line in the browser console.
 *
 * <p>What it does not do is run JavaScript: that is {@code tools/webmap-test}, which parses the very
 * payload this server produces.
 */
public final class WebMapHttpCheck {

    private static int checks;
    private static int failures;

    private WebMapHttpCheck() {
    }

    /** Reused: one client is enough and its connection pool makes the checks quicker. */
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private static final Pattern REFERENCES = Pattern.compile("(?:src|href)=\"([^\"]+)\"");

    public static int[] run() {
        System.out.println("== the browser map's server, over real HTTP ==");

        int port = freePort();
        WebMapServer server;
        try {
            server = WebMapServer.start(WebMapCheck::fixtureSnapshot, port);
        } catch (IOException couldNotStart) {
            expect("a server starts on a free port (" + couldNotStart.getMessage() + ")", false);
            return new int[]{checks, failures};
        }

        try {
            expect("it binds the loopback address and says so in the URL it hands out ("
                            + server.url() + ")",
                    server.url().startsWith("http://127.0.0.1:") && server.running());
            assets(server);
            api(server);
            refusals(server);
            statusCodesForAFailingSource();
            handlersRunInParallel();
        } finally {
            server.stop();
        }
        afterStop(server, port);

        System.out.println(failures == 0
                ? "webmap http ok (" + checks + " checks)"
                : "webmap http FAILED: " + failures + " of " + checks + " checks");
        return new int[]{checks, failures};
    }

    // ------------------------------------------------------------------- assets

    private static void assets(WebMapServer server) {
        Set<String> served = WebMapServer.servedPaths();
        expect("the server advertises the page and its parts (" + served.size() + " paths)",
                served.contains("/") && served.contains("/js/app.js")
                        && served.contains("/vendor/html2canvas.min.js"));

        boolean allThere = true;
        boolean allTyped = true;
        for (String path : served) {
            Result result = get(server.url() + path.substring(1));
            if (result.status != 200 || result.body.isEmpty()) {
                allThere = false;
                System.out.println("      missing: " + path + " -> " + result.status);
                continue;
            }
            String type = result.contentType.toLowerCase(Locale.ROOT);
            boolean typed = path.endsWith(".js") ? type.contains("javascript")
                    : path.endsWith(".css") ? type.contains("css")
                    : type.contains("html");
            if (!typed) {
                allTyped = false;
                System.out.println("      wrong type: " + path + " -> " + result.contentType);
            }
        }
        expect("every advertised path is in the jar and served, so a page cannot ask for a missing file",
                allThere);
        // Conjoined with "every one was there": a page that answers 500 to every asset cannot be
        // said to serve them with the right type, and a check that passes on an empty run is worse
        // than no check at all.
        expect("and each is served as the type a browser will execute rather than sniff",
                allThere && allTyped);

        Result page = get(server.url());
        expect("the page is the map, with a canvas to draw it on",
                page.body.contains("<canvas"));
        expect("and it loads the vendored html2canvas, which is what the export button needs",
                page.body.contains("/vendor/html2canvas.min.js"));

        Result library = get(server.url() + "vendor/html2canvas.min.js");
        expect("the vendored library is really html2canvas (" + library.body.length() + " bytes)",
                library.body.contains("html2canvas") && library.body.length() > 100_000);

        // Every path the page refers to must be one the server promises to serve. A renamed script
        // is otherwise a blank map and one line in a console the player will never open.
        List<String> referenced = new ArrayList<>();
        Matcher matcher = REFERENCES.matcher(page.body);
        while (matcher.find()) {
            String target = matcher.group(1);
            if (target.startsWith("/")) {
                referenced.add(target);
            }
        }
        List<String> unknown = new ArrayList<>();
        for (String target : referenced) {
            String path = target.split("[?#]")[0];
            if (!served.contains(path) && !WebMapServer.apiPaths().contains(path)) {
                unknown.add(target);
            }
        }
        expect("everything the page refers to is a path the server serves (" + referenced + ")",
                !referenced.isEmpty() && unknown.isEmpty());

        Result missing = get(server.url() + "does-not-exist.js");
        expect("a file that is not in the table is a 404 rather than a guess", missing.status == 404);
    }

    // ---------------------------------------------------------------------- api

    private static void api(WebMapServer server) {
        long before = System.currentTimeMillis();
        Result roads = get(server.url() + "api/roads");
        long after = System.currentTimeMillis();

        expect("the API answers 200 with JSON (" + roads.contentType + ")",
                roads.status == 200 && roads.contentType.contains("application/json"));
        expect("and the response is the page's own payload, not a wrapper",
                roads.body.startsWith("{") && roads.body.contains("\"segments\""));

        JsonObject payload = JsonParser.parseString(roads.body).getAsJsonObject();
        expect("which parses and carries the fixture's roads",
                payload.get("version").getAsInt() == RoadMapSnapshot.FORMAT_VERSION
                        && payload.getAsJsonArray("segments").size() == 4);
        expect("the payload is generated per request rather than frozen at startup",
                payload.get("generatedAt").getAsLong() >= before
                        && payload.get("generatedAt").getAsLong() <= after);
        expect("the world and dimension are named, which is what the page's title shows",
                "sp_TEST".equals(payload.get("world").getAsString())
                        && "minecraft:overworld".equals(payload.get("dimension").getAsString()));

        Result status = get(server.url() + "api/status");
        expect("the liveness call answers without touching the roads",
                status.status == 200
                        && JsonParser.parseString(status.body).getAsJsonObject()
                                .get("ok").getAsBoolean());

        // The player is handed the 127.0.0.1 spelling in chat and may well type the localhost one
        // instead. On a dual-stack machine localhost resolves to ::1 first, which is why both
        // loopback addresses are bound; this is the check that the other spelling of the address
        // works at all. It deliberately does not assert *which* socket answered: a machine with IPv6
        // switched off has only the one, and the map is served there too.
        Result localhost = get("http://localhost:" + server.port() + "/api/status");
        expect("the page is reachable at the localhost spelling of its address (status "
                        + localhost.status + ")",
                localhost.status == 200);

        Result roadsAgain = get(server.url() + "api/roads");
        expect("two fetches in a row both work, which is what a refresh button does",
                roadsAgain.status == 200
                        && JsonParser.parseString(roadsAgain.body).getAsJsonObject()
                                .getAsJsonArray("segments").size() == 4);

        expect("no caching anywhere, so a restart cannot leave a stale script next to a live payload",
                roads.noStore && roads.nosniff);
    }

    // ----------------------------------------------------------------- refusals

    private static void refusals(WebMapServer server) {
        Result post = post(server.url() + "api/roads");
        expect("a write is refused with 405 rather than half-served", post.status == 405);
        expect("and the refusal says what is allowed (" + post.allow + ")",
                post.allow.contains("GET"));

        expect("a path that does not exist is a 404",
                get(server.url() + "assets/howtogo/webmap/index.html").status == 404);
        expect("an asset beside a served one is not served by prefix",
                get(server.url() + "js/app.js.bak").status == 404);

        // Percent-encoded so the client cannot normalise it away before it is sent: the server has to
        // be the one that refuses it.
        Result traversal = get(server.url() + "..%2F..%2Fwindows%2Fwin.ini");
        expect("a traversal is refused and answers nothing but JSON (" + traversal.status + ")",
                traversal.status == 404 && traversal.body.contains("error"));
        expect("and the reply cannot be mistaken for a file",
                !traversal.body.toLowerCase(Locale.ROOT).contains("[fonts]"));
    }

    /** A world that is not loaded is a status code the page can act on, not a blank map. */
    private static void statusCodesForAFailingSource() {
        WebMapServer server = null;
        try {
            server = WebMapServer.start(
                    RoadMapSource.unavailable("no world is loaded in the game client"), freePort());
            Result result = get(server.url() + "api/roads");
            expect("a request with nothing to read is a 503, which is retryable, and not a 200 with "
                            + "an empty map (" + result.status + ")",
                    result.status == 503);
            expect("and it carries the reason in the documented error shape",
                    JsonParser.parseString(result.body).getAsJsonObject()
                            .get("error").getAsString().contains("no world"));
            expect("while the page itself still loads, so the player can read that reason",
                    get(server.url()).status == 200);
        } catch (IOException failed) {
            expect("a server could be started for the failing source (" + failed.getMessage() + ")",
                    false);
        } finally {
            if (server != null) {
                server.stop();
            }
        }
    }

    /**
     * A handler that is waiting on the game must not stop the page's other requests being answered.
     *
     * <p>This is what {@code setExecutor} is for: {@code HttpServer}'s default executor runs handlers
     * on its single dispatcher thread, so a read that waits for a client tick -- up to five seconds --
     * would hold every other request, including the ones that could have been answered at once. Two
     * requests that each take 400 ms complete in about 400 ms when they run in parallel and in 800 ms
     * when they do not; the bound is between the two.
     */
    private static void handlersRunInParallel() {
        WebMapServer server = null;
        try {
            RoadMapSource slow = () -> {
                try {
                    Thread.sleep(400);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return fixtureForHttp();
            };
            server = WebMapServer.start(slow, freePort());
            // Captured by the request threads below, which is why the bound server is its own final
            // name: the field above is reassigned and cannot be closed over.
            WebMapServer bound = server;
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(2);
            List<Integer> statuses = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                Thread thread = new Thread(() -> {
                    try {
                        start.await();
                        Result result = get(bound.url() + "api/roads");
                        synchronized (statuses) {
                            statuses.add(result.status);
                        }
                    } catch (Throwable ignored) {
                        // Counted as a missing status below.
                    } finally {
                        done.countDown();
                    }
                }, "webmap-check-request-" + i);
                thread.setDaemon(true);
                thread.start();
            }
            long began = System.nanoTime();
            start.countDown();
            boolean finished;
            try {
                finished = done.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                finished = false;
            }
            long millis = (System.nanoTime() - began) / 1_000_000;
            expect("two handlers that wait run at once rather than one behind the other ("
                            + millis + " ms for two 400 ms reads)",
                    finished && statuses.size() == 2 && statuses.get(0) == 200
                            && statuses.get(1) == 200 && millis < 700);
        } catch (IOException failed) {
            expect("a server could be started for the parallel check (" + failed.getMessage() + ")",
                    false);
        } finally {
            if (server != null) {
                server.stop();
            }
        }
    }

    /** The fixture payload, reached through a method so the lambda above stays short. */
    private static RoadMapSnapshot fixtureForHttp() {
        return WebMapCheck.fixtureSnapshot();
    }

    /** Stopping has to release the socket, or the next session's server gets a port nobody is using. */
    private static void afterStop(WebMapServer server, int port) {
        expect("the server reports itself stopped", !server.running());
        Result gone = get(server.url() + "api/roads");
        expect("and nothing answers at the old address any more (status " + gone.status + ")",
                gone.status != 200);

        WebMapServer restarted = null;
        try {
            restarted = WebMapServer.start(WebMapCheck::fixtureSnapshot, port);
            expect("and the same port can be bound again, so the port was really released",
                    restarted.port() == port);
        } catch (IOException stillHeld) {
            expect("and the same port can be bound again (" + stillHeld.getMessage() + ")", false);
        } finally {
            if (restarted != null) {
                restarted.stop();
            }
        }
    }

    // ------------------------------------------------------------------ plumbing

    /** A port that was free a moment ago; the server's own scan covers the race. */
    private static int freePort() {
        try (ServerSocket probe = new ServerSocket(0)) {
            return probe.getLocalPort();
        } catch (IOException failed) {
            return 45_000 + (int) (System.nanoTime() % 5_000);
        }
    }

    private record Result(int status, String contentType, String body, String allow,
                          boolean noStore, boolean nosniff) {
    }

    private static Result get(String url) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();
        return send(request);
    }

    private static Result post(String url) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return send(request);
    }

    private static Result send(HttpRequest request) {
        try {
            HttpResponse<String> response =
                    CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Result(response.statusCode(),
                    response.headers().firstValue("Content-Type").orElse(""),
                    response.body(),
                    response.headers().firstValue("Allow").orElse(""),
                    response.headers().firstValue("Cache-Control")
                            .map(value -> value.contains("no-store")).orElse(false),
                    response.headers().firstValue("X-Content-Type-Options")
                            .map(value -> value.contains("nosniff")).orElse(false));
        } catch (IOException | InterruptedException failed) {
            if (failed instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            // Reported as a status no HTTP server sends, so a check can assert the failure rather
            // than the harness dying with an exception.
            return new Result(-1, "", "", "", false, false);
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
