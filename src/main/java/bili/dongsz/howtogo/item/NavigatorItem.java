package bili.dongsz.howtogo.item;

import bili.dongsz.howtogo.client.NavigatorClient;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;

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
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        // Asked of the level rather than of anything global, because use() runs on both sides: the server
        // calls it to decide what happened, the client calls it to open the screen.
        if (level.isClientSide()) {
            NavigatorClient.openPicker();
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    /**
     * The tooltip, built on the client.
     *
     * <p>Signature note: 1.20.5 replaced the {@code Level} parameter with a {@code TooltipContext}, so
     * the author's original is the newer shape. On 1.20.1 the override takes the level -- and it was
     * never read here, so this is a signature change and nothing else.
     */
    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip,
                                TooltipFlag flag) {
        // A tooltip is built on the client, but this method is reachable from a server (an item stack can
        // be asked for its lines there), so the side is asked before the readout is touched.
        if (FMLEnvironment.dist == Dist.CLIENT) {
            NavigatorClient.appendTooltip(tooltip);
        }
    }
}
