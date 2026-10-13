package bili.dongsz.howtogo.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.neoforge.client.event.ScreenEvent;
// 1.21.9 reads mouse buttons from the SDL-backed platform layer, so the GLFW constants moved to
// InputConstants. Same values.
//? if >=1.21.9 {
/*import com.mojang.blaze3d.platform.InputConstants;
*///?} else {
import org.lwjgl.glfw.GLFW;
//?}

/**
 * The map's switches, drawn as part of the map screen rather than inside the map.
 *
 * <h2>Why this is a screen overlay and not a map element</h2>
 * It was drawn from the map's element renderer, at the end of the last element, and it came out under
 * the map anyway: the lines, the markers and the names are written through the element renderer's own
 * vertex buffers, which the game flushes when it flushes them, and no amount of drawing the panel later
 * inside that pass changes which buffer reaches the screen first. The panel belongs to the screen, so it
 * is drawn from the screen's own render event -- after the map has finished, whatever the map did -- and
 * its clicks come from the screen's mouse events, where the coordinates are the screen's own rather than
 * something recomputed from the window.
 *
 * <h2>When it is offered</h2>
 * With the world map open and the editor closed: hiding a kind of road is a way of reading the map, not a
 * way of changing it. See {@link MapFilter} for what the switches mean.
 */
public final class MapFilterOverlay {

    /**
     * The left mouse button.
     *
     * <p>Named once here because the constant it stands for moved: 1.21.9 reads mouse buttons from the
     * SDL-backed platform layer, where it is {@code InputConstants.MOUSE_BUTTON_LEFT}, and before that
     * it was {@code GLFW.GLFW_MOUSE_BUTTON_LEFT}. Both are the same number, and the two tests below
     * are the only uses, so the condition sits here rather than at each.
     */
    //? if >=1.21.9 {
    /*private static final int MOUSE_LEFT = com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT;
    *///?} else {
    private static final int MOUSE_LEFT = GLFW.GLFW_MOUSE_BUTTON_LEFT;
    //?}

    /** Where the panel was grabbed, as an offset from its corner, while its title is held. */
    private static double grabX;
    private static double grabY;
    private static boolean held;
    private static boolean moved;

    private MapFilterOverlay() {
    }

    /** Whether the panel is being offered at all: the world map, without the editor. */
    private static boolean offered(Screen screen) {
        return screen != null && !RoadEditSession.isActive() && RoadEditHandler.isMapScreen(screen);
    }

    /** Draws the panel over the finished screen, so nothing the map drew can be on top of it. */
    public static void onScreenRender(ScreenEvent.Render.Post event) {
        if (!offered(event.getScreen())) {
            return;
        }
        GuiGraphics graphics = event.getGuiGraphics();
        // The panel is drawn in plain screen coordinates, so the GUI matrix starts from identity.
        // Before 1.21.6 that meant clearing the pose and the normal of the top PoseStack entry; from
        // 1.21.6 on the GUI stack is JOML's Matrix3x2fStack, where the same intent is one call --
        // there is no separate normal matrix once the stack is 2D.
        //? if >=1.21.6 {
        /*graphics.pose().pushMatrix();
        graphics.pose().identity();
        MapFilterPanel.draw(graphics, event.getMouseX(), event.getMouseY());
        graphics.pose().popMatrix();
        *///?} else {
        graphics.pose().pushPose();
        graphics.pose().last().pose().identity();
        graphics.pose().last().normal().identity();
        MapFilterPanel.draw(graphics, event.getMouseX(), event.getMouseY());
        graphics.pose().popPose();
        //?}
    }

    /**
     * Takes a press on the panel, whether it is a switch or the title.
     *
     * <p>Cancelled so the map does not also act on it: a press that fell through the panel would place a
     * road or start a drag under the thing the player was aiming at.
     */
    public static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        if (event.getButton() != MOUSE_LEFT || !offered(event.getScreen())) {
            return;
        }
        double mouseX = event.getMouseX();
        double mouseY = event.getMouseY();
        net.minecraft.client.gui.Font font = Minecraft.getInstance().font;
        if (!MapFilterPanel.covers(font, mouseX, mouseY)) {
            return;
        }
        MapFilterPanel.Toggle title = MapFilterPanel.titleToggle(font, mouseX, mouseY);
        if (title != null) {
            // The title both rolls the panel up and moves it, and which one a press means is decided
            // when it is let go: a press and release without moving rolls it up, a press that moves
            // drags it. Deciding on the press would make one of the two impossible.
            grabX = MapFilter.panelX() - mouseX;
            grabY = MapFilter.panelY() - mouseY;
            held = true;
            moved = false;
        } else {
            MapFilterPanel.Toggle pressed = MapFilterPanel.pressed(font, mouseX, mouseY);
            if (pressed != null) {
                pressed.flip().run();
            }
        }
        event.setCanceled(true);
    }

    /** A drag with the title held moves the panel, and the map is told nothing about it. */
    public static void onMouseDragged(ScreenEvent.MouseDragged.Pre event) {
        if (!held || !offered(event.getScreen())) {
            return;
        }
        double mouseX = event.getMouseX();
        double mouseY = event.getMouseY();
        if (Math.abs(mouseX - (MapFilter.panelX() - grabX)) > 2
                || Math.abs(mouseY - (MapFilter.panelY() - grabY)) > 2) {
            moved = true;
        }
        Minecraft minecraft = Minecraft.getInstance();
        int[] bounds = MapFilterPanel.bounds(minecraft.font);
        com.mojang.blaze3d.platform.Window window = minecraft.getWindow();
        MapFilter.movePanel((int) Math.round(mouseX + grabX), (int) Math.round(mouseY + grabY),
                window.getGuiScaledWidth(), window.getGuiScaledHeight(), bounds[2], bounds[3]);
        event.setCanceled(true);
    }

    /** Letting the title go either finishes a move or rolls the panel up. */
    public static void onMouseReleased(ScreenEvent.MouseButtonReleased.Pre event) {
        if (!held || event.getButton() != MOUSE_LEFT) {
            return;
        }
        held = false;
        if (moved) {
            // Written when the drag ends rather than on every pixel of it.
            MapFilter.keepPanelPosition();
            event.setCanceled(true);
            return;
        }
        MapFilterPanel.Toggle title = MapFilterPanel.titleToggle(Minecraft.getInstance().font,
                event.getMouseX(), event.getMouseY());
        if (title != null) {
            title.flip().run();
        }
        event.setCanceled(true);
    }
}
