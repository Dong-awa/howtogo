package bili.dongsz.howtogo.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
//? if fabric {
/*import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
*///?} else {
import net.neoforged.neoforge.client.event.ScreenEvent;
//?}
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
 *
 * <h2>How the two loaders differ, and where</h2>
 * Only in the plumbing, and it is confined to the four entry points below plus {@link #register()}:
 *
 * <ul>
 *   <li>NeoForge's screen events are global and carry everything in an event object; Fabric's are
 *       per-screen, obtained from the screen as it is initialised, and hand the coordinates and the
 *       screen in as arguments. So each entry point is a two-line adapter over a shared body.</li>
 *   <li>NeoForge says "the panel took this click" by cancelling the event; Fabric says it by
 *       refusing the callback, which is a {@code false} return. The adapters translate.</li>
 *   <li>NeoForge has a drag event of its own. Fabric does not, so the drag is stepped from the render
 *       callback instead -- which is why the Fabric render entry point does one thing more than the
 *       NeoForge one.</li>
 * </ul>
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

    // Attaches the panel to each map screen as it is initialised.
    //
    // Called once, from the client initialiser. The map test here is a screen test and nothing more:
    // whether the panel is actually being offered is asked again by offered(Screen) in every callback,
    // because the editor can be opened and closed while the map screen stays up.
    //
    // NeoForge has no equivalent and needs none: its screen events are global, so the handlers are
    // subscribed once at construction and filter by screen themselves.
    //? if fabric && <1.21.9 {
    /*public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!RoadEditHandler.isMapScreen(screen)) {
                return;
            }
            ScreenEvents.afterRender(screen).register(MapFilterOverlay::onScreenRender);
            ScreenMouseEvents.allowMouseClick(screen).register(MapFilterOverlay::onMousePressed);
            ScreenMouseEvents.allowMouseRelease(screen).register(MapFilterOverlay::onMouseReleased);
        });
    }
    *///?}
    // The same registration from 1.21.9 on, where the mouse callbacks stopped taking the
    // coordinates and the button as separate arguments and started taking a MouseButtonEvent.
    // Two whole methods rather than a condition inside one: the anchor node compiles src/ verbatim,
    // so an inner terminator inside this fence would close it early there and leave the rest live.
    //? if fabric && >=1.21.9 {
    /*public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!RoadEditHandler.isMapScreen(screen)) {
                return;
            }
            ScreenEvents.afterRender(screen).register(MapFilterOverlay::onScreenRender);
            ScreenMouseEvents.allowMouseClick(screen).register((open, event) ->
                    onMousePressed(open, event.x(), event.y(), event.button()));
            ScreenMouseEvents.allowMouseRelease(screen).register((open, event) ->
                    onMouseReleased(open, event.x(), event.y(), event.button()));
        });
    }
    *///?}

    /** Whether the panel is being offered at all: the world map, without the editor. */
    private static boolean offered(Screen screen) {
        return screen != null && !RoadEditSession.isActive() && RoadEditHandler.isMapScreen(screen);
    }

    /** Draws the panel over the finished screen, so nothing the map drew can be on top of it. */
    //? if fabric {
    /*public static void onScreenRender(Screen screen, GuiGraphics graphics, int mouseX, int mouseY,
                                      float tickDelta) {
        if (!offered(screen)) {
            return;
        }
        // Stepped here rather than from an event of its own, because Fabric has none for a drag. It
        // runs before the drawing so the panel is already where the frame's cursor says it should be.
        stepDrag(screen, mouseX, mouseY);
        drawPanel(graphics, mouseX, mouseY);
    }
    *///?} else {
    public static void onScreenRender(ScreenEvent.Render.Post event) {
        if (!offered(event.getScreen())) {
            return;
        }
        drawPanel(event.getGuiGraphics(), event.getMouseX(), event.getMouseY());
    }
    //?}

    private static void drawPanel(GuiGraphics graphics, int mouseX, int mouseY) {
        // The panel is drawn in plain screen coordinates, so the GUI matrix starts from identity.
        // Before 1.21.6 that meant clearing the pose and the normal of the top PoseStack entry; from
        // 1.21.6 on the GUI stack is JOML's Matrix3x2fStack, where the same intent is one call --
        // there is no separate normal matrix once the stack is 2D.
        //? if >=1.21.6 {
        /*graphics.pose().pushMatrix();
        graphics.pose().identity();
        MapFilterPanel.draw(graphics, mouseX, mouseY);
        graphics.pose().popMatrix();
        *///?} else {
        graphics.pose().pushPose();
        graphics.pose().last().pose().identity();
        graphics.pose().last().normal().identity();
        MapFilterPanel.draw(graphics, mouseX, mouseY);
        graphics.pose().popPose();
        //?}
    }

    /**
     * Takes a press on the panel, whether it is a switch or the title.
     *
     * <p>Consumed, so the map does not also act on it: a press that fell through the panel would place a
     * road or start a drag under the thing the player was aiming at.
     */
    //? if fabric {
    /*public static boolean onMousePressed(Screen screen, double mouseX, double mouseY, int button) {
        // Inverted: Fabric's allowMouseClick answers "may the screen have this click?", so consuming it
        // is a false.
        return !pressPanel(screen, mouseX, mouseY, button);
    }
    *///?} else {
    public static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        if (pressPanel(event.getScreen(), event.getMouseX(), event.getMouseY(), event.getButton())) {
            event.setCanceled(true);
        }
    }
    //?}

    private static boolean pressPanel(Screen screen, double mouseX, double mouseY, int button) {
        if (button != MOUSE_LEFT || !offered(screen)) {
            return false;
        }
        net.minecraft.client.gui.Font font = Minecraft.getInstance().font;
        if (!MapFilterPanel.covers(font, mouseX, mouseY)) {
            return false;
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
        return true;
    }

    /** A drag with the title held moves the panel, and the map is told nothing about it. */
    private static void stepDrag(Screen screen, double mouseX, double mouseY) {
        if (!held || !offered(screen)) {
            return;
        }
        if (Math.abs(mouseX - (MapFilter.panelX() - grabX)) > 2
                || Math.abs(mouseY - (MapFilter.panelY() - grabY)) > 2) {
            moved = true;
        }
        Minecraft minecraft = Minecraft.getInstance();
        int[] bounds = MapFilterPanel.bounds(minecraft.font);
        com.mojang.blaze3d.platform.Window window = minecraft.getWindow();
        MapFilter.movePanel((int) Math.round(mouseX + grabX), (int) Math.round(mouseY + grabY),
                window.getGuiScaledWidth(), window.getGuiScaledHeight(), bounds[2], bounds[3]);
    }

    // NeoForge's own drag entry point. Fabric has no counterpart to declare: it steps the drag from the
    // render callback, which is why that one does an extra thing.
    //? if neoforge {
    public static void onMouseDragged(ScreenEvent.MouseDragged.Pre event) {
        if (!held || !offered(event.getScreen())) {
            return;
        }
        stepDrag(event.getScreen(), event.getMouseX(), event.getMouseY());
        event.setCanceled(true);
    }
    //?}

    /** Letting the title go either finishes a move or rolls the panel up. */
    //? if fabric {
    /*public static boolean onMouseReleased(Screen screen, double mouseX, double mouseY, int button) {
        return !releasePanel(mouseX, mouseY, button);
    }
    *///?} else {
    public static void onMouseReleased(ScreenEvent.MouseButtonReleased.Pre event) {
        if (releasePanel(event.getMouseX(), event.getMouseY(), event.getButton())) {
            event.setCanceled(true);
        }
    }
    //?}

    private static boolean releasePanel(double mouseX, double mouseY, int button) {
        if (!held || button != MOUSE_LEFT) {
            return false;
        }
        held = false;
        if (moved) {
            // Written when the drag ends rather than on every pixel of it.
            MapFilter.keepPanelPosition();
            return true;
        }
        MapFilterPanel.Toggle title = MapFilterPanel.titleToggle(Minecraft.getInstance().font,
                mouseX, mouseY);
        if (title != null) {
            title.flip().run();
        }
        return true;
    }
}
