package bili.dongsz.howtogo.api;

import bili.dongsz.howtogo.route.DestinationSource;
//? if fabric {
/*import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
*///?} else {
import net.neoforged.bus.api.Event;
//?}

/**
 * Posted once, on the first client tick, on the client thread, after every mod is constructed and client config is read.
 * NeoForge: extends net.neoforged.bus.api.Event and is posted on the game event bus. Fabric: no common event supertype, so the class publishes its own EVENT and handlers register on it.
 * An addon may also subscribe from its own initialiser -- the registries behind this are open from the start. Do not register from a constructor: mod construction order is not a contract.
 * Client thread, so a handler may read config or inspect a loaded world. An id may be taken once; registering twice is refused.
 */
// The two halves are written out in full rather than interleaved: the only shared part would be the constructor and two delegating methods. Comments inside a condition must be // lines -- a block comment would close the /* the condition is written with.
//? if fabric {
/*public final class HowToGoRegistrationEvent {

    // What an addon registers on EVENT; Fabric's substitute for @SubscribeEvent.
    @FunctionalInterface
    public interface Handler {
        void onRegistration(HowToGoRegistrationEvent event);
    }

    // The event itself. Handlers run in registration order; a throwing handler is the loader's problem.
    public static final Event<Handler> EVENT = EventFactory.createArrayBacked(Handler.class,
            handlers -> event -> {
                for (Handler handler : handlers) {
                    handler.onRegistration(event);
                }
            });

    // Constructed by this mod when it posts the event; addons only ever receive one.
    public HowToGoRegistrationEvent() {
    }

    public void registerDestinationSource(DestinationSource source) {
        DestinationSources.register(source);
    }

    public void registerSelfCheck(SelfCheck check) {
        SelfChecks.register(check);
    }
}
*///?} else {
public final class HowToGoRegistrationEvent extends Event {

    /** Constructed by this mod when it posts the event; addons only ever receive one. */
    public HowToGoRegistrationEvent() {
    }

    /** Contributes a destination source; see HowToGoApi.registerDestinationSource. */
    public void registerDestinationSource(DestinationSource source) {
        DestinationSources.register(source);
    }

    /** Contributes a self-check; see HowToGoApi.registerSelfCheck. */
    public void registerSelfCheck(SelfCheck check) {
        SelfChecks.register(check);
    }
}
//?}
