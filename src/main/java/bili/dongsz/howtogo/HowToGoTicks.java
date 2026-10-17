//? if neoforge && >=1.20.5 {
package bili.dongsz.howtogo;

import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

// Registers a client-tick job, in whichever shape the NeoForge version offers.
//
// From 1.20.5 the event is ClientTickEvent.Post -- one subclass per phase -- so a job is a listener
// and nothing else. Before that there is a single TickEvent.ClientTickEvent carrying a Phase, fired
// twice per tick, so the job has to be guarded rather than the event chosen.
//
// A helper rather than a condition at each registration, because there are twelve of them and they
// live in HowToGo, which is fenced whole by loader -- a version condition inside its neoforge arm
// would nest, and the twelve registrations would have to be written out twice. Here they are
// written once and the difference is one method.
//
// The two arms of this file are top-level conditions on both axes, which is what keeps that
// possible: on fabric both are inactive and the file compiles to nothing.
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
