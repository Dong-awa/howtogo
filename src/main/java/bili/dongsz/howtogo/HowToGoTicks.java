//? if neoforge && >=1.20.5 {
package bili.dongsz.howtogo;

import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

// Registers a client-tick job in whichever shape the NeoForge version offers: ClientTickEvent.Post
// from 1.20.5, the Phase-carrying ClientTickEvent before that.
// A helper rather than a condition at each of the twelve call sites, which live in HowToGo's
// loader-fenced body. On fabric both arms are inactive and this file compiles to nothing.
final class HowToGoTicks {

    private HowToGoTicks() {
    }

    static void onClientTick(Runnable work) {
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> work.run());
    }
}
//?}
//? if neoforge && <1.20.5 {
/*package bili.dongsz.howtogo;

import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.TickEvent;

// The pre-1.21 shape of the same thing. See the other arm of this file for why it is a helper.
final class HowToGoTicks {

    private HowToGoTicks() {
    }

    static void onClientTick(Runnable work) {
        NeoForge.EVENT_BUS.addListener((TickEvent.ClientTickEvent event) -> {
            if (event.phase == TickEvent.Phase.END) {
                work.run();
            }
        });
    }
}
*///?}
