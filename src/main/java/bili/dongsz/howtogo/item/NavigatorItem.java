package bili.dongsz.howtogo.item;

import bili.dongsz.howtogo.client.DestinationScreen;
import bili.dongsz.howtogo.client.Navigation;
import bili.dongsz.howtogo.route.Destination;
import bili.dongsz.howtogo.route.Route;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * Right-clicking opens the destination picker; the tooltip reports the trip currently being
 * navigated.
 */
public final class NavigatorItem extends Item {

    public NavigatorItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            Minecraft.getInstance().setScreen(new DestinationScreen(Minecraft.getInstance().screen));
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip,
                                TooltipFlag flag) {
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
