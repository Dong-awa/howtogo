package bili.dongsz.howtogo.mtr;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The framing of the two custom payloads, in plain bytes.
 * The payload classes differ per loader/version; a byte[]-only field keeps that to a one-line codec
 * and puts the protocol here, testable with no game running.
 * Frame: int protocol, kind, session, long hash, index, total, length, byte[length] data. A
 * protocol mismatch is refused; a new session abandons the assembly.
 * Chunked because a whole railway can exceed the packet limit; parts are MAX_PART_BYTES,
 * reassembled by session.
 */
public final class MtrWire {

    /** Framing version; frames with any other number are dropped. */
    public static final int PROTOCOL = 1;

    /** Hello: the client understands snapshots, send them. Sent once per world join. */
    public static final int HELLO = 1;

    /** One part of one snapshot. */
    public static final int SNAPSHOT = 2;

    /** Max bytes per frame; a megabyte railway is ~30 packets, under packet and Fabric limits. */
    public static final int MAX_PART_BYTES = 30_000;

    /** Max parts per session; 2048 x 30000 bytes ~ 61.4 MB, under the 64 MiB client cap. */
    public static final int MAX_PARTS = 2048;

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

        /** Whether the frame is a complete one-part session. */
        public boolean isWhole() {
            return total == 1;
        }
    }

    /** Smallest frame this mod sends. */
    public static byte[] hello() {
        try {
            return encode(new Frame(HELLO, 0, 0L, 0, 1, new byte[0]));
        } catch (IOException impossible) {
            // An in-memory frame cannot fail; the signature exists for the snapshot path.
            throw new IllegalStateException(impossible);
        }
    }

    /** Splits one encoded reading into frames, or returns empty when it exceeds MAX_PARTS. */
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

    /** Encodes one frame; never fails for a frame this class built. */
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

    /** Decodes one frame, or null if not understood; null, not throw, ignores stray bytes. */
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
