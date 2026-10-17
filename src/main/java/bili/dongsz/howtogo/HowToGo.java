package bili.dongsz.howtogo;

import bili.dongsz.howtogo.api.ApiBootstrap;
import bili.dongsz.howtogo.api.ClientScheduler;
import bili.dongsz.howtogo.client.AddonRoadStore;
import bili.dongsz.howtogo.client.Narration;
import bili.dongsz.howtogo.client.NavHudRenderer;
import bili.dongsz.howtogo.client.Navigation;
import bili.dongsz.howtogo.client.AutoSelfTest;
import bili.dongsz.howtogo.client.MtrClientData;
import bili.dongsz.howtogo.client.RailTrackStore;
import bili.dongsz.howtogo.client.MapFilterOverlay;
import bili.dongsz.howtogo.client.MtrOverlayToggles;
import bili.dongsz.howtogo.client.RoadEditHandler;
import bili.dongsz.howtogo.client.RoadEditSession;
import bili.dongsz.howtogo.client.RoadLayer;
import bili.dongsz.howtogo.client.RoadStore;
import bili.dongsz.howtogo.client.RoadWeaverLayer;
import bili.dongsz.howtogo.client.TransitLineStore;
import bili.dongsz.howtogo.item.ModItems;
import bili.dongsz.howtogo.store.RoutePreferenceStore;
import bili.dongsz.howtogo.webmap.WebMapService;
import com.mojang.logging.LogUtils;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/**
 * Entry point.
 * This mod is deliberately client-only: roads are stored on the client and every feature
 * (rendering, editing, routing) is a pure client concern for now.
 */
@Mod(value = HowToGo.MODID, dist = Dist.CLIENT)
public final class HowToGo {

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

    public HowToGo(IEventBus modEventBus, ModContainer modContainer) {
        // Registration into Xaero's render pipeline has to wait until Xaero has built its
        // handler, so we poll from the client tick instead of doing it here.
        NeoForge.EVENT_BUS.addListener(RoadLayer::onClientTick);
        // The addon API's two tick jobs, and in this order: the deferred queue is drained before the
        // registration event is posted, so work queued by a registration handler waits for the next
        // tick and ClientScheduler's promise -- "the tick after the one that queued it" -- holds for
        // addons exactly as it does for this mod's own commands. See ApiBootstrap and ClientScheduler.
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> ClientScheduler.tick());
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> ApiBootstrap.fireOnce());
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> RoadStore.tick());
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> TransitLineStore.tick());
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> RailTrackStore.tick());
        // RoadWeaver's roads: read from its client map snapshot, or from its own road store while the
        // player hosts the world. After the stores above so a layer rebuilt this tick sees the same
        // world they did. See RoadWeaverLayer.
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> RoadWeaverLayer.tick());
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> MtrClientData.tick());
        // Addon layers: one version comparison per registered source per tick, and a re-read only when
        // that number has moved. After the stores above so that a layer rebuilt this tick sees the same
        // world they did. See AddonRoadStore.
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> AddonRoadStore.tick());
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> RoadEditSession.tick());
        // The main screen's key, read from the tick because it is a key for the world rather than for
        // a screen; see RoadEditHandler.tickMainMenuKey.
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> RoadEditHandler.tickMainMenuKey());
        // MTR Map Overlay resets its two map toggles every session (it only ever sets them at
        // runtime), so their last state is remembered here and put back once the world is open.
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> MtrOverlayToggles.tick());
        // The browser map's server, when the config asks for it to start itself: it waits for a world
        // to be loaded, because a page opened onto a 503 is worse than a socket opened a moment later.
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> WebMapService.tick());
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> Navigation.tick());
        // The commands' own deferral: a screen cannot be opened from inside the command that asked for
        // it, so the work is run on the tick after.
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> HowToGoCommand.tick());
        // Client commands: opening the terminal, ending the trip and switching travel mode are all
        // client state, so they run here with no server involved and no permission needed.
        NeoForge.EVENT_BUS.addListener(HowToGoCommand::onRegisterClientCommands);
        // Ticked rather than drawn: the readout is recomputed per frame, so deciding what to say
        // there would repeat the same instruction continuously, and the HUD does not run at all
        // while a screen is open. The speaking itself is on the narration thread; this only feeds it.
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> Narration.tick());
        // The in-game self-test, when a launcher has asked for one: see AutoSelfTest and
        // tools/user-test/run-user-test.ps1. Silent unless the property is set.
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> AutoSelfTest.tick());
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
        ModItems.register(modEventBus);
        modContainer.registerConfig(ModConfig.Type.CLIENT, RoadConfig.SPEC);
        // The config screen: the button beside this mod in the mods list. NeoForge's own editor
        // reads the very SPEC the file is written from -- the same sections, defaults, comments and
        // ranges -- so nothing here is a second copy of the settings that could drift from the file,
        // and a value saved in the screen is a value the next reload of the file sees. Without this
        // no screen is offered at all, which is why the two colour sections would otherwise only be
        // editable by hand in the TOML.
        modContainer.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);

        LOGGER.info("[HowToGo] constructed");
    }
}
