package bili.dongsz.howtogo.road;

import bili.dongsz.howtogo.HowToGo;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes a {@link RoadNetwork} as JSON.
 *
 * <p>The on-disk shape is deliberately plain and versioned: ids are preserved so topology survives
 * a round trip, and vertices are stored as flat {@code xs}/{@code zs} arrays because that is
 * already the in-memory layout.
 */
public final class RoadStorage {

    public static final int FORMAT_VERSION = 1;

    /**
     * Suffix of the file a save is written to before it takes the real one's place.
     *
     * <p>Kept beside the target rather than in a temporary directory, because the last step of the
     * save is a rename onto the target and a rename cannot cross a filesystem.
     */
    private static final String PARTIAL_SUFFIX = ".part";

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private RoadStorage() {
    }

    public static RoadNetwork load(Path file) {
        RoadNetwork network = new RoadNetwork();
        if (file == null || !Files.isRegularFile(file)) {
            return network;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            NetworkDto dto = GSON.fromJson(reader, NetworkDto.class);
            if (dto == null) {
                return network;
            }
            if (dto.version > FORMAT_VERSION) {
                // Written by something newer than this build. Read it anyway -- the fields this
                // version knows are still the fields it wrote -- but say so, because the next save
                // will write only what this version understands.
                HowToGo.LOGGER.warn("[HowToGo] {} says version {} and this build writes {}; reading "
                                + "the fields it knows", file.getFileName(), dto.version,
                        FORMAT_VERSION);
            }
            int rejected = 0;
            int duplicates = 0;
            if (dto.nodes != null) {
                for (NodeDto n : dto.nodes) {
                    // A negative id is not an id: -1 is NO_NODE, the sentinel for "this endpoint has
                    // no node". A file carrying one used to be loaded as written, so the node was
                    // built, drawn on the map and saved again -- and every segment referring to it was
                    // skipped by the router, for ever: a road that looks perfectly good and can never
                    // be travelled.
                    if (n.id < 0) {
                        rejected++;
                        continue;
                    }
                    // Ids are the network's keys, so a repeated one used to overwrite: the first
                    // node, with its position and its name, disappeared without a word.
                    if (network.node(n.id) != null) {
                        duplicates++;
                        continue;
                    }
                    RoadNode node = new RoadNode(n.id, n.x, n.y, n.z, parseType(n.type), n.name);
                    // Absent in any file written before place kinds existed, and it reads back as
                    // PLACE -- so every place a player had already put down is still an ordinary place
                    // and no road vertex becomes one, because whether a node is a place at all is its
                    // type, not this field.
                    node.setPlaceKind(PlaceKind.byId(n.placeKind));
                    network.putNode(node);
                }
            }
            if (dto.segments != null) {
                for (SegmentDto s : dto.segments) {
                    if (s.id < 0) {
                        rejected++;
                        continue;
                    }
                    if (network.segment(s.id) != null) {
                        duplicates++;
                        continue;
                    }
                    network.putSegment(toSegment(s));
                }
            }
            if (rejected > 0 || duplicates > 0) {
                HowToGo.LOGGER.warn("[HowToGo] {} held {} entr(ies) with an unusable id and {} with a "
                                + "repeated one; those were dropped rather than allowed to overwrite "
                                + "what they collided with",
                        file.getFileName(), rejected, duplicates);
            }
            HowToGo.LOGGER.info("[HowToGo] loaded {} nodes / {} segments from {}",
                    network.nodeCount(), network.segmentCount(), file.getFileName());
        } catch (IOException | JsonSyntaxException e) {
            HowToGo.LOGGER.error("[HowToGo] could not read {}; starting empty", file, e);
            // Starting empty is the only thing that can be done with a file that does not parse, but
            // it must not also be the end of that data: the next autosave writes over it, and a save
            // interrupted before this version wrote atomically leaves exactly this -- a truncated
            // file whose roads are still in there, in part. Moving it aside keeps the salvagable
            // remainder and costs a name in the world's folder instead of the player's whole map.
            quarantine(file);
        }
        return network;
    }

    /** Renames an unreadable network aside so that starting empty does not destroy it. */
    private static void quarantine(Path file) {
        Path salvaged = file.resolveSibling(file.getFileName() + ".corrupt");
        try {
            Files.move(file, salvaged, StandardCopyOption.REPLACE_EXISTING);
            HowToGo.LOGGER.error("[HowToGo] kept the unreadable file as {} -- "
                    + "part of it may still be recoverable by hand", salvaged);
        } catch (IOException moveFailed) {
            HowToGo.LOGGER.error("[HowToGo] could not set {} aside either; it will be overwritten",
                    file, moveFailed);
        }
    }

    /**
     * Writes the network out, replacing the file in one step.
     *
     * <h2>Why this does not write the target directly</h2>
     * A save is not one operation but a stream of them, and a stream can be cut in half: the game
     * can be killed, the machine can lose power, the disk can fill. Writing straight onto the target
     * means the moment that happens the only copy of a player's whole road network is a truncated
     * JSON file, and {@link #load} reports it as unreadable and starts empty -- so the failure mode of
     * an interrupted save is losing the map, not losing the edit that was in flight.
     *
     * <p>So the bytes go to a neighbouring {@code .part} file first and the target is replaced by a
     * rename, which a filesystem performs as one step: either the old file is still there in full or
     * the new one is. The half-written file is never a name anything reads.
     *
     * <p>The move is attempted as an atomic one and falls back to a plain replace, because atomic
     * moves are a filesystem capability rather than a guarantee -- a network share or an unusual
     * filesystem can refuse. The fallback is the old behaviour on those, which is the honest answer:
     * better a save that can be interrupted than a save that does not happen.
     */
    public static boolean save(Path file, RoadNetwork network) {
        if (file == null) {
            return false;
        }
        Path partial = file.resolveSibling(file.getFileName() + PARTIAL_SUFFIX);
        try {
            Files.createDirectories(file.getParent());
            NetworkDto dto = new NetworkDto();
            dto.version = FORMAT_VERSION;
            dto.nodes = new ArrayList<>(network.nodeCount());
            dto.segments = new ArrayList<>(network.segmentCount());

            for (RoadNode node : network.nodes()) {
                NodeDto n = new NodeDto();
                n.id = node.id();
                n.x = node.x();
                n.y = node.y();
                n.z = node.z();
                n.type = node.type().name();
                n.name = node.name();
                n.placeKind = node.placeKind().name();
                dto.nodes.add(n);
            }
            for (RoadSegment segment : network.segments()) {
                SegmentDto s = new SegmentDto();
                s.id = segment.id();
                s.roadClass = segment.roadClass().name();
                s.from = segment.fromNode();
                s.to = segment.toNode();
                s.direction = segment.direction().name();
                s.y = segment.y();
                s.layer = segment.layer();
                s.name = segment.name();
                s.xs = new int[segment.vertexCount()];
                s.zs = new int[segment.vertexCount()];
                for (int i = 0; i < segment.vertexCount(); i++) {
                    s.xs[i] = segment.x(i);
                    s.zs[i] = segment.z(i);
                }
                dto.segments.add(s);
            }

            try (Writer writer = Files.newBufferedWriter(partial, StandardCharsets.UTF_8)) {
                GSON.toJson(dto, writer);
            }
            replace(partial, file);
            return true;
        } catch (IOException e) {
            HowToGo.LOGGER.error("[HowToGo] could not write {}", file, e);
            // The half-written file is left behind otherwise, and the next save would overwrite it
            // anyway -- but a stray .part beside the network is confusing to find, so it goes now.
            discard(partial);
            return false;
        }
    }

    /** Gives {@code partial} the target's name, atomically where the filesystem allows it. */
    private static void replace(Path partial, Path file) throws IOException {
        try {
            Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException notAtomic) {
            HowToGo.LOGGER.warn(
                    "[HowToGo] this filesystem cannot replace {} in one step; falling back to a plain overwrite",
                    file.getFileName());
            Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Removes a partial file if it is there. Never throws: this runs while reporting a failure. */
    private static void discard(Path partial) {
        try {
            Files.deleteIfExists(partial);
        } catch (IOException ignored) {
            // Nothing useful can be done about it here, and the error that mattered is already logged.
        }
    }

    private static RoadNode.Type parseType(String raw) {
        if (raw == null) {
            return RoadNode.Type.ENDPOINT;
        }
        try {
            return RoadNode.Type.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return RoadNode.Type.ENDPOINT;
        }
    }

    private static RoadClass parseClass(String raw) {
        if (raw == null) {
            return RoadClass.ROAD;
        }
        try {
            return RoadClass.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return RoadClass.ROAD;
        }
    }

    private static RoadSegment toSegment(SegmentDto s) {
        int[] xs = s.xs != null ? s.xs : new int[0];
        int[] zs = s.zs != null ? s.zs : new int[0];
        int count = Math.min(xs.length, zs.length);

        RoadSegment segment = new RoadSegment(s.id, parseClass(s.roadClass), s.y, Math.max(2, count));
        for (int i = 0; i < count; i++) {
            segment.addVertex(xs[i], zs[i]);
        }
        segment.setFromNode(s.from);
        segment.setToNode(s.to);
        segment.setDirection(directionOf(s));
        // Clamped by the segment itself, so a file somebody edited by hand costs them the storey
        // rather than the road.
        segment.setLayer(s.layer);
        segment.setName(s.name);
        return segment;
    }

    /**
     * A segment's direction, from whichever of the two fields the file has.
     *
     * <p>{@code direction} is what this version writes. {@code oneWay} is the boolean an earlier build of
     * this feature wrote, and a file from it means the one direction that boolean could express, which is
     * {@link RoadDirection#FORWARD}. Reading the old field rather than ignoring it is what keeps a save
     * from that build meaning the same thing after the upgrade; a file with neither field is a road
     * nobody has said anything about, which is a two-way road.
     */
    private static RoadDirection directionOf(SegmentDto s) {
        if (s.direction != null) {
            return RoadDirection.byId(s.direction);
        }
        return s.oneWay ? RoadDirection.FORWARD : RoadDirection.TWO_WAY;
    }

    // ------------------------------------------------------------------- DTOs

    private static final class NetworkDto {
        int version;
        List<NodeDto> nodes;
        List<SegmentDto> segments;
    }

    private static final class NodeDto {
        int id;
        int x;
        int y;
        int z;
        String type;
        String name;
        /**
         * Added after the first release; written always, read tolerantly.
         *
         * <p>Left absent in every file written before it existed, and {@code Gson} leaves the field
         * null rather than complaining, which is what makes the addition one that older saves survive.
         */
        String placeKind;
    }

    private static final class SegmentDto {
        int id;
        String roadClass;
        int from;
        int to;
        /**
         * The one-way state, as {@link RoadDirection}'s constant name.
         *
         * <p>Written always, read tolerantly: absent in a file written before directions existed, and
         * then {@link #oneWay} decides.
         */
        String direction;
        /**
         * The boolean this field replaced, still read and no longer written.
         *
         * <p>A file that has it was written by the version that could only say "one-way forwards", and
         * writing it again would claim the same thing about a road that now runs backwards.
         */
        boolean oneWay;
        int y;
        /**
         * Which storey the road is on: 0 the surface, positive above it, negative below.
         *
         * <p>Written alongside the rest and absent in every file written before storeys existed, where
         * {@code Gson} leaves it at zero -- which is exactly the right reading of an older save: every
         * road in it was drawn on the surface, because there was nothing else to draw one on.
         */
        int layer;
        String name;
        int[] xs;
        int[] zs;
    }
}
