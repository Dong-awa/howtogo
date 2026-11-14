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
 * The client's half of the MTR sync: ask the server for its reading, assemble the parts, offer the result.
 * A fourth reading for MtrClientData, after MTR's own window, the local simulation and the Map Overlay snapshot, and the only one needing nothing but this mod on the server; MtrClientData prefers it over the overlay's copy.
 * Handshake: the client sends MtrWire.HELLO while the sync is enabled and repeats it once a second, because a server running this mod must not send to a client without it. Assembly: parts arrive by session, parts of other sessions are ignored, and malformed frames lose nothing else.
 */
public final class MtrClientSync {

    /** Client heartbeat interval, in client ticks. One second. */
    private static final int HELLO_TICKS = 20;

    /** Cap on one assembled reading; the server caps a session at 2048 x 30 kB. */
    private static final long MAX_ASSEMBLED_BYTES = 64L << 20;

    private static volatile MtrSnapshotData.Snapshot latest = MtrSnapshotData.Snapshot.EMPTY;

    /** Session being assembled, or -1 when none is; parts of any other session are ignored. */
    private static int session = -1;
    private static int total;
    private static final Map<Integer, byte[]> parts = new HashMap<>();

    /** Conversion cached for the dimension it was made for, so the per-tick ask is not a per-tick walk. */
    private static MtrClientData.Snapshot converted = MtrClientData.Snapshot.EMPTY;
    private static String convertedFor;
    private static MtrSnapshotData.Snapshot convertedFrom;

    /** The world the reading belongs to: a different level voids the previous server's reading. */
    private static Object lastLevel;

    private static int ticks;
    private static boolean announced;

    private MtrClientSync() {
    }

    /** The reading the server sent, in MTR's own records; empty until the first complete one. */
    public static MtrSnapshotData.Snapshot raw() {
        return RoadConfig.mtrServerSync() ? latest : MtrSnapshotData.Snapshot.EMPTY;
    }

    public static boolean received() {
        return RoadConfig.mtrServerSync() && !latest.isEmpty();
    }

    /**
     * Drives the handshake from the client tick.
     * Also the only place a world change is noticed: a reading belongs to the server it came from, and a reference comparison covers single player, server switch and disconnect alike, so no loader join event is needed.
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
        if (!RoadConfig.mtrServerSync()) {
            reset();
            return;
        }
        if (minecraft.player == null) {
            return;
        }
        // Heartbeat continues after a reading arrives: it keeps the server queue alive and lets a queue expire once the client turns the sync off.
        if (++ticks % HELLO_TICKS == 0) {
            MtrSync.sendHello();
        }
    }

    /**
     * One part of the server's reading, already on the client thread; the payload handler has moved off the network thread.
     */
    public static void onPart(byte[] body) {
        if (!RoadConfig.mtrServerSync()) {
            reset();
            return;
        }
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
        ticks = 0;
        announced = false;
    }

    /**
     * The server's reading as this mod's own records, for the dimension the player is standing in. Empty when nothing arrived, when the held dimension is not the player's, and when the player is not in a world. MTR spells a dimension minecraft/overworld, so compare in that spelling rather than the registry's.
     */
    public static MtrClientData.Snapshot snapshot() {
        if (!RoadConfig.mtrServerSync()) {
            return MtrClientData.Snapshot.EMPTY;
        }
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
        // An unnamed sole dimension is the only usable fallback; a named one that does not match is a different dimension and must not be guessed at.
        return count == 1 && only != null && only.id() == null ? only : null;
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
                    stops, line.rails(), line.circular()));
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
            // A reading this large is a broken or hostile session, not a railway: refuse it instead of letting the frame count decide how much memory the client spends.
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
