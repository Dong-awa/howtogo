package bili.dongsz.howtogo;

import bili.dongsz.howtogo.api.ApiBootstrap;
import bili.dongsz.howtogo.api.ClientScheduler;
import bili.dongsz.howtogo.client.Narration;
import bili.dongsz.howtogo.client.NavHudRenderer;
import bili.dongsz.howtogo.client.Navigation;
import bili.dongsz.howtogo.client.AutoSelfTest;
import bili.dongsz.howtogo.client.MtrClientData;
import bili.dongsz.howtogo.client.RailTrackStore;
import bili.dongsz.howtogo.client.MapFilterOverlay;
import bili.dongsz.howtogo.client.RoadEditHandler;
import bili.dongsz.howtogo.client.RoadEditSession;
import bili.dongsz.howtogo.client.RoadLayer;
import bili.dongsz.howtogo.client.RoadStore;
import bili.dongsz.howtogo.client.TransitLineStore;
import bili.dongsz.howtogo.store.RoutePreferenceStore;
import bili.dongsz.howtogo.webmap.WebMapService;
import com.mojang.logging.LogUtils;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import org.slf4j.Logger;

/**
 * The client entry point.
 *
 * <p>This mod is deliberately client-only: roads are stored on the client and every feature
 * (rendering, editing, routing) is a pure client concern for now.
 *
 * <h2>What the Fabric port had to change here</h2>
 * On NeoForge this class was an {@code @Mod(dist = Dist.CLIENT)} whose constructor subscribed
 * thirteen listeners to two buses. Fabric has neither an event bus on a mod container nor a
 * constructor to subscribe from: initialisation is the single {@link #onInitializeClient()} call, the
 * tick listeners collapse into one {@link ClientTickEvents#END_CLIENT_TICK} callback run in the same
 * order as the original registered them, the HUD listener becomes {@link HudRenderCallback}, and the
 * key, screen-input and command registrations are made by the classes that own them -- see
 * {@code RoadEditHandler.registerKeys()}, {@code RoadEditHandler.registerScreenHandlers()},
 * {@code MapFilterOverlay.register()} and {@code HowToGoCommand.register()}.
 *
 * <p>The config is read here rather than by the loader, because Fabric has no config service; see
 * {@link RoadConfig} for what that cost and what it deliberately preserved.
 *
 * <p>The item is registered by {@link HowToGoServer}, the entry point that runs on both sides, for the
 * reason given there.
 */
public final class HowToGo implements ClientModInitializer {

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

    @Override
    public void onInitializeClient() {
        // The config first: the two calls below read it, and on NeoForge it had already been read by the
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
            // The addon API's two tick jobs, and in this order: the deferred queue is drained before
            // the registration event is posted, so work queued by a registration handler waits for the
            // next tick and ClientScheduler's promise -- "the tick after the one that queued it" --
            // holds for addons exactly as it does for this mod's own commands. See ApiBootstrap and
            // ClientScheduler.
            ClientScheduler.tick();
            ApiBootstrap.fireOnce();
            RoadStore.tick();
            TransitLineStore.tick();
            RailTrackStore.tick();
            MtrClientData.tick();
            RoadEditSession.tick();
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
        // The map's switches: a screen overlay rather than part of the map, so that nothing the map
        // draws can end up over them. See MapFilterOverlay.
        MapFilterOverlay.register();

        LOGGER.info("[HowToGo] constructed");
    }
}
