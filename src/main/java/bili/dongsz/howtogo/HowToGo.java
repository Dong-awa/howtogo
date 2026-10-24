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
 * This mod is deliberately client-only: roads are stored on the client and every feature
 * (rendering, editing, routing) is a pure client concern for now.
 *
 * <h2>How the two loaders differ here, and why the class is written twice</h2>
 * NeoForge constructs this class with the mod bus and a container, and everything is subscribed to
 * two buses from that constructor: thirteen listeners on the game bus for the tick jobs, the HUD,
 * the editing input and the map's switches, and three on the mod bus for the key mappings, the
 * config reload and the client-setup step.
 *
 * <p>Fabric has neither a bus on a mod container nor a constructor to subscribe from. There is one
 * {@code onInitializeClient} call, the thirteen tick listeners collapse into one
 * {@code END_CLIENT_TICK} callback run in the same order the original registered them, the HUD
 * listener becomes {@code HudRenderCallback}, and the registrations that belong to a class are made
 * by that class -- see {@code RoadEditHandler.registerKeys()},
 * {@code RoadEditHandler.registerScreenHandlers()}, {@code MapFilterOverlay.register()} and
 * {@code HowToGoCommand.register()}.
 *
 * <p>The config is read by this class on Fabric rather than by the loader, because Fabric has no
 * config service; see {@link RoadConfig} for what that cost and what it deliberately preserved.
 *
 * <p>So the class declaration and the entry point are written once per loader, and everything else
 * in the file -- the id, the logger and {@link #diagnostic} -- is shared and sits between them.
 */
// The two halves are whole declarations rather than conditions inside one: the loaders disagree
// about what an entry point is, so a shared shell with the differences inside would be more
// scaffolding than code. The javadoc above stays outside, because a `/** ... */` inside the
// `/* ... */` a condition is written with would close it early.
//? if fabric {
/*public final class HowToGo implements ClientModInitializer {
*///?} else {
@Mod(value = HowToGo.MODID, dist = Dist.CLIENT)
public final class HowToGo {
//?}

    public static final String MODID = "howtogo";
    public static final Logger LOGGER = LogUtils.getLogger();

    /**
     * One of this mod's own diagnostics, written only when the player has asked for them.
     *
     * <h2>What counts as a diagnostic</h2>
     * A line about how the mod is <em>working</em> rather than about what happened to the player: the
     * rail layer's one line a second, the cost of a map frame, what an MTR reading turned into, the
     * arithmetic of a planned route, the geometry of a line drawn as a straight step. All of it is
     * worth having while something is being investigated and none of it is worth writing once a second
     * for a whole session, so it goes through here and the switch is {@link RoadConfig#debugLog()}.
     *
     * <p>Everything that is not a diagnostic stays on the logger directly: warnings and errors, which
     * are always reported because a problem a player cannot see is a problem nobody can fix, and the
     * handful of one-off lines that say the mod loaded, saved or listened -- those are the record of a
     * session rather than measurements of it.
     */
    public static void diagnostic(String format, Object... args) {
        if (RoadConfig.debugLog()) {
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
            MtrClientData.tick();
            RoadEditSession.tick();
            // The main screen's key, read from the tick because it is a key for the world rather
            // than for a screen; see RoadEditHandler.tickMainMenuKey. The K key is the mod's only
            // way in now, so it is registered and read on both loaders.
            RoadEditHandler.tickMainMenuKey();
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

        LOGGER.info("[HowToGo] constructed");
    }
    *///?} else {
    public HowToGo(IEventBus modEventBus, ModContainer modContainer) {
        // Registration into Xaero's render pipeline has to wait until Xaero has built its
        // handler, so we poll from the client tick instead of doing it here.
        HowToGoTicks.onClientTick(RoadLayer::onClientTick);
        // The display mode's own tick job: it polls Xaero's Minimap for the overlay's registration
        // and writes the one log line that says which mode is in force and, when the answer is not
        // what the player asked for, why. See NavMapMode.
        HowToGoTicks.onClientTick(NavMapMode::onClientTick);
        // The addon API's two tick jobs, and in this order: the deferred queue is drained before the
        // registration event is posted, so work queued by a registration handler waits for the next
        // tick and ClientScheduler's promise -- "the tick after the one that queued it" -- holds for
        // addons exactly as it does for this mod's own commands. See ApiBootstrap and ClientScheduler.
        HowToGoTicks.onClientTick(ClientScheduler::tick);
        HowToGoTicks.onClientTick(ApiBootstrap::fireOnce);
        HowToGoTicks.onClientTick(RoadStore::tick);
        // After the roads, for the same reason as the Fabric arm: the details' "unlinked" reading
        // asks the road network which of the player's own places still exist. See PlaceDetailStore.
        HowToGoTicks.onClientTick(PlaceDetailStore::tick);
        HowToGoTicks.onClientTick(TransitLineStore::tick);
        HowToGoTicks.onClientTick(RailTrackStore::tick);
        // RoadWeaver's roads: read from its client map snapshot, or from its own road store while the
        // player hosts the world. After the stores above so a layer rebuilt this tick sees the same
        // world they did. See RoadWeaverLayer.
        HowToGoTicks.onClientTick(RoadWeaverLayer::tick);
        HowToGoTicks.onClientTick(MtrClientData::tick);
        // Addon layers: one version comparison per registered source per tick, and a re-read only when
        // that number has moved. After the stores above so that a layer rebuilt this tick sees the same
        // world they did. See AddonRoadStore.
        HowToGoTicks.onClientTick(AddonRoadStore::tick);
        HowToGoTicks.onClientTick(RoadEditSession::tick);
        // The main screen's key, read from the tick because it is a key for the world rather than for
        // a screen; see RoadEditHandler.tickMainMenuKey.
        HowToGoTicks.onClientTick(RoadEditHandler::tickMainMenuKey);
        // MTR Map Overlay resets its two map toggles every session (it only ever sets them at
        // runtime), so their last state is remembered here and put back once the world is open.
        HowToGoTicks.onClientTick(MtrOverlayToggles::tick);
        // The browser map's server, when the config asks for it to start itself: it waits for a world
        // to be loaded, because a page opened onto a 503 is worse than a socket opened a moment later.
        HowToGoTicks.onClientTick(WebMapService::tick);
        HowToGoTicks.onClientTick(Navigation::tick);
        // The commands' own deferral: a screen cannot be opened from inside the command that asked for
        // it, so the work is run on the tick after.
        HowToGoTicks.onClientTick(HowToGoCommand::tick);
        // Client commands: opening the terminal, ending the trip and switching travel mode are all
        // client state, so they run here with no server involved and no permission needed.
        NeoForge.EVENT_BUS.addListener(HowToGoCommand::onRegisterClientCommands);
        // Ticked rather than drawn: the readout is recomputed per frame, so deciding what to say
        // there would repeat the same instruction continuously, and the HUD does not run at all
        // while a screen is open. The speaking itself is on the narration thread; this only feeds it.
        HowToGoTicks.onClientTick(Narration::tick);
        // The in-game self-test, when a launcher has asked for one: see AutoSelfTest and
        // tools/user-test/run-user-test.ps1. Silent unless the property is set.
        HowToGoTicks.onClientTick(AutoSelfTest::tick);
        NeoForge.EVENT_BUS.addListener(NavHudRenderer::onRenderGui);

        // Editing keys arrive as screen events and editing clicks as low-level input events, and the two
        // paths are not interchangeable in this build: the keyboard handler offers a key to the open
        // screen first and returns before posting InputEvent.Key if the screen took it, while the mouse
        // handler posts its event before any screen sees the click. RoadEditHandler carries the detail.
        NeoForge.EVENT_BUS.addListener(RoadEditHandler::onMouseButton);
        NeoForge.EVENT_BUS.addListener(RoadEditHandler::onMouseButtonReleased);
        NeoForge.EVENT_BUS.addListener(RoadEditHandler::onScreenKeyPressed);
        NeoForge.EVENT_BUS.addListener(RoadEditHandler::onScreenKeyReleased);
        NeoForge.EVENT_BUS.addListener(RoadEditHandler::onScreenOpening);
        // The map's switches: a screen overlay rather than part of the map, so that nothing the map
        // draws can end up over them. See MapFilterOverlay.
        NeoForge.EVENT_BUS.addListener(MapFilterOverlay::onScreenRender);
        NeoForge.EVENT_BUS.addListener(MapFilterOverlay::onMousePressed);
        NeoForge.EVENT_BUS.addListener(MapFilterOverlay::onMouseDragged);
        NeoForge.EVENT_BUS.addListener(MapFilterOverlay::onMouseReleased);

        modEventBus.addListener(RoadEditHandler::onRegisterKeyMappings);
        // A config screen's save has to reach the values this mod holds rather than reads where it
        // uses them. Almost every key is read at the point of use and needs nothing; the route
        // preferences and the default travel mode are seeded once, so saving the screen used to leave
        // them with no effect until the game was restarted -- while the keys beside them in the same
        // screen took effect at once, which is what made it read as a broken screen.
        modEventBus.addListener((net.neoforged.fml.event.config.ModConfigEvent.Reloading event) -> {
            Navigation.reloadConfiguredMode();
            RoutePreferenceStore.load();
            // The class RoadWeaver's roads are converted to is baked into the layer, so a change to
            // that key has to make the layer read itself again. Every other RoadWeaver key is read
            // where it is used and needs nothing here.
            RoadWeaverLayer.invalidate();
        });
        // Client setup runs after the config files have been read, so this is the first point at
        // which the configured travel mode is genuinely available -- and the only point at which
        // the saved route preferences sit in front of config values that are known to be loaded.
        modEventBus.addListener((FMLClientSetupEvent event) -> {
            Navigation.loadConfiguredMode();
            RoutePreferenceStore.load();
        });
        HowToGoConfig.registerClient(modContainer);

        LOGGER.info("[HowToGo] constructed");
    }
    //?}
}
