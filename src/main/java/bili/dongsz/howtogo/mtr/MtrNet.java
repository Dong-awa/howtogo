//? if fabric && >=1.20.6 && <1.21.2 {
/*package bili.dongsz.howtogo.mtr;

import bili.dongsz.howtogo.client.MtrClientSync;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

// The Fabric end of the MTR sync: payload registration and the two sends.
//
// What is registered where: the payload types are registered once per process, on both sides --
// Fabric will not route a payload it has no type for, and the sending side needs the type as much as
// the receiving one. Receivers are registered per side: a dedicated server has no client receiver and
// no Minecraft client to run one on. Both registration methods are idempotent because the main
// entrypoint and the client entrypoint both run in a client process.
//
// Why the receivers hop threads: a Fabric receiver runs on the network thread, and this mod's client
// store and server manager both touch state only their own thread may touch. Each handler hands its
// work to that thread with the context's execute(); a payload handled off-thread is the classic
// source of a mod that works in single player and corrupts a client on a server.
//
// Every comment in this arm is a `//` line on purpose: this arm is inside the block comment the
// loader condition is written with, and a javadoc block would close that comment early -- the trap
// MIGRATION.md records.
public final class MtrNet {

    private static boolean typesRegistered;

    private MtrNet() {
    }

    // Client initialisation: register the types and the client's receiver.
    public static void registerClient() {
        registerTypes();
        ClientPlayNetworking.registerGlobalReceiver(MtrPayload.SnapshotPart.TYPE,
                (payload, context) -> {
                    byte[] data = payload.data();
                    context.client().execute(() -> MtrClientSync.onPart(data));
                });
    }

    // Server (or integrated server) initialisation: register the types, the receiver and the tick.
    public static void registerServer() {
        registerTypes();
        ServerPlayNetworking.registerGlobalReceiver(MtrPayload.Hello.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            context.server().execute(() -> MtrServerSync.hello(player));
        });
        ServerTickEvents.END_SERVER_TICK.register(MtrServerSync::tick);
    }

    private static void registerTypes() {
        if (typesRegistered) {
            return;
        }
        typesRegistered = true;
        PayloadTypeRegistry.playS2C().register(MtrPayload.SnapshotPart.TYPE,
                MtrPayload.SnapshotPart.CODEC);
        PayloadTypeRegistry.playC2S().register(MtrPayload.Hello.TYPE, MtrPayload.Hello.CODEC);
    }

    // Says hello, or nothing when the server has not registered the payload.
    public static void sendHello() {
        try {
            if (ClientPlayNetworking.canSend(MtrPayload.Hello.TYPE)) {
                ClientPlayNetworking.send(new MtrPayload.Hello(MtrWire.hello()));
            }
        } catch (RuntimeException notConnected) {
            // Asked from the client tick while a world may be closing; nothing to say and nothing to do.
        }
    }

    // Sends one part; the caller has already decided this player said hello.
    // Returns whether it was sent, so the caller can keep the frame for a connection that is not
    // ready yet: Fabric's canSend is false between a join and the client registering the channel.
    public static boolean sendSnapshotPart(ServerPlayer player, byte[] frame) {
        if (!ServerPlayNetworking.canSend(player, MtrPayload.SnapshotPart.TYPE)) {
            return false;
        }
        ServerPlayNetworking.send(player, new MtrPayload.SnapshotPart(frame));
        return true;
    }
}
*///?}
//? if neoforge && >=1.20.6 && <1.21.2 {
package bili.dongsz.howtogo.mtr;

import bili.dongsz.howtogo.client.MtrClientSync;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.handling.IPayloadHandler;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * The NeoForge end of the MTR sync: payload registration and the two sends.
 *
 * <h2>Why there are no init calls here</h2>
 * NeoForge hands the mod bus to a constructed class, and Stonecutter's comment-based conditions do not
 * nest -- a version condition inside the loader fence in {@code HowToGo} is not available. So the
 * registration is attached to the buses by annotation, which FML scans for on its own, on both
 * physical sides: the mod-bus listener registers the payload types, the game-bus listener drives the
 * server's tick. Nothing of this is lost on a dedicated server, where {@code HowToGo} is never
 * constructed.
 *
 * <h2>Why the receivers hop threads</h2>
 * A payload handler runs on the network thread; the client store and the server manager only accept
 * their own thread's calls, so each handler hands its work over with {@code enqueueWork}. A payload
 * arriving off-thread is the classic source of a mod that works in single player and corrupts a
 * client on a server.
 */
public final class MtrNet {

    /** Version of the payload negotiation this mod speaks; bumped with a wire change. */
    private static final String VERSION = "1";

    private MtrNet() {
    }

    /** Fabric-shaped entry point, kept so {@link MtrSync} compiles on every loader. */
    public static void registerClient() {
        // The mod-bus subscriber below does this on this loader.
    }

    /** Fabric-shaped entry point, kept so {@link MtrSync} compiles on every loader. */
    public static void registerServer() {
        // The mod-bus subscriber below does this on this loader.
    }

    /** Says hello, or nothing when there is no server to say it to. */
    public static void sendHello() {
        try {
            PacketDistributor.sendToServer(new MtrPayload.Hello(MtrWire.hello()));
        } catch (RuntimeException notConnected) {
            // Asked from the client tick while a world may be closing; nothing to say and nothing to do.
        }
    }

    /**
     * Sends one part; the caller has already decided this player said hello.
     *
     * <p>Returns whether it was sent, so the caller can keep the frame for a connection that is not
     * ready yet. The channel is asked first because NeoForge's own send path assumes it was: sending a
     * payload the other end never registered throws rather than dropping it, and this runs on the
     * server tick, where an uncaught throw is a crash report.
     */
    public static boolean sendSnapshotPart(ServerPlayer player, byte[] frame) {
        if (player.connection == null || !player.connection.hasChannel(MtrPayload.SnapshotPart.TYPE)) {
            return false;
        }
        PacketDistributor.sendToPlayer(player, new MtrPayload.SnapshotPart(frame));
        return true;
    }

    private static void onSnapshotPart(MtrPayload.SnapshotPart payload, IPayloadContext context) {
        byte[] data = payload.data();
        context.enqueueWork(() -> MtrClientSync.onPart(data));
    }

    private static void onHello(MtrPayload.Hello payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            context.enqueueWork(() -> MtrServerSync.hello(player));
        }
    }

    /** Mod-bus: the payload types, once per process, on both physical sides. */
    @EventBusSubscriber(modid = "howtogo", bus = EventBusSubscriber.Bus.MOD)
    public static final class Payloads {

        private Payloads() {
        }

        @SubscribeEvent
        public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
            // Optional: the channel may be absent on the other side. A vanilla client must still be
            // able to join a server that has this mod, and a client with this mod must still be able
            // to join a server without it; the hello handshake is what decides whether anything is
            // ever sent.
            PayloadRegistrar registrar = event.registrar(VERSION).optional();
            IPayloadHandler<MtrPayload.SnapshotPart> snapshot = MtrNet::onSnapshotPart;
            IPayloadHandler<MtrPayload.Hello> hello = MtrNet::onHello;
            registrar.playToClient(MtrPayload.SnapshotPart.TYPE, MtrPayload.SnapshotPart.CODEC,
                    snapshot);
            registrar.playToServer(MtrPayload.Hello.TYPE, MtrPayload.Hello.CODEC, hello);
        }
    }

    /** Game-bus: the server tick that checks MTR and sends what changed. */
    @EventBusSubscriber(modid = "howtogo", bus = EventBusSubscriber.Bus.GAME)
    public static final class Ticks {

        private Ticks() {
        }

        @SubscribeEvent
        public static void onServerTick(ServerTickEvent.Post event) {
            MtrServerSync.tick(event.getServer());
        }
    }
}
//?}
