package bili.dongsz.howtogo.item;

import bili.dongsz.howtogo.client.NavigatorClient;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;

import java.util.List;
import java.util.function.Consumer;

/**
 * Right-clicking opens the destination picker; the tooltip reports the trip currently being
 * navigated.
 *
 * <h2>This item exists on both sides</h2>
 * It is registered on a dedicated server as well as on a client, because a recipe is a data pack file
 * and data packs are read on both: a recipe naming an item the server has never heard of is a parse
 * error in its log, and in multiplayer the server owns the inventory, so an item only the client knew
 * about could not be crafted, held or dropped. The <em>behaviour</em> is still client-side, and the two
 * methods below are the whole of the seam -- each asks which side it is on before touching anything
 * that only exists on a client (see {@link NavigatorClient}).
 */
public final class NavigatorItem extends Item {

    public NavigatorItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        // Asked of the level rather than of anything global, because use() runs on both sides: the server
        // calls it to decide what happened, the client calls it to open the screen.
        if (level.isClientSide()) {
            NavigatorClient.openPicker();
            // 26.x folded InteractionResultHolder away: success is a constant now, and the client side
            // is the swing the player sees. The old sidedSuccess(stack, isClientSide) said the same
            // thing -- client success swings the hand, server success marks the action done.
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.SUCCESS_SERVER;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
                                Consumer<Component> builder, TooltipFlag flag) {
        // A tooltip is built on the client, but this method is reachable from a server (an item stack can
        // be asked for its lines there), so the side is asked before the readout is touched. The lines
        // still come back as a list -- the client half of this mod speaks that shape -- and are handed
        // to the builder one at a time: 26.x replaced the gathered List<Component> with this callback.
        if (FMLEnvironment.getDist() == Dist.CLIENT) {
            List<Component> lines = new java.util.ArrayList<>();
            NavigatorClient.appendTooltip(lines);
            for (Component line : lines) {
                builder.accept(line);
            }
        }
    }
}
