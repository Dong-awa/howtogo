package bili.dongsz.howtogo.road;

import bili.dongsz.howtogo.HowToGo;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
            if (dto.nodes != null) {
                for (NodeDto n : dto.nodes) {
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
                    network.putSegment(toSegment(s));
                }
            }
            HowToGo.LOGGER.info("[HowToGo] loaded {} nodes / {} segments from {}",
                    network.nodeCount(), network.segmentCount(), file.getFileName());
        } catch (IOException | JsonSyntaxException e) {
            HowToGo.LOGGER.error("[HowToGo] could not read {}; starting empty", file, e);
        }
        return network;
    }

    public static boolean save(Path file, RoadNetwork network) {
        if (file == null) {
            return false;
        }
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
                s.oneWay = segment.oneWay();
                s.y = segment.y();
                s.name = segment.name();
                s.xs = new int[segment.vertexCount()];
                s.zs = new int[segment.vertexCount()];
                for (int i = 0; i < segment.vertexCount(); i++) {
                    s.xs[i] = segment.x(i);
                    s.zs[i] = segment.z(i);
                }
                dto.segments.add(s);
            }

            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(dto, writer);
            }
            return true;
        } catch (IOException e) {
            HowToGo.LOGGER.error("[HowToGo] could not write {}", file, e);
            return false;
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
        segment.setOneWay(s.oneWay);
        segment.setName(s.name);
        return segment;
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
        boolean oneWay;
        int y;
        String name;
        int[] xs;
        int[] zs;
    }
}
