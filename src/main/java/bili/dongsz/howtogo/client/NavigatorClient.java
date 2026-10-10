package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.route.Destination;
import bili.dongsz.howtogo.route.Route;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * What the navigator item does when there is a client to do it on.
 *
 * <h2>Why this is a class of its own</h2>
 * The item is registered on <b>both</b> sides, and it has to be: a recipe is a data pack file, data
 * packs are read on a dedicated server as well, and a recipe for an item the server has never heard of
 * is a parse error in its log -- worse, in multiplayer the server owns the inventory, so an item only
 * the client knows about cannot be crafted or held at all.
 *
 * <p>That puts {@code NavigatorItem} on the server, where it must not reach for anything that only
 * exists on a client: no {@code Minecraft}, no screens, no navigation readout. Those live here, and the
 * item calls in only after asking which side it is on -- the answer is asked of the level or of
 * {@link net.fabricmc.loader.api.FabricLoader}, never by assuming.
 */
public final class NavigatorClient {

    private NavigatorClient() {
    }

    /** Opens the destination picker over whatever screen is open -- the world map, usually. */
    public static void openPicker() {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.setScreen(new DestinationScreen(minecraft.screen));
    }

    /** The item's tooltip: the trip being navigated, or an invitation to pick one. */
    public static void appendTooltip(List<Component> tooltip) {
        Destination target = Navigation.target();
        if (target == null) {
            tooltip.add(Component.translatable("tooltip.howtogo.navigator.idle")
                    .withStyle(ChatFormatting.GRAY));
            return;
        }
        tooltip.add(Component.translatable("tooltip.howtogo.navigator.target", target.name())
                .withStyle(ChatFormatting.AQUA));
        tooltip.add(Component.translatable("tooltip.howtogo.navigator.remaining",
                        Route.formatDistance(Navigation.remainingLength()),
                        Navigation.modeLabel(),
                        Route.formatDuration(Navigation.remainingSeconds()))
                .withStyle(ChatFormatting.GRAY));
    }
}
