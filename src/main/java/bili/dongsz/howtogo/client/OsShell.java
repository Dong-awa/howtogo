package bili.dongsz.howtogo.client;

import java.nio.file.Path;

/**
 * Handing a path or a URL to the operating system, in the shape each node wants.
 *
 * <h2>Why this is its own class</h2>
 * This is the one place in the mod where the answer changes on two axes the tree deals in, and the
 * two boundaries do not line up with any of the ones established elsewhere:
 *
 * <ul>
 *   <li><b>1.20.x vs 1.21.1</b> -- {@code Util.OS.openPath} does not exist on 1.20.4 or 1.20.6.
 *       Those releases have {@code openFile}, {@code openUri} and {@code openUrl}, which is why
 *       opening a directory is a different call there rather than a differently spelled one.
 *   <li><b>1.21.11 vs 26.3</b> -- 1.21.11 moved the class from {@code net.minecraft.Util} to
 *       {@code net.minecraft.util.Util} and left the platform accessor alone, so 1.21.11 through
 *       26.2 spell the same call with a different package. 26.3 dropped the accessor: both calls
 *       went to {@code com.mojang.blaze3d.Blaze3D}, as {@code openPath(Path)} and
 *       {@code openUri(URI)}.
 * </ul>
 *
 * <p>Every boundary here was read out of each node's own merged jar rather than inferred from the
 * version number. Two of them would have been guessed wrong from the version alone: the package move
 * is at 1.21.11 and not at 26.1, and the Blaze3D spelling arrives at 26.3 and not at 26.1.
 *
 * <p>Written here rather than as a condition at each call site for the reason {@code GamePaths} and
 * {@code Screens} give: a {@code //?} marker is a line comment, so a condition mid-expression
 * comments out the rest of the line. A method body has room for the marker on a line of its own.
 *
 * <p>Failures are the caller's to report: one of these is a button that should log and carry on, the
 * other is a chat command that says so in chat.
 */
public final class OsShell {

    private OsShell() {
    }

    /**
     * Opens a folder in the platform's file manager.
     *
     * <p>The caller creates the directory first; this only opens what is there.
     */
    public static void openFolder(Path folder) {
        //? if >=26.3 {
        /*com.mojang.blaze3d.Blaze3D.openPath(folder);
        *///?}
        //? if >=1.21.11 && <26.3 {
        /*net.minecraft.util.Util.getPlatform().openPath(folder);
        *///?}
        //? if >=1.21.1 && <1.21.11 {
        net.minecraft.Util.getPlatform().openPath(folder);
        //?}
        // 1.20.4 and 1.20.6 have no openPath, so the directory goes through openFile, which takes a
        // File and is the same hand-off to the desktop.
        //? if <1.21.1 {
        /*net.minecraft.Util.getPlatform().openFile(folder.toFile());
        *///?}
    }

    /** Opens a URL in the player's default browser. */
    public static void openUrl(String url) {
        //? if >=26.3 {
        /*com.mojang.blaze3d.Blaze3D.openUri(java.net.URI.create(url));
        *///?}
        //? if >=1.21.11 && <26.3 {
        /*net.minecraft.util.Util.getPlatform().openUri(url);
        *///?}
        //? if <1.21.11 {
        net.minecraft.Util.getPlatform().openUri(url);
        //?}
    }
}
