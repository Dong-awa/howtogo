//? if >=1.20.6 && <1.21.2 {
package bili.dongsz.howtogo.mtr;

/**
 * The one door the rest of the mod knocks on for the MTR sync, whichever loaders this node builds for.
 *
 * <h2>Why a facade exists</h2>
 * The feature is implemented for a version range and both loaders, while the rest of the mod compiles
 * for twenty-one nodes. Rather than a condition at every call site -- and Stonecutter's comment-based
 * conditions do not nest, so a version condition inside a loader arm is not available anyway -- the
 * call sites talk to this class, and this file is written twice: the arm below forwards to
 * {@link MtrNet} where the feature exists, the other arm does nothing where it does not. Call sites
 * therefore never need to know either the loader or the version.
 */
public final class MtrSync {

    private MtrSync() {
    }

    /** Client initialisation: register the client's payload handlers. */
    public static void onClientInit() {
        MtrNet.registerClient();
    }

    /** Server initialisation: register the server's payload handlers and tick job. */
    public static void onServerInit() {
        MtrNet.registerServer();
    }

    /** "I am here and I understand the snapshot payloads; send them." */
    public static void sendHello() {
        MtrNet.sendHello();
    }
}
//?} else {
/*package bili.dongsz.howtogo.mtr;

// The same door on a node where the MTR sync is not implemented: every method is a no-op, so the
// rest of the mod compiles and behaves exactly as it did before this feature existed.
public final class MtrSync {

    private MtrSync() {
    }

    public static void onClientInit() {
    }

    public static void onServerInit() {
    }

    public static void sendHello() {
    }
}
*///?}
