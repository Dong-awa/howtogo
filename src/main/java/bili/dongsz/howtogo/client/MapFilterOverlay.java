package bili.dongsz.howtogo.client;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;

/**
 * The map's switches, drawn as part of the map screen rather than inside the map.
 *
 * <h2>Why this is a screen overlay and not a map element</h2>
 * It was drawn from the map's element renderer, at the end of the last element, and it came out under
 * the map anyway: the lines, the markers and the names are written through the element renderer's own
 * vertex buffers, which the game flushes when it flushes them, and no amount of drawing the panel later
 * inside that pass changes which buffer reaches the screen first. The panel belongs to the screen, so it
 * is drawn from the screen's own render callback -- after the map has finished, whatever the map did --
 * and its clicks come from the screen's own mouse callbacks, where the coordinates are the screen's own
 * rather than something recomputed from the window.
 *
 * <h2>How the screen callbacks are attached</h2>
 * Fabric's screen events are per screen instance rather than global: they are obtained from the screen
 * itself and live only as long as it does. So {@link #register()} listens once for every screen the game
 * initialises, and attaches the render and mouse callbacks below to the map screens alone. A screen that
 * is re-initialised -- on a window resize, say -- gets a fresh set.
 *
 * <h2>When it is offered</h2>
 * With the world map open and the editor closed: hiding a kind of road is a way of reading the map, not a
 * way of changing it. See {@link MapFilter} for what the switches mean.
 */
public final class MapFilterOverlay {

    /** Where the panel was grabbed, as an offset from its corner, while its title is held. */
    private static double grabX;
    private static double grabY;
    private static boolean held;
    private static boolean moved;

    private MapFilterOverlay() {
    }

    /**
     * Attaches the panel to each map screen as it is initialised.
     *
     * <p>Called once, from the client initialiser. The map test here is a screen test and nothing more:
     * whether the panel is actually being offered is asked again by {@link #offered(Screen)} in every
     * callback below, because the editor can be opened and closed while the map screen stays up.
     */
    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!RoadEditHandler.isMapScreen(screen)) {
                return;
            }
            ScreenEvents.afterRender(screen).register(MapFilterOverlay::onScreenRender);
            ScreenMouseEvents.allowMouseClick(screen).register(MapFilterOverlay::onMousePressed);
            ScreenMouseEvents.allowMouseRelease(screen).register(MapFilterOverlay::onMouseReleased);
        });
    }

    /** Whether the panel is being offered at all: the world map, without the editor. */
    private static boolean offered(Screen screen) {
        return screen != null && !RoadEditSession.isActive() && RoadEditHandler.isMapScreen(screen);
    }

    /** Draws the panel over the finished screen, so nothing the map drew can be on top of it. */
    public static void onScreenRender(Screen screen, GuiGraphics graphics, int mouseX, int mouseY,
                                      float tickDelta) {
        if (!offered(screen)) {
            return;
        }
        // The drag is stepped from here rather than from an event of its own; see onMouseDragged. It runs
        // before the drawing so that the panel is already where the frame's cursor says it should be.
        onMouseDragged(screen, mouseX, mouseY);
        graphics.pose().pushPose();
        graphics.pose().last().pose().identity();
        graphics.pose().last().normal().identity();
        MapFilterPanel.draw(graphics, mouseX, mouseY);
        graphics.pose().popPose();
    }

    /**
     * Takes a press on the panel, whether it is a switch or the title.
     *
     * <p>Refused -- by returning {@code false}, the Fabric equivalent of cancelling the event -- so the
     * map does not also act on it: a press that fell through the panel would place a road or start a drag
     * under the thing the player was aiming at.
     */
    public static boolean onMousePressed(Screen screen, double mouseX, double mouseY, int button) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT || !offered(screen)) {
            return true;
        }
        net.minecraft.client.gui.Font font = Minecraft.getInstance().font;
        if (!MapFilterPanel.covers(font, mouseX, mouseY)) {
            return true;
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
        return false;
    }

    /**
     * A drag with the title held moves the panel.
     *
     * <p>This one is not a screen callback. Fabric's screen mouse events cover the press, the release and
     * the scroll in this version but not the drag, so there is no callback to refuse, and nothing here
     * can keep a drag from reaching the map as well. It is therefore stepped from
     * {@link #onScreenRender} with the frame's own cursor position, which is the same position the panel
     * is drawn at. The press that began the drag was refused, so the map never saw that gesture begin,
     * and this class passes nothing about the move on to it.
     */
    private static void onMouseDragged(Screen screen, double mouseX, double mouseY) {
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

    /**
     * Letting the title go either finishes a move or rolls the panel up.
     *
     * <p>Refused by returning {@code false} for the same reason as the press: the release belongs to the
     * panel, not to the map.
     */
    public static boolean onMouseReleased(Screen screen, double mouseX, double mouseY, int button) {
        if (!held || button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return true;
        }
        held = false;
        if (moved) {
            // Written when the drag ends rather than on every pixel of it.
            MapFilter.keepPanelPosition();
            return false;
        }
        MapFilterPanel.Toggle title = MapFilterPanel.titleToggle(Minecraft.getInstance().font,
                mouseX, mouseY);
        if (title != null) {
            title.flip().run();
        }
        return false;
    }
}
