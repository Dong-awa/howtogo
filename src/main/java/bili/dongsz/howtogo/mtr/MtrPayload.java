//? if >=1.20.6 && <1.21 {
/*package bili.dongsz.howtogo.mtr;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

// The two custom payloads the MTR sync is carried in -- the pre-1.21 arm.
//
// Why they carry one byte[] and nothing else: everything that differs between the loaders and between
// the Minecraft versions this mod supports lives in the payload type itself -- what it is called,
// which codec interface it declares, and which buffer the codec is written against. Keeping the body
// opaque makes each of those a two-line StreamCodec, and puts the protocol -- kinds, sessions, chunk
// indexes and the reading -- in MtrWire, where it is one implementation for every node.
//
// Both payloads are play-phase only, and the server only ever sends SnapshotPart to a client that has
// said Hello.
//
// This file is written twice: the only difference is the ResourceLocation factory, which is a public
// constructor before 1.21 and a static factory from 1.21 on. A version condition inside one class body
// would nest inside the arm the whole file is written with, and the inactive arm is a block comment --
// so the arms are whole files. Every comment in an arm is a `//` line for the same reason: a javadoc
// block would close the comment the arm is written with early.
public final class MtrPayload {

    private static final String MOD_ID = "howtogo";

    private MtrPayload() {
    }

    // One part of one MTR reading, as framed by MtrWire.
    public record SnapshotPart(byte[] data) implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<SnapshotPart> TYPE = new CustomPacketPayload.Type<>(
                new ResourceLocation(MOD_ID, "mtr_snapshot"));

        public static final StreamCodec<FriendlyByteBuf, SnapshotPart> CODEC = StreamCodec.of(
                (buffer, payload) -> buffer.writeByteArray(payload.data()),
                buffer -> new SnapshotPart(buffer.readByteArray()));

        public SnapshotPart {
            data = data == null ? new byte[0] : data;
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // "I am here and I understand the snapshot payloads; send them."
    public record Hello(byte[] data) implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<Hello> TYPE = new CustomPacketPayload.Type<>(
                new ResourceLocation(MOD_ID, "mtr_hello"));

        public static final StreamCodec<FriendlyByteBuf, Hello> CODEC = StreamCodec.of(
                (buffer, payload) -> buffer.writeByteArray(payload.data()),
                buffer -> new Hello(buffer.readByteArray()));

        public Hello {
            data = data == null ? new byte[0] : data;
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
*///?}
//? if >=1.21 && <1.21.2 {
package bili.dongsz.howtogo.mtr;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

// The two custom payloads the MTR sync is carried in -- the 1.21 arm.
//
// See the other arm for why the body is one byte[] and why this file is written twice. The only
// difference here is the ResourceLocation factory: 1.21 made the constructor private and
// fromNamespaceAndPath the only way in.
public final class MtrPayload {

    private static final String MOD_ID = "howtogo";

    private MtrPayload() {
    }

    /** One part of one MTR reading, as framed by {@link MtrWire}. */
    public record SnapshotPart(byte[] data) implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<SnapshotPart> TYPE = new CustomPacketPayload.Type<>(
                ResourceLocation.fromNamespaceAndPath(MOD_ID, "mtr_snapshot"));

        public static final StreamCodec<FriendlyByteBuf, SnapshotPart> CODEC = StreamCodec.of(
                (buffer, payload) -> buffer.writeByteArray(payload.data()),
                buffer -> new SnapshotPart(buffer.readByteArray()));

        public SnapshotPart {
            data = data == null ? new byte[0] : data;
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** "I am here and I understand the snapshot payloads; send them." */
    public record Hello(byte[] data) implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<Hello> TYPE = new CustomPacketPayload.Type<>(
                ResourceLocation.fromNamespaceAndPath(MOD_ID, "mtr_hello"));

        public static final StreamCodec<FriendlyByteBuf, Hello> CODEC = StreamCodec.of(
                (buffer, payload) -> buffer.writeByteArray(payload.data()),
                buffer -> new Hello(buffer.readByteArray()));

        public Hello {
            data = data == null ? new byte[0] : data;
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
//?}
