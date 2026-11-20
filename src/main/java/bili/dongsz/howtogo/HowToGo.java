package bili.dongsz.howtogo;

import bili.dongsz.howtogo.api.ApiBootstrap;
import bili.dongsz.howtogo.api.ClientScheduler;
import bili.dongsz.howtogo.client.AddonRoadStore;
import bili.dongsz.howtogo.client.Narration;
import bili.dongsz.howtogo.client.NavHudRenderer;
import bili.dongsz.howtogo.client.Navigation;
import bili.dongsz.howtogo.client.AutoSelfTest;
import bili.dongsz.howtogo.client.MtrClientData;
import bili.dongsz.howtogo.client.PlaceDetailStore;
import bili.dongsz.howtogo.client.RailTrackStore;
import bili.dongsz.howtogo.client.MapFilterOverlay;
import bili.dongsz.howtogo.client.NavMapMode;
import bili.dongsz.howtogo.client.MtrOverlayToggles;
import bili.dongsz.howtogo.client.RoadEditHandler;
import bili.dongsz.howtogo.client.RoadEditSession;
import bili.dongsz.howtogo.client.RoadLayer;
import bili.dongsz.howtogo.client.RoadStore;
import bili.dongsz.howtogo.client.RoadWeaverLayer;
import bili.dongsz.howtogo.client.TransitLineStore;
import bili.dongsz.howtogo.mtr.MtrSync;
import bili.dongsz.howtogo.store.RoutePreferenceStore;
import bili.dongsz.howtogo.webmap.WebMapService;
import com.mojang.logging.LogUtils;
//? if fabric {
/*import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
*///?} else {
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
//?}
import org.slf4j.Logger;

/**
 * Entry point.
 * This mod is client-only: roads are stored client-side and rendering, editing and routing are all client concerns.
 * NeoForge constructs the class with the mod bus and container and subscribes listeners there. Fabric has one onInitializeClient, collapses the tick listeners into one END_CLIENT_TICK callback, uses HudRenderCallback, and has each class register itself; it also reads the config itself because Fabric has no config service -- see RoadConfig. The class declaration and entry point are written once per loader; the rest is shared.
 */
// The two halves are whole declarations so loader differences are not scaffolded inside a shared
//? if fabric {
/*public final class HowToGo implements ClientModInitializer {
*///?} else {
@Mod(value = HowToGo.MODID, dist = Dist.CLIENT)
public final class HowToGo {
//?}

    public static final String MODID = "howtogo";
    public static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Writes one of the mod's own diagnostics, only when RoadConfig.debugLog() is on.
     * A diagnostic describes how the mod is working (per-second rail line, map frame cost, route arithmetic); warnings and errors stay on the logger directly.
     */
    public static void diagnostic(String format, Object... args) {
        if (RoadConfig.debugLog()) {
            LOGGER.info(format, args);
        }
    }

    /**
     * Writes one of the mod's ordinary progress lines, only when RoadConfig.logInfo() is on.
     * The periodic lines -- autosaves, data re-reads, route recalculations, the editing mode's on and off notes -- and the one-off lifecycle lines alike go through here, so the settings screen's log switch quiets the whole mod with one answer while warnings and errors stay on the logger directly. Diagnostics keep their own gate: turning info off never hides a line the debug switch explicitly asked for.
     */
    public static void logInfo(String format, Object... args) {
        if (RoadConfig.logInfo()) {
            LOGGER.info(format, args);
        }
    }

    //? if fabric {
    /*@Override
    public void onInitializeClient() {
        // The config first: the calls below read it, and on NeoForge it had already been read by the
        // time the client-setup event this replaces was posted.
        RoadConfig.load();
        // Client setup runs after the config files have been read, so this is the first point at which
        // the configured travel mode is genuinely available -- and the only point at which the saved
        // route preferences sit in front of config values that are known to be loaded.
        Navigation.loadConfiguredMode();
        RoutePreferenceStore.load();

        // One callback rather than thirteen listeners, run in the order the original registered them.
        // Registration into Xaero's render pipeline has to wait until Xaero has built its handler, so
        // RoadLayer polls from the client tick instead of doing it during initialisation.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            RoadLayer.onClientTick();
            // The display mode's own tick job: it polls Xaero's Minimap for the overlay's
            // registration and writes the one log line that says which mode is in force and, when
            // the answer is not what the player asked for, why. See NavMapMode.
            NavMapMode.onClientTick();
            // The addon API's two tick jobs, and in this order: the deferred queue is drained before
            // the registration event is posted, so work queued by a registration handler waits for the
            // next tick and ClientScheduler's promise -- "the tick after the one that queued it" --
            // holds for addons exactly as it does for this mod's own commands.
            ClientScheduler.tick();
            ApiBootstrap.fireOnce();
            RoadStore.tick();
            // After the roads: the details' "unlinked" reading asks the road network which of the
            // player's own places still exist, so it wants this tick's network rather than last
            // tick's.
            PlaceDetailStore.tick();
            TransitLineStore.tick();
            RailTrackStore.tick();
            // RoadWeaver's roads: read from its client map snapshot, or from its own road store while the
            // player hosts the world. After the stores above so a layer rebuilt this tick sees the same
            // world they did. See RoadWeaverLayer.
            RoadWeaverLayer.tick();
            MtrClientData.tick();
            // Addon layers: one version comparison per registered source per tick, and a re-read only when
            // that number has moved. After the stores above so that a layer rebuilt this tick sees the same
            // world they did. See AddonRoadStore.
            AddonRoadStore.tick();
            RoadEditSession.tick();
            // The main screen's key, read from the tick because it is a key for the world rather
            // than for a screen; see RoadEditHandler.tickMainMenuKey. The K key is the mod's only
            // way in now, so it is registered and read on both loaders.
            RoadEditHandler.tickMainMenuKey();
            // MTR Map Overlay resets its two map toggles every session (it only ever sets them at
            // runtime), so their last state is remembered here and put back once the world is open.
            MtrOverlayToggles.tick();
            // The browser map's server, when the config asks for it to start itself: it waits for a
            // world to be loaded, because a page opened onto a 503 is worse than a socket opened a
            // moment later.
            WebMapService.tick();
            Navigation.tick();
            // The commands' own deferral: a screen cannot be opened from inside the command that asked
            // for it, so the work is run on the tick after.
            HowToGoCommand.tick();
            // Ticked rather than drawn: the readout is recomputed per frame, so deciding what to say
            // there would repeat the same instruction continuously, and the HUD does not run at all
            // while a screen is open. The speaking itself is on the narration thread; this only feeds
            // it.
            Narration.tick();
            // The in-game self-test, when a launcher has asked for one: see AutoSelfTest and
            // tools/user-test. Silent unless the property is set.
            AutoSelfTest.tick();
        });

        // Drawn rather than ticked: the readout is recomputed per frame from the state the tick left.
        HudRenderCallback.EVENT.register(NavHudRenderer::onRenderGui);
        // Client commands: opening the terminal, ending the trip and switching travel mode are all
        // client state, so they run here with no server involved and no permission needed.
        HowToGoCommand.register();
        // Editing keys are offered to the screen before the screen itself sees them, and editing clicks
        // arrive through a Mixin on the mouse handler, because Fabric has no global mouse event. See
        // RoadEditHandler.
        RoadEditHandler.registerKeys();
        RoadEditHandler.registerScreenHandlers();
        // The MTR sync's client half: the payload types and receiver. The hello is sent later, from
        // the client tick, so that it can wait for a world -- see MtrClientSync.
        MtrSync.onClientInit();
        // The map's switches: a screen overlay rather than part of the map, so that nothing the map
        // draws can end up over them. See MapFilterOverlay.
        MapFilterOverlay.register();

        logInfo("[HowToGo] constructed");
    }
    *///?} else {
    public HowToGo(IEventBus modEventBus, ModContainer modContainer) {
        // Xaero's render pipeline is registered only after Xaero builds its handler, so poll
        HowToGoTicks.onClientTick(RoadLayer::onClientTick);
        // Polls Xaero's Minimap for overlay registration and logs which display mode is in force
        HowToGoTicks.onClientTick(NavMapMode::onClientTick);
        // The addon API's tick jobs, in this order: drain the deferred queue, then post
        HowToGoTicks.onClientTick(ClientScheduler::tick);
        HowToGoTicks.onClientTick(ApiBootstrap::fireOnce);
        HowToGoTicks.onClientTick(RoadStore::tick);
        // After the roads: the unlinked reading asks the road network which places still exist.
        HowToGoTicks.onClientTick(PlaceDetailStore::tick);
        HowToGoTicks.onClientTick(TransitLineStore::tick);
        HowToGoTicks.onClientTick(RailTrackStore::tick);
        // RoadWeaver's roads, from its client map snapshot or its own store while hosting.
        HowToGoTicks.onClientTick(RoadWeaverLayer::tick);
        HowToGoTicks.onClientTick(MtrClientData::tick);
        // Addon layers: one version comparison per source per tick, re-read only when it moves.
        HowToGoTicks.onClientTick(AddonRoadStore::tick);
        HowToGoTicks.onClientTick(RoadEditSession::tick);
        // The main screen's key is for the world rather than a screen, so it is read from the tick.
        HowToGoTicks.onClientTick(RoadEditHandler::tickMainMenuKey);
        // MTR Map Overlay sets its two map toggles only at runtime, so their last state
        HowToGoTicks.onClientTick(MtrOverlayToggles::tick);
        // The browser map's server, if the config starts it: waits for a loaded world, since
        HowToGoTicks.onClientTick(WebMapService::tick);
        HowToGoTicks.onClientTick(Navigation::tick);
        // Command deferral: a screen cannot open from inside the command that asked
        HowToGoTicks.onClientTick(HowToGoCommand::tick);
        // Client commands: opening the terminal, ending the trip and switching travel mode need no
        NeoForge.EVENT_BUS.addListener(HowToGoCommand::onRegisterClientCommands);
        // Ticked rather than drawn: the readout is recomputed per frame, and the HUD does not run
        HowToGoTicks.onClientTick(Narration::tick);
        // The in-game self-test when a launcher asked for one: see AutoSelfTest
        HowToGoTicks.onClientTick(AutoSelfTest::tick);
        NeoForge.EVENT_BUS.addListener(NavHudRenderer::onRenderGui);

        // Key edits arrive as screen events and edits as low-level input events; the paths are not
        NeoForge.EVENT_BUS.addListener(RoadEditHandler::onMouseButton);
        NeoForge.EVENT_BUS.addListener(RoadEditHandler::onMouseButtonReleased);
        NeoForge.EVENT_BUS.addListener(RoadEditHandler::onScreenKeyPressed);
        NeoForge.EVENT_BUS.addListener(RoadEditHandler::onScreenKeyReleased);
        NeoForge.EVENT_BUS.addListener(RoadEditHandler::onScreenOpening);
        // The map switches are a screen overlay, so nothing the map draws can cover them.
        NeoForge.EVENT_BUS.addListener(MapFilterOverlay::onScreenRender);
        NeoForge.EVENT_BUS.addListener(MapFilterOverlay::onMousePressed);
        NeoForge.EVENT_BUS.addListener(MapFilterOverlay::onMouseDragged);
        NeoForge.EVENT_BUS.addListener(MapFilterOverlay::onMouseReleased);

        modEventBus.addListener(RoadEditHandler::onRegisterKeyMappings);
        // A config save must reach the values this mod caches.
        modEventBus.addListener((net.neoforged.fml.event.config.ModConfigEvent.Reloading event) -> {
            Navigation.reloadConfiguredMode();
            RoutePreferenceStore.load();
            // RoadWeaverLayer bakes its source class, so a change to this key must make it re-read.
            RoadWeaverLayer.invalidate();
        });
        // Client setup runs after config is read, so this is where the configured travel mode
        modEventBus.addListener((FMLClientSetupEvent event) -> {
            Navigation.loadConfiguredMode();
            RoutePreferenceStore.load();
        });
        HowToGoConfig.registerClient(modContainer);

        logInfo("[HowToGo] constructed");
    }
    //?}
}
