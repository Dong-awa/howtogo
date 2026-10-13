package bili.dongsz.howtogo.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The three pieces of screen/HUD plumbing whose home moved during the 26.x releases, in one
 * place so the call sites do not have to care.
 *
 * <p>26.2 moved screen management onto {@code Minecraft.gui}: what used to be the public
 * {@code Minecraft.screen} field and {@code Minecraft.setScreen(Screen)} became
 * {@code Minecraft.gui.screen()} and {@code Minecraft.gui.setScreen(Screen)}. The overlay
 * message moved one step further, from {@code Gui} itself onto a separate {@code Gui.hud}
 * object. Every one of those is the same operation under a new owner, so the version
 * difference is resolved here once instead of at each of the twenty-odd call sites.
 *
 * <p>Nothing here caches or holds state: these are pass-throughs to whatever the running
 * version offers, and they are called from the client thread exactly where the vanilla call
 * used to be.
 */
public final class MinecraftCompat {

    private MinecraftCompat() {
    }

    /** The screen currently shown, or null when the game is showing the world. */
    public static @Nullable Screen currentScreen() {
        //? if >=26.2 {
        return Minecraft.getInstance().gui.screen();
        //?} else {
        /*return Minecraft.getInstance().screen;
        *///?}
    }

    /**
     * Shows {@code screen}, or returns to the world when it is null -- the same contract
     * {@code Minecraft.setScreen} has always had.
     */
    public static void openScreen(@Nullable Screen screen) {
        //? if >=26.2 {
        Minecraft.getInstance().gui.setScreen(screen);
        //?} else {
        /*Minecraft.getInstance().setScreen(screen);
        *///?}
    }

    /**
     * Sets the fading message above the hotbar ("剩余 802 米 · 约 2m 27s" and friends).
     *
     * @param animate whether the text pulses as it appears
     */
    public static void overlayMessage(Component message, boolean animate) {
        //? if >=26.2 {
        Minecraft.getInstance().gui.hud.setOverlayMessage(message, animate);
        //?} else {
        /*Minecraft.getInstance().gui.setOverlayMessage(message, animate);
        *///?}
    }
}
