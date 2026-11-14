package bili.dongsz.howtogo.mtr;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * The shape of an MTR reading, with no Minecraft type in it.
 * MtrClientData Snapshot is client-side, so a dedicated server could not build the records it must send. This is the same reading in a shape both sides hold, plus the wire format; the conversion is one-for-one and no thinking happens here.
 * Wire format: a version int, then the dimensions with their stations, platforms, lines and rails, written with DataOutputStream and deflated -- the buffer classes change between Minecraft versions, DataOutputStream has not. Decoding is bounded: counts and lengths are checked against limits before anything is allocated.
 */
public final class MtrSnapshotData {

    /**
     * Bumped when the shape changes; a reader refuses a version it does not know.
     * 2 added a route's circular state to every line. Both sides ship in the same jar, so an older peer is refused rather than misread.
     */
    public static final int VERSION = 2;

    private static final int MAX_DIMENSIONS = 64;
    private static final int MAX_ELEMENTS = 1 << 20;
    private static final int MAX_TEXT = 8192;
    private static final int MAX_POINTS = 1 << 21;
    /** Upper bound on the inflated body, so a deflated bomb cannot ask for more than this. */
    private static final int MAX_INFLATED_BYTES = 96 << 20;

    private MtrSnapshotData() {
    }

    /** A station area, with the extent MTR gave it. Mirrors MtrClientData Station. */
    public record Station(long id, String name, String mode, int color, int centerX, int centerY,
                          int centerZ, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    }

    /** One platform: where a vehicle actually stops, and which station area it belongs to. */
    public record Platform(long id, long stationId, String name, String mode, int x, int y, int z) {
    }

    /** One stop of a line: which platform, which station, and what it is called. */
    public record Stop(long platformId, long stationId, String stationName, String destination) {
    }

    /**
     * A line, with MTR's own ids for the rails it runs along when the reading states them.
     * circular is MTR's raw fact, not this mod's direction: MtrTransit turns it into a LineDirection. False when the reading cannot say, which cannot invent a restriction.
     */
    public record Line(long id, String name, String mode, int color, List<Stop> stops,
                       List<String> rails, boolean circular) {

        public Line {
            stops = stops == null ? List.of() : List.copyOf(stops);
            rails = rails == null ? List.of() : List.copyOf(rails);
        }

        /** The shape before a reading carried a route's circular state: a line that is not a loop. */
        public Line(long id, String name, String mode, int color, List<Stop> stops,
                    List<String> rails) {
            this(id, name, mode, color, stops, rails, false);
        }
    }

    /** One rail, as a flat polyline, with the height it sits at. Mirrors the client's track record. */
    public record Track(String hexId, String mode, int y, double[] xs, double[] zs) {

        public Track {
            xs = xs == null ? new double[0] : xs;
            zs = zs == null ? new double[0] : zs;
        }
    }

    /** Everything MTR's simulation holds for one dimension. */
    public record Dimension(String id, List<Station> stations, List<Platform> platforms,
                            List<Line> lines, List<Track> tracks) {

        public Dimension {
            stations = stations == null ? List.of() : List.copyOf(stations);
            platforms = platforms == null ? List.of() : List.copyOf(platforms);
            lines = lines == null ? List.of() : List.copyOf(lines);
            tracks = tracks == null ? List.of() : List.copyOf(tracks);
        }

        public boolean isEmpty() {
            return stations.isEmpty() && platforms.isEmpty() && lines.isEmpty() && tracks.isEmpty();
        }
    }

    /** One server's entire railway, dimension by dimension. */
    public record Snapshot(List<Dimension> dimensions) {

        public static final Snapshot EMPTY = new Snapshot(List.of());

        public Snapshot {
            dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
        }

        public boolean isEmpty() {
            for (Dimension dimension : dimensions) {
                if (!dimension.isEmpty()) {
                    return false;
                }
            }
            return true;
        }
    }

    /** The reading as the wire bytes, deflated. IOException only if the in-memory streams fail. */
    public static byte[] encode(Snapshot snapshot) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream(1 << 16);
        try (DataOutputStream out = new DataOutputStream(raw)) {
            out.writeInt(VERSION);
            out.writeInt(snapshot.dimensions().size());
            for (Dimension dimension : snapshot.dimensions()) {
                writeText(out, dimension.id());
                out.writeInt(dimension.stations().size());
                for (Station station : dimension.stations()) {
                    out.writeLong(station.id());
                    writeNullable(out, station.name());
                    writeNullable(out, station.mode());
                    out.writeInt(station.color());
                    out.writeInt(station.centerX());
                    out.writeInt(station.centerY());
                    out.writeInt(station.centerZ());
                    out.writeInt(station.minX());
                    out.writeInt(station.minY());
                    out.writeInt(station.minZ());
                    out.writeInt(station.maxX());
                    out.writeInt(station.maxY());
                    out.writeInt(station.maxZ());
                }
                out.writeInt(dimension.platforms().size());
                for (Platform platform : dimension.platforms()) {
                    out.writeLong(platform.id());
                    out.writeLong(platform.stationId());
                    writeNullable(out, platform.name());
                    writeNullable(out, platform.mode());
                    out.writeInt(platform.x());
                    out.writeInt(platform.y());
                    out.writeInt(platform.z());
                }
                out.writeInt(dimension.lines().size());
                for (Line line : dimension.lines()) {
                    out.writeLong(line.id());
                    writeNullable(out, line.name());
                    writeNullable(out, line.mode());
                    out.writeInt(line.color());
                    // MTR's own fact, not the direction it becomes: the client decides once, in MtrTransit.
                    out.writeBoolean(line.circular());
                    out.writeInt(line.stops().size());
                    for (Stop stop : line.stops()) {
                        out.writeLong(stop.platformId());
                        out.writeLong(stop.stationId());
                        writeNullable(out, stop.stationName());
                        writeNullable(out, stop.destination());
                    }
                    out.writeInt(line.rails().size());
                    for (String rail : line.rails()) {
                        writeText(out, rail);
                    }
                }
                out.writeInt(dimension.tracks().size());
                for (Track track : dimension.tracks()) {
                    writeNullable(out, track.hexId());
                    writeNullable(out, track.mode());
                    out.writeInt(track.y());
                    int points = Math.min(track.xs().length, track.zs().length);
                    out.writeInt(points);
                    for (int i = 0; i < points; i++) {
                        out.writeDouble(track.xs()[i]);
                        out.writeDouble(track.zs()[i]);
                    }
                }
            }
        }
        ByteArrayOutputStream deflated = new ByteArrayOutputStream(raw.size() / 2 + 16);
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, false);
        try (DeflaterOutputStream out = new DeflaterOutputStream(deflated, deflater)) {
            raw.writeTo(out);
        } finally {
            deflater.end();
        }
        return deflated.toByteArray();
    }

    /**
     * The reading back, or an exception when the bytes are not a reading this version understands.
     * IOException rather than a null return: the caller is a network handler that has to log what arrived, and malformed and empty are different answers.
     */
    public static Snapshot decode(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0) {
            throw new IOException("empty snapshot body");
        }
        ByteArrayOutputStream inflated = new ByteArrayOutputStream(Math.min(MAX_INFLATED_BYTES,
                Math.max(1024, bytes.length * 4)));
        try (InflaterInputStream in = new InflaterInputStream(new ByteArrayInputStream(bytes))) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                inflated.write(buffer, 0, read);
                if (inflated.size() > MAX_INFLATED_BYTES) {
                    throw new IOException("snapshot inflates past " + MAX_INFLATED_BYTES + " bytes");
                }
            }
        }
        try (DataInputStream in = new DataInputStream(
                new ByteArrayInputStream(inflated.toByteArray()))) {
            int version = in.readInt();
            if (version != VERSION) {
                throw new IOException("snapshot version " + version + ", wanted " + VERSION);
            }
            int dimensions = count(in, MAX_DIMENSIONS);
            List<Dimension> read = new ArrayList<>(dimensions);
            for (int d = 0; d < dimensions; d++) {
                String id = readText(in);
                List<Station> stations = new ArrayList<>();
                int stationCount = count(in, MAX_ELEMENTS);
                for (int i = 0; i < stationCount; i++) {
                    stations.add(new Station(in.readLong(), readNullable(in), readNullable(in),
                            in.readInt(), in.readInt(), in.readInt(), in.readInt(),
                            in.readInt(), in.readInt(), in.readInt(),
                            in.readInt(), in.readInt(), in.readInt()));
                }
                List<Platform> platforms = new ArrayList<>();
                int platformCount = count(in, MAX_ELEMENTS);
                for (int i = 0; i < platformCount; i++) {
                    platforms.add(new Platform(in.readLong(), in.readLong(), readNullable(in),
                            readNullable(in), in.readInt(), in.readInt(), in.readInt()));
                }
                List<Line> lines = new ArrayList<>();
                int lineCount = count(in, MAX_ELEMENTS);
                for (int i = 0; i < lineCount; i++) {
                    long lineId = in.readLong();
                    String name = readNullable(in);
                    String mode = readNullable(in);
                    int color = in.readInt();
                    boolean circular = in.readBoolean();
                    int stopCount = count(in, MAX_ELEMENTS);
                    List<Stop> stops = new ArrayList<>(stopCount);
                    for (int s = 0; s < stopCount; s++) {
                        stops.add(new Stop(in.readLong(), in.readLong(), readNullable(in),
                                readNullable(in)));
                    }
                    int railCount = count(in, MAX_ELEMENTS);
                    List<String> rails = new ArrayList<>(railCount);
                    for (int r = 0; r < railCount; r++) {
                        rails.add(readText(in));
                    }
                    lines.add(new Line(lineId, name, mode, color, stops, rails, circular));
                }
                List<Track> tracks = new ArrayList<>();
                int trackCount = count(in, MAX_ELEMENTS);
                for (int i = 0; i < trackCount; i++) {
                    String hexId = readNullable(in);
                    String mode = readNullable(in);
                    int y = in.readInt();
                    int points = count(in, MAX_POINTS);
                    double[] xs = new double[points];
                    double[] zs = new double[points];
                    for (int p = 0; p < points; p++) {
                        xs[p] = in.readDouble();
                        zs[p] = in.readDouble();
                    }
                    tracks.add(new Track(hexId, mode, y, xs, zs));
                }
                read.add(new Dimension(id, stations, platforms, lines, tracks));
            }
            if (in.available() != 0) {
                throw new IOException("snapshot body has " + in.available() + " trailing byte(s)");
            }
            return new Snapshot(read);
        }
    }

    /** A content hash of an encoded reading, which is what the server compares to spot a change. */
    public static long hashOf(byte[] encoded) {
        CRC32 crc = new CRC32();
        crc.update(encoded, 0, encoded.length);
        return crc.getValue();
    }

    private static int count(DataInputStream in, int max) throws IOException {
        int value = in.readInt();
        if (value < 0 || value > max) {
            throw new IOException("snapshot count " + value + " outside 0.." + max);
        }
        return value;
    }

    private static void writeText(DataOutputStream out, String text) throws IOException {
        out.writeUTF(truncate(text == null ? "" : text));
    }

    private static void writeNullable(DataOutputStream out, String text) throws IOException {
        out.writeBoolean(text != null);
        if (text != null) {
            out.writeUTF(truncate(text));
        }
    }

    private static String readText(DataInputStream in) throws IOException {
        return in.readUTF();
    }

    private static String readNullable(DataInputStream in) throws IOException {
        return in.readBoolean() ? in.readUTF() : null;
    }

    /** Keeps a name inside what a writeUTF can carry, in bytes rather than characters. */
    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() > MAX_TEXT) {
            text = text.substring(0, MAX_TEXT);
        }
        // Modified UTF-8 is at most 3 bytes per char and writeUTF refuses anything over 65535 bytes; the cap above keeps every char inside that.
        return text;
    }
}
