package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.RoadConfig;
import bili.dongsz.howtogo.mtr.MtrSnapshotData;
import bili.dongsz.howtogo.mtr.MtrSync;
import bili.dongsz.howtogo.mtr.MtrWire;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The client's half of the MTR sync: ask the server for its reading, assemble what arrives, offer it.
 *
 * <h2>What this adds to the readings the client already has</h2>
 * {@code MtrClientData} already folds three readings together: MTR's own window, the simulation this
 * process runs when the player hosts the world (see {@code MtrWholeMap}), and MTR Map Overlay's
 * snapshot when that mod is installed on both sides. This is the fourth, and the one that needs
 * nothing but this mod on the server: the server reads the whole railway it is simulating and sends
 * it here. {@code MtrClientData} prefers it over the overlay's copy, and only falls back to the
 * overlay when no own reading exists -- see the note on the merge there.
 *
 * <h2>The handshake, and why there is one</h2>
 * A server that has this mod must not send anything to a client that does not: the payload classes
 * would be unknown there. So the client says {@link MtrWire#HELLO} while it has no reading, and
 * repeats it once a second; a server sends to nobody else. That also covers the other order -- a
 * client that joins before the server has finished reading -- without either side needing to know
 * when the other is ready.
 *
 * <h2>Assembly</h2>
 * The reading arrives in parts, by session. A session is the server's number for one snapshot; parts
 * of a session the client is not assembling are ignored, and the part that completes the set is the
 * only thing that changes what is offered. Malformed frames lose themselves and nothing else.
 */
public final class MtrClientSync {

    /** How often the hello is repeated while nothing has arrived, in client ticks. One second. */
    private static final int HELLO_TICKS = 20;

    /** The most one assembled reading may inflate to; a whole railway is far under this. */
    private static final long MAX_ASSEMBLED_BYTES = 64L << 20;

    private static volatile MtrSnapshotData.Snapshot latest = MtrSnapshotData.Snapshot.EMPTY;

    /** The session being assembled, or -1 when nothing is: parts of any other session are ignored. */
    private static int session = -1;
    private static int total;
    private static final Map<Integer, byte[]> parts = new HashMap<>();

    /** The conversion for the dimension it was made for, so a per-tick ask is not a per-tick walk. */
    private static MtrClientData.Snapshot converted = MtrClientData.Snapshot.EMPTY;
    private static String convertedFor;
    private static MtrSnapshotData.Snapshot convertedFrom;

    /** The world the reading belongs to: a different level means the previous server's reading is void. */
    private static Object lastLevel;

    private static int ticks;
    private static boolean announced;

    private MtrClientSync() {
    }

    /** The reading the server sent, in MTR's own records; empty until the first complete one. */
    public static MtrSnapshotData.Snapshot raw() {
        return latest;
    }

    /** Whether anything has arrived that the merge can use. */
    public static boolean received() {
        return !latest.isEmpty();
    }

    /**
     * Drives the handshake, from the client tick.
     *
     * <p>Also the only place a change of world is noticed: a reading belongs to the server it came
     * from, so entering a different level drops it and starts asking again. This is why there is no
     * join event on either loader to keep in step -- a reference comparison covers single player,
     * a server switch and a disconnect alike.
     */
    public static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        Object level = minecraft.level;
        if (level != lastLevel) {
            lastLevel = level;
            session = -1;
            parts.clear();
            latest = MtrSnapshotData.Snapshot.EMPTY;
            converted = MtrClientData.Snapshot.EMPTY;
            convertedFor = null;
            convertedFrom = null;
            announced = false;
            ticks = 0;
        }
        if (!RoadConfig.mtrServerSync() || minecraft.player == null) {
            return;
        }
        if (latest.isEmpty() && ++ticks % HELLO_TICKS == 0) {
            MtrSync.sendHello();
        }
    }

    /**
     * One part of the server's reading arrived, already on the client thread.
     *
     * <p>Called by the payload handler, which is the only caller and which has already moved off the
     * network thread.
     */
    public static void onPart(byte[] body) {
        MtrWire.Frame frame = MtrWire.decode(body);
        if (frame == null || !frame.isSnapshot()) {
            return;
        }
        if (frame.session() != session) {
            if (session != -1 && !parts.isEmpty()) {
                // A newer session started before the old one finished: the old reading is obsolete.
                HowToGo.LOGGER.info("[HowToGo] MTR sync | session {} replaced {}",
                        frame.session(), session);
            }
            session = frame.session();
            total = frame.total();
            parts.clear();
        }
        parts.put(frame.index(), frame.data());
        if (parts.size() < total) {
            return;
        }
        byte[] encoded = join();
        if (encoded == null) {
            session = -1;
            parts.clear();
            return;
        }
        try {
            MtrSnapshotData.Snapshot reading = MtrSnapshotData.decode(encoded);
            latest = reading;
            converted = MtrClientData.Snapshot.EMPTY;
            convertedFrom = null;
            if (!announced) {
                announced = true;
                HowToGo.LOGGER.info("[HowToGo] MTR is synced from the server ({} dimension(s), {} kB)",
                        reading.dimensions().size(), encoded.length / 1024);
            }
        } catch (IOException | RuntimeException malformed) {
            HowToGo.LOGGER.warn("[HowToGo] could not read the MTR snapshot the server sent ({}); the "
                    + "windowed reading stands", malformed.toString());
        } finally {
            session = -1;
            parts.clear();
        }
    }

    /** Forgets what the server sent, which is what switching the sync off does. */
    public static void reset() {
        latest = MtrSnapshotData.Snapshot.EMPTY;
        session = -1;
        parts.clear();
        converted = MtrClientData.Snapshot.EMPTY;
        convertedFor = null;
        convertedFrom = null;
    }

    /**
     * The server's reading as this mod's own records, for the dimension the player is standing in.
     *
     * <p>Empty when no snapshot has arrived, when the dimension it holds is not the player's, and when
     * the player is not in a world. MTR's own simulator spells a dimension {@code minecraft/overworld},
     * so the player's dimension is compared in that spelling rather than in the registry's.
     */
    public static MtrClientData.Snapshot snapshot() {
        MtrSnapshotData.Snapshot reading = latest;
        if (reading.isEmpty()) {
            return MtrClientData.Snapshot.EMPTY;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null) {
            return MtrClientData.Snapshot.EMPTY;
        }
        String wanted = player.level().dimension().location().toString().replace(':', '/');
        if (reading == convertedFrom && wanted.equals(convertedFor)) {
            return converted;
        }
        MtrSnapshotData.Dimension dimension = dimensionFor(reading, wanted);
        MtrClientData.Snapshot built = dimension == null
                ? MtrClientData.Snapshot.EMPTY : convert(dimension);
        converted = built;
        convertedFor = wanted;
        convertedFrom = reading;
        return built;
    }

    private static MtrSnapshotData.Dimension dimensionFor(MtrSnapshotData.Snapshot reading,
                                                          String wanted) {
        MtrSnapshotData.Dimension only = null;
        int count = 0;
        for (MtrSnapshotData.Dimension dimension : reading.dimensions()) {
            count++;
            only = dimension;
            if (wanted.equals(dimension.id())) {
                return dimension;
            }
        }
        // No name to match -- a server whose simulator does not say which dimension it is -- is
        // usable when there is exactly one dimension to choose from, and ambiguous otherwise.
        return count == 1 ? only : null;
    }

    private static MtrClientData.Snapshot convert(MtrSnapshotData.Dimension dimension) {
        List<MtrClientData.Station> stations = new ArrayList<>(dimension.stations().size());
        for (MtrSnapshotData.Station station : dimension.stations()) {
            stations.add(new MtrClientData.Station(station.id(), station.name(), station.mode(),
                    station.color(), station.centerX(), station.centerY(), station.centerZ(),
                    station.minX(), station.minY(), station.minZ(), station.maxX(), station.maxY(),
                    station.maxZ()));
        }
        List<MtrClientData.Platform> platforms = new ArrayList<>(dimension.platforms().size());
        for (MtrSnapshotData.Platform platform : dimension.platforms()) {
            platforms.add(new MtrClientData.Platform(platform.id(), platform.stationId(),
                    platform.name(), platform.mode(), platform.x(), platform.y(), platform.z()));
        }
        List<MtrClientData.Line> lines = new ArrayList<>(dimension.lines().size());
        for (MtrSnapshotData.Line line : dimension.lines()) {
            List<MtrClientData.Stop> stops = new ArrayList<>(line.stops().size());
            for (MtrSnapshotData.Stop stop : line.stops()) {
                stops.add(new MtrClientData.Stop(stop.platformId(), stop.stationId(),
                        stop.stationName(), stop.destination()));
            }
            lines.add(new MtrClientData.Line(line.id(), line.name(), line.mode(), line.color(),
                    stops, line.rails()));
        }
        List<MtrClientData.Track> tracks = new ArrayList<>(dimension.tracks().size());
        for (MtrSnapshotData.Track track : dimension.tracks()) {
            tracks.add(new MtrClientData.Track(track.hexId(), track.mode(), track.y(),
                    track.xs(), track.zs()));
        }
        return new MtrClientData.Snapshot(List.copyOf(stations), List.copyOf(platforms),
                List.copyOf(lines), List.copyOf(tracks));
    }

    private static byte[] join() {
        long length = 0;
        for (int index = 0; index < total; index++) {
            byte[] part = parts.get(index);
            if (part == null) {
                return null;
            }
            length += part.length;
        }
        if (length > MAX_ASSEMBLED_BYTES) {
            // A reading bigger than this is a broken or hostile session, not a railway: refuse it
            // instead of letting the frame count decide how much memory the client spends.
            HowToGo.LOGGER.warn("[HowToGo] MTR snapshot refused: {} bytes assembled, over the {} limit",
                    length, MAX_ASSEMBLED_BYTES);
            return null;
        }
        byte[] encoded = new byte[(int) length];
        int offset = 0;
        for (int index = 0; index < total; index++) {
            byte[] part = parts.get(index);
            System.arraycopy(part, 0, encoded, offset, part.length);
            offset += part.length;
        }
        return encoded;
    }
}
