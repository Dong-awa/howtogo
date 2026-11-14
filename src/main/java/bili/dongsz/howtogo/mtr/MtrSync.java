//? if >=1.20.6 && <1.21.2 {
package bili.dongsz.howtogo.mtr;

/**
 * Single facade for the MTR sync: call sites compile for twenty-one nodes and two loaders, Stonecutter conditions do not nest, so one arm forwards to MtrNet and the other does nothing. Call sites never need to know loader or version.
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

    /** Announce that this side understands the snapshot payloads and wants them. */
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
