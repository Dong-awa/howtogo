package bili.dongsz.howtogo.mtr;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The framing of the two custom payloads this mod sends, in plain bytes.
 *
 * <h2>Why the payload carries one {@code byte[]}</h2>
 * The payload classes themselves are the part of this that has to be written once per loader and, in
 * places, once per Minecraft version: what a payload type is called, how its codec is declared and
 * which buffer it reads from have all moved. A payload whose only field is a {@code byte[]} makes
 * every one of those differences a one-line codec, and puts the actual protocol -- kinds, sessions,
 * chunk indexes and the reading itself -- in this file, where it is the same code everywhere and can
 * be tested with no game running.
 *
 * <p>A frame is:
 * <pre>
 * int  protocol   -- this file's version, so a future change is refused rather than misread
 * int  kind       -- {@link #HELLO} or {@link #SNAPSHOT}
 * int  session    -- the server's id for one snapshot; a different one abandons what was assembled
 * long hash       -- the snapshot's content hash, for the client to keep and compare
 * int  index      -- this part's number, from zero
 * int  total      -- how many parts the session has
 * int  length     -- the bytes of this part
 * byte[length]    -- the part
 * </pre>
 *
 * <h2>Why it is chunked at all</h2>
 * A whole railway is every rail's geometry, and a single custom payload that size is past what a
 * packet may carry -- and even where it fitted it would arrive as one stall. The reading is therefore
 * split into parts of {@link #MAX_PART_BYTES}, which the receiving side reassembles by session.
 */
public final class MtrWire {

    /** The framing version. A frame with any other number is dropped. */
    public static final int PROTOCOL = 1;

    /** "I am here and I understand the snapshot payloads; send them." Sent once per world join. */
    public static final int HELLO = 1;

    /** One part of one snapshot. */
    public static final int SNAPSHOT = 2;

    /**
     * The most reading-bytes one frame carries.
     *
     * <p>30000 keeps a frame comfortably under Minecraft's own 1 MiB packet limit and under Fabric's
     * default payload ceiling even when the header and a hostile hash are added, while making a
     * megabyte-sized railway thirty-odd packets rather than hundreds.
     */
    public static final int MAX_PART_BYTES = 30_000;

    /** The most parts one session may have; a reading bigger than this is not sent at all. */
    public static final int MAX_PARTS = 4096;

    private MtrWire() {
    }

    /** One decoded frame. */
    public record Frame(int kind, int session, long hash, int index, int total, byte[] data) {

        public Frame {
            data = data == null ? new byte[0] : data;
        }

        public boolean isHello() {
            return kind == HELLO;
        }

        public boolean isSnapshot() {
            return kind == SNAPSHOT;
        }

        /** Whether the frame itself says it is a complete one-part session. */
        public boolean isWhole() {
            return total == 1;
        }
    }

    /** The hello frame: the smallest thing this mod can send. */
    public static byte[] hello() {
        try {
            return encode(new Frame(HELLO, 0, 0L, 0, 1, new byte[0]));
        } catch (IOException impossible) {
            // A frame built in memory cannot fail; the signature exists for the snapshot path.
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * Splits one encoded reading into frames, ready to be sent.
     *
     * @return the frames, or an empty list when the reading is too big for {@link #MAX_PARTS}
     */
    public static List<byte[]> split(int session, long hash, byte[] encoded) {
        int parts = Math.max(1, (encoded.length + MAX_PART_BYTES - 1) / MAX_PART_BYTES);
        if (parts > MAX_PARTS) {
            return List.of();
        }
        List<byte[]> frames = new ArrayList<>(parts);
        try {
            for (int index = 0; index < parts; index++) {
                int from = index * MAX_PART_BYTES;
                int to = Math.min(encoded.length, from + MAX_PART_BYTES);
                byte[] part = new byte[to - from];
                System.arraycopy(encoded, from, part, 0, part.length);
                frames.add(encode(new Frame(SNAPSHOT, session, hash, index, parts, part)));
            }
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
        return frames;
    }

    /** Encodes one frame. Never fails for a frame this class built; see {@link #hello()}. */
    public static byte[] encode(Frame frame) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(24 + frame.data().length);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(PROTOCOL);
            out.writeInt(frame.kind());
            out.writeInt(frame.session());
            out.writeLong(frame.hash());
            out.writeInt(frame.index());
            out.writeInt(frame.total());
            out.writeInt(frame.data().length);
            out.write(frame.data());
        }
        return bytes.toByteArray();
    }

    /**
     * Decodes one frame, or null when the bytes are not one this version understands.
     *
     * <p>Null rather than an exception: this runs inside a network handler, where the right answer to
     * unframed bytes is to ignore them, and a throw would turn a stray packet into a crash.
     */
    public static Frame decode(byte[] body) {
        if (body == null || body.length < 32) {
            return null;
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(body))) {
            if (in.readInt() != PROTOCOL) {
                return null;
            }
            int kind = in.readInt();
            int session = in.readInt();
            long hash = in.readLong();
            int index = in.readInt();
            int total = in.readInt();
            int length = in.readInt();
            if (kind != HELLO && kind != SNAPSHOT) {
                return null;
            }
            if (total < 1 || total > MAX_PARTS || index < 0 || index >= total) {
                return null;
            }
            if (length < 0 || length > MAX_PART_BYTES || length > in.available()) {
                return null;
            }
            byte[] data = new byte[length];
            in.readFully(data);
            if (in.available() != 0) {
                return null;
            }
            return new Frame(kind, session, hash, index, total, data);
        } catch (IOException | RuntimeException malformed) {
            return null;
        }
    }
}
