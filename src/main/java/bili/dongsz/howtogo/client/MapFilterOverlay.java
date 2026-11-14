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
// The left button comes from MouseButton: the number changed at 26.3, and a literal 0 meant no

/**
 * The map's switches, drawn from the screen rather than inside the map.
 * The map's element renderer cannot keep the panel on top because vertex buffers flush in submission order; screen render events draw after the map finishes, and screen mouse events give screen coordinates.
 * Offered only with the world map open and the editor closed -- filtering is reading, not editing.
 * Loader differences live in the four entry points and register(): NeoForge events are global and cancel to claim a click; Fabric events are per-screen and return false to claim, and have no drag event, so the drag is stepped from render.
 */
public final class MapFilterOverlay {

    /** Left mouse button, read from MouseButton because 26.3 changed its number (0 now means no button). */
    private static final int MOUSE_LEFT = MouseButton.LEFT;

    /** Panel grab point, as an offset from the panel corner, while the title is held. */
    private static double grabX;
    private static double grabY;
    private static boolean held;
    private static boolean moved;

    private MapFilterOverlay() {
    }

    // Attaches the panel to each map screen as it is initialised; called once from the client
    //? if fabric && <1.21.9 {
    /*public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!RoadEditHandler.isXaeroScreen(screen)) {
                return;
            }
            ScreenEvents.afterRender(screen).register(MapFilterOverlay::onScreenRender);
            ScreenMouseEvents.allowMouseClick(screen).register(MapFilterOverlay::onMousePressed);
            ScreenMouseEvents.allowMouseRelease(screen).register(MapFilterOverlay::onMouseReleased);
        });
    }
    *///?}
    // The same registration from 1.21.9 on, where mouse callbacks take a MouseButtonEvent instead
    //? if fabric && >=1.21.9 {
    /*public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!RoadEditHandler.isXaeroScreen(screen)) {
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

    /** Whether the panel is offered: the world map without the editor. */
    private static boolean offered(Screen screen) {
        return screen != null && !RoadEditSession.isActive() && RoadEditHandler.isMapScreen(screen);
    }

    /** Draws the panel over the finished screen, so nothing the map drew can cover it. */
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
        // The panel draws in plain screen coordinates, so the GUI matrix starts from identity
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
     * Takes and consumes a press on the panel, switch or title, so the map does not also act: a fallen-through press would place a road or start a drag under the panel.
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
            // The title rolls the panel up and moves it; which a press means is decided on release.
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

    /** A drag with the title held moves the panel; the map is told nothing. */
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

    // NeoForge's drag entry point; Fabric steps the drag from the render callback instead.
    //? if neoforge {
    public static void onMouseDragged(ScreenEvent.MouseDragged.Pre event) {
        if (!held || !offered(event.getScreen())) {
            return;
        }
        stepDrag(event.getScreen(), event.getMouseX(), event.getMouseY());
        event.setCanceled(true);
    }
    //?}

    /** Releasing the title either finishes a move or rolls the panel up. */
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
            // Written when a drag ends, not on every pixel of it.
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
