package bili.dongsz.howtogo.item;

import bili.dongsz.howtogo.client.NavigatorClient;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;

import java.util.List;

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
        }
        // InteractionResultHolder was folded into InteractionResult in 1.21.2, and sidedSuccess(stack,
        // isClientSide) -- success with a hand swing on the client, a bare consume on the server -- is
        // now spelled with the two constants below. Neither side needs the stack any more: the record's
        // ItemContext carries it only for items that transform what is held, which this never does.
        return level.isClientSide() ? InteractionResult.SUCCESS : InteractionResult.CONSUME;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip,
                                TooltipFlag flag) {
        // A tooltip is built on the client, but this method is reachable from a server (an item stack can
        // be asked for its lines there), so the side is asked before the readout is touched.
        if (FMLEnvironment.dist == Dist.CLIENT) {
            NavigatorClient.appendTooltip(tooltip);
        }
    }
}
