package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.road.RoadSegment;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.lwjgl.glfw.GLFW;

/**
 * Mouse and keyboard control for the road editor.
 *
 * <h2>Why the keys come through the screen event and the mouse does not</h2>
 * The two input paths are not symmetric in this NeoForge build, and the asymmetry is the whole
 * reason the editor's keys stopped working while its mouse kept going. Read out of the patched
 * sources:
 * <ul>
 *   <li>{@code KeyboardHandler.keyPress} offers the key to the open screen <b>first</b>
 *       ({@code onScreenKeyPressedPre}, then {@code screen.keyPressed}) and returns right there if the
 *       screen took it, posting {@code InputEvent.Key} only afterwards. Xaero's map consumes the keys
 *       it uses, so an {@code InputEvent.Key} listener never sees them: the R press was being eaten by
 *       the map screen before NeoForge's key event existed at all, which no gate could fix.</li>
 *   <li>{@code MouseHandler.onPress} posts {@code InputEvent.MouseButton} <b>before</b> any screen
 *       dispatch, so clicks arrive whether the screen wants them or not. That is why editing by mouse
 *       always worked.</li>
 * </ul>
 * So keys are handled from {@link ScreenEvent.KeyPressed.Pre} and {@link ScreenEvent.KeyReleased.Pre},
 * which fire before the screen sees the key and are indifferent to whether it would consume it, and
 * the key is cancelled only when the editor actually uses it -- otherwise Xaero would lose its own
 * shortcuts. There is deliberately no {@code InputEvent.Key} listener beside these: a key the map
 * happens not to consume would then be handled twice, and R would toggle on and off again.
 *
 * <h2>Controls</h2>
 * <ul>
 *   <li><b>R</b> - toggle road editing (also rebindable under Controls)</li>
 *   <li><b>Left click</b> - place a point, extending the road being drawn</li>
 *   <li><b>Alt + left click</b> - place a point exactly where the cursor is, snapping disabled</li>
 *   <li><b>Shift + left click / drag</b> - grab and move a node, or select a road</li>
 *   <li><b>Ctrl + left click</b> - set the current cursor spot as the navigation destination
 *       (works whether or not editing is on). This is the only way to start navigating from the map;
 *       there used to be a bare {@code G} as well, and it was dropped because a single unmodified
 *       letter on the map is too easy to press by accident and the map already owns most of them.</li>
 *   <li><b>Right click</b> - finish the current road, or clear the selection</li>
 *   <li><b>&lt; / &gt;</b> (comma / period) - change the road class being drawn, or of the selection</li>
 *   <li><b>N</b> - name the selected road, or the road under the cursor</li>
 *   <li><b>Delete</b> - delete the selection</li>
 *   <li><b>Ctrl+Z / Ctrl+Y</b> - undo / redo</li>
 * </ul>
 */
public final class RoadEditHandler {

    public static final String KEY_CATEGORY = "category.howtogo";

    public static final KeyMapping TOGGLE_EDIT = new KeyMapping(
            "key.howtogo.toggle_edit",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_R,
            KEY_CATEGORY);

    /**
     * Opens the line editor.
     *
     * <p>A registered mapping rather than a key code compared in the handler, so that it appears in
     * the controls list and can be rebound. A key that exists only in the code is a key nobody can
     * find, which is what the first version of this was.
     */
    public static final KeyMapping LINES = new KeyMapping(
            "key.howtogo.lines",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_L,
            KEY_CATEGORY);

    private RoadEditHandler() {
    }

    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(TOGGLE_EDIT);
        event.register(LINES);
    }

    // ------------------------------------------------------------------ mouse

    public static void onMouseButton(InputEvent.MouseButton.Pre event) {
        Screen screen = Minecraft.getInstance().screen;
        boolean gate = isMapOpen(screen);
        if (!gate) {
            return;
        }
        if (event.getAction() != GLFW.GLFW_PRESS) {
            return;
        }

        // Picking a destination works regardless of the editor being open, the way tapping a map
        // does in a navigation app.
        if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_LEFT && Screen.hasControlDown()) {
            safely("pick destination on map", RoadEditSession::navigateToCursor);
            event.setCanceled(true);
            return;
        }

        // The map's own switches, in its ordinary mode where they are drawn. Asked before anything else
        // that could act on the click: a press that fell through the panel would move the map or place a
        // road under the thing the player was aiming at.
        if (!RoadEditSession.isActive()
                && event.getButton() == GLFW.GLFW_MOUSE_BUTTON_LEFT
                && handlesFilterPanel()) {
            event.setCanceled(true);
            return;
        }
        if (!RoadEditSession.isActive()) {
            return;
        }

        switch (event.getButton()) {
            case GLFW.GLFW_MOUSE_BUTTON_LEFT -> {
                if (Screen.hasShiftDown()) {
                    safely("begin drag", RoadEditSession::beginDragAtCursor);
                } else {
                    safely("place point", RoadEditSession::clickPlace);
                }
                event.setCanceled(true);
            }
            case GLFW.GLFW_MOUSE_BUTTON_RIGHT -> {
                safely("finish road", RoadEditSession::clickFinishOrClear);
                event.setCanceled(true);
            }
            default -> {
                // Middle click and friends stay with Xaero.
            }
        }
    }

    /**
     * Presses one of the map panel's switches, or takes hold of its title to move it.
     *
     * <p>The boxes come from the panel itself, so the switch that is flipped is the one that was drawn
     * under the cursor rather than one worked out a second time here.
     *
     * <p>The title does two things, and which one a press means is decided when it is let go: a press and
     * release without moving rolls the panel up, and a press that moves drags it. Deciding on the press
     * would make one of the two impossible.
     *
     * @return whether the press was the panel's business, in which case the map must not also see it
     */
    private static boolean handlesFilterPanel() {
        Minecraft minecraft = Minecraft.getInstance();
        com.mojang.blaze3d.platform.Window window = minecraft.getWindow();
        if (window == null) {
            return false;
        }
        double mouseX = cursorX(window);
        double mouseY = cursorY(window);
        net.minecraft.client.gui.Font font = minecraft.font;
        if (!MapFilterPanel.covers(font, mouseX, mouseY)) {
            return false;
        }
        MapFilterPanel.Toggle title = MapFilterPanel.titleToggle(font, mouseX, mouseY);
        if (title != null) {
            panelGrabX = MapFilter.panelX() - mouseX;
            panelGrabY = MapFilter.panelY() - mouseY;
            panelHeld = true;
            panelMoved = false;
            return true;
        }
        MapFilterPanel.Toggle pressed = MapFilterPanel.pressed(font, mouseX, mouseY);
        if (pressed != null) {
            safely("map filter", pressed::flip);
        }
        // Cancelled either way: the panel is opaque, and a press inside it is not a press on the map.
        return true;
    }

    /**
     * A drag while the panel's title is held moves the panel.
     *
     * <p>The map pans on a drag, so the event is cancelled while the panel owns the press: a panel that
     * moved and took the map with it would be a panel nobody could place.
     */
    public static void onMouseDragged(ScreenEvent.MouseDragged.Pre event) {
        if (!panelHeld) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        com.mojang.blaze3d.platform.Window window = minecraft.getWindow();
        if (window == null) {
            return;
        }
        double mouseX = cursorX(window);
        double mouseY = cursorY(window);
        if (Math.abs(mouseX - (MapFilter.panelX() - panelGrabX)) > 2
                || Math.abs(mouseY - (MapFilter.panelY() - panelGrabY)) > 2) {
            panelMoved = true;
        }
        int[] bounds = MapFilterPanel.bounds(minecraft.font);
        MapFilter.movePanel((int) Math.round(mouseX + panelGrabX),
                (int) Math.round(mouseY + panelGrabY),
                window.getGuiScaledWidth(), window.getGuiScaledHeight(), bounds[2], bounds[3]);
        event.setCanceled(true);
    }

    /** The cursor in the same pixels the panel is drawn in. */
    private static double cursorX(com.mojang.blaze3d.platform.Window window) {
        return Minecraft.getInstance().mouseHandler.xpos()
                * window.getGuiScaledWidth() / window.getScreenWidth();
    }

    /** The same for the other axis. */
    private static double cursorY(com.mojang.blaze3d.platform.Window window) {
        return Minecraft.getInstance().mouseHandler.ypos()
                * window.getGuiScaledHeight() / window.getScreenHeight();
    }

    public static void onMouseButtonReleased(InputEvent.MouseButton.Post event) {
        if (event.getAction() != GLFW.GLFW_RELEASE) {
            return;
        }
        if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            if (panelHeld) {
                panelHeld = false;
                if (panelMoved) {
                    // Written when the drag ends rather than on every pixel of it.
                    MapFilter.keepPanelPosition();
                } else {
                    MapFilterPanel.Toggle title = MapFilterPanel.titleToggle(
                            Minecraft.getInstance().font, cursorX(Minecraft.getInstance().getWindow()),
                            cursorY(Minecraft.getInstance().getWindow()));
                    if (title != null) {
                        safely("map filter", title::flip);
                    }
                }
                return;
            }
            if (RoadEditSession.draggingNodeId() != RoadSegment.NO_NODE) {
                RoadEditSession.endDrag();
            }
        }
    }

    /** Whether the panel's title is being held, and whether it has been moved since it was. */
    private static boolean panelHeld;
    private static boolean panelMoved;
    private static double panelGrabX;
    private static double panelGrabY;

    // --------------------------------------------------------------- keyboard

    /**
     * A key was pressed while some screen is open: the editor's only way in.
     *
     * <p>Cancels the event only when the editor uses the key, so the map screen keeps every shortcut
     * the editor does not claim.
     */
    public static void onScreenKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        Screen screen = event.getScreen();
        boolean gate = isMapOpen(screen);
        boolean consumed = gate
                && handleKey(screen, event.getKeyCode(), event.getScanCode(), GLFW.GLFW_PRESS);
        if (consumed) {
            event.setCanceled(true);
        }
    }

    /** A key was released: only a shift release matters, and it ends a drag. */
    public static void onScreenKeyReleased(ScreenEvent.KeyReleased.Pre event) {
        Screen screen = event.getScreen();
        boolean gate = isMapOpen(screen);
        boolean consumed = gate
                && handleKey(screen, event.getKeyCode(), event.getScanCode(), GLFW.GLFW_RELEASE);
        if (consumed) {
            event.setCanceled(true);
        }
    }

    /**
     * Runs one key against the editor.
     *
     * @return whether the editor used it, which is what decides if the screen is allowed to see it
     */
    private static boolean handleKey(Screen screen, int key, int scanCode, int action) {
        if (key == GLFW.GLFW_KEY_LEFT_SHIFT || key == GLFW.GLFW_KEY_RIGHT_SHIFT) {
            if (action == GLFW.GLFW_RELEASE) {
                RoadEditSession.endDrag();
                return true;
            }
            return false;
        }

        if (action != GLFW.GLFW_PRESS) {
            return false;
        }

        if (TOGGLE_EDIT.matches(key, scanCode)) {
            RoadEditSession.toggle();
            HowToGo.LOGGER.info("[HowToGo] road editing {}",
                    RoadEditSession.isActive() ? "enabled" : "disabled");
            return true;
        }
        if (!RoadEditSession.isActive()) {
            return false;
        }

        boolean ctrl = Screen.hasControlDown();
        if (ctrl && key == GLFW.GLFW_KEY_Z) {
            safely("undo", RoadEditSession::undo);
        } else if (ctrl && key == GLFW.GLFW_KEY_Y) {
            safely("redo", RoadEditSession::redo);
        } else if (key == GLFW.GLFW_KEY_DELETE || key == GLFW.GLFW_KEY_BACKSPACE) {
            safely("delete", RoadEditSession::deleteSelected);
        } else if (key == GLFW.GLFW_KEY_COMMA) {
            // '<' on most layouts; '[' and ']' are taken by Xaero's own map shortcuts.
            safely("change class", () -> RoadEditSession.cycleActiveClass(-1));
        } else if (key == GLFW.GLFW_KEY_PERIOD) {
            // '>'
            safely("change class", () -> RoadEditSession.cycleActiveClass(1));
        } else if (key == GLFW.GLFW_KEY_N) {
            safely("name road", RoadEditSession::nameSelectedRoad);
        } else if (key == GLFW.GLFW_KEY_P) {
            safely("place landmark", RoadEditSession::placePoi);
        } else if (LINES.matches(key, scanCode)) {
            // The lines are a property of the network rather than of whatever is selected, so this
            // opens on the map itself and needs no selection first. Deferred to the next tick, like
            // every other screen here: opening one from the input handler is what makes a key look
            // dead.
            safely("edit lines", RoadEditSession::promptLineEditor);
        } else {
            return false;
        }
        return true;
    }

    /**
     * Runs an interaction, logging instead of propagating.
     *
     * <p>These handlers run deep inside the input dispatch chain, so an escaping exception takes
     * the whole game down -- a bug in the editor should cost the player a log line, not their
     * session.
     */
    private static void safely(String what, Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            HowToGo.LOGGER.error("[HowToGo] '{}' failed", what, t);
        }
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Whether the player is looking at Xaero's world map, which is the only place the editor is
     * offered.
     *
     * <h2>Why this is not a list of class names</h2>
     * It used to be: the screen's superclass chain had to contain exactly {@code xaero.map.gui.GuiMap}.
     * That is a name, and a name is the one thing an update to Xaero is free to change -- the map
     * simply stopped being recognised, and with it every editor key. The test is now built on two
     * pieces of evidence that do not depend on a name:
     * <ol>
     *   <li>the screen, or something it extends, is declared in the World Map's own packages
     *       ({@code xaero.map.*}). This is a whole package rather than one class, so a renamed or
     *       newly subclassed map screen is still recognised, while the Minimap's own screens
     *       ({@code xaero.common.*}) are not;</li>
     *   <li>failing that, the screen is one of Xaero's and <b>Xaero is drawing the map behind it right
     *       now</b> -- {@link MapViewState} is only updated from the map's render pass, so a fresh
     *       reading is direct evidence that the map is on screen, whatever its class is called.</li>
     * </ol>
     *
     * <p>Two things keep this from opening the gate where it should not. With no screen open it is
     * false, so nothing here works in the world -- as nothing ever did. And a screen whose focused
     * widget is a text field is false too, so a letter typed into a name or search box is text before
     * it is a shortcut, whether that field is ours or someone else's.
     */
    private static boolean isMapOpen(Screen screen) {
        if (screen == null || isTyping(screen)) {
            return false;
        }
        if (isInPackage(screen, "xaero.map.")) {
            return true;
        }
        return isInPackage(screen, "xaero.") && MapViewState.isFresh();
    }

    /** Whether the screen, or anything it extends, is declared in the given package. */
    private static boolean isInPackage(Screen screen, String prefix) {
        for (Class<?> c = screen.getClass(); c != null; c = c.getSuperclass()) {
            String name = c.getName();
            int lastDot = name.lastIndexOf('.');
            if (lastDot > 0 && name.substring(0, lastDot + 1).startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a text field has the focus, so the next letter is text rather than a shortcut.
     *
     * <p>Asked of the screen's own focus rather than of the screen's class, because that is the fact
     * that matters: every field the game builds reports itself here, ours and Xaero's alike.
     */
    private static boolean isTyping(Screen screen) {
        return screen.getFocused() instanceof EditBox;
    }

}
