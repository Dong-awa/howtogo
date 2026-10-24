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
 *
 * <h2>Why this exists rather than the records the client already has</h2>
 * The client reads MTR into {@code MtrClientData.Snapshot}, and that class is client-side: it reaches
 * for MTR's client data, the local simulation and the overlay, and it is compiled against Minecraft's
 * client half. A dedicated server cannot load it, so the server cannot build the very records it would
 * have to send. This file is the same reading in a shape both sides can hold -- records of ids, names
 * and numbers, plus the two arrays a rail's geometry is -- and the wire format of it.
 *
 * <p>The conversion is deliberately one-for-one with {@code MtrClientData}'s records: no thinking
 * happens here, and a field this file does not carry is a field the client was not going to be sent
 * anyway.
 *
 * <h2>The wire format</h2>
 * A version int, then the dimensions, each with its own stations, platforms, lines and rails, written
 * with {@link DataOutputStream} and then deflated. Plain data streams rather than a
 * {@code FriendlyByteBuf} on purpose: the buffer classes and their method names are exactly what
 * changes between the Minecraft versions this mod supports, while {@code DataOutputStream} has not
 * changed since Java 1.0. The payload's own codec therefore carries one {@code byte[]} and nothing
 * else.
 *
 * <p>Decoding is bounded: the counts and lengths are checked against limits <em>before</em> anything
 * is allocated, because a malformed or hostile packet must not be able to ask this side for an
 * arbitrarily large array. The deflated body is bounded the same way.
 */
public final class MtrSnapshotData {

    /** Bumped when the shape below changes; a reader refuses a version it does not know. */
    public static final int VERSION = 1;

    private static final int MAX_DIMENSIONS = 64;
    private static final int MAX_ELEMENTS = 1 << 20;
    private static final int MAX_TEXT = 8192;
    private static final int MAX_POINTS = 1 << 21;
    /** Upper bound on the inflated body, so a deflated bomb cannot ask for more than this. */
    private static final int MAX_INFLATED_BYTES = 96 << 20;

    private MtrSnapshotData() {
    }

    /** A station area, with the extent MTR gave it. Mirrors {@code MtrClientData.Station}. */
    public record Station(long id, String name, String mode, int color, int centerX, int centerY,
                          int centerZ, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    }

    /** One platform: where a vehicle actually stops, and which station area it belongs to. */
    public record Platform(long id, long stationId, String name, String mode, int x, int y, int z) {
    }

    /** One stop of a line: which platform, which station, and what it is called. */
    public record Stop(long platformId, long stationId, String stationName, String destination) {
    }

    /** A line, with MTR's own ids for the rails it runs along when the reading states them. */
    public record Line(long id, String name, String mode, int color, List<Stop> stops,
                       List<String> rails) {

        public Line {
            stops = stops == null ? List.of() : List.copyOf(stops);
            rails = rails == null ? List.of() : List.copyOf(rails);
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

    /**
     * The reading as the bytes that go on the wire, deflated.
     *
     * @throws IOException only when the in-memory streams fail, which they do not in practice; the
     *                     signature is kept so a caller can log rather than crash on a broken value
     */
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
     *
     * <p>{@link IOException} rather than a null return: the caller is a network handler that has to
     * log what arrived, and "malformed" and "empty" are different answers.
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
                    lines.add(new Line(lineId, name, mode, color, stops, rails));
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

    /** Keeps a name inside what a {@code writeUTF} can carry, in bytes rather than characters. */
    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() > MAX_TEXT) {
            text = text.substring(0, MAX_TEXT);
        }
        // The length of a modified UTF-8 string is at most 3 bytes per char, and writeUTF refuses
        // anything over 65535 bytes; the cap above keeps every char inside that by a wide margin.
        return text;
    }
}
