package bili.dongsz.howtogo.client;

import java.nio.file.Path;

/**
 * Handing a path or a URL to the operating system, in the shape each node wants.
 *
 * <h2>Why this is its own class</h2>
 * This is the one place in the mod where the answer differs on two of the axes the tree deals in, and
 * the two boundaries are not the same:
 *
 * <ul>
 *   <li><b>1.20.x vs 1.21.1</b> -- {@code Util.OS.openPath} does not exist on 1.20.4 or 1.20.6. Those
 *       releases have {@code openFile}, {@code openUri} and {@code openUrl}, which is why opening a
 *       directory is a different call there rather than a differently spelled one.
 *   <li><b>1.21.1 .. 1.21.11 vs 26.x</b> -- from 1.21.1 it is {@code Util.getPlatform().openPath()}
 *       and {@code .openUri()} at {@code net.minecraft.Util}; 26.x moved both and not to the same
 *       place. {@code net.minecraft.Util} is gone, its surviving pieces at
 *       {@code net.minecraft.util.Util} without a platform field; paths went to
 *       {@code com.mojang.blaze3d.Blaze3D.openPath(Path)} and URIs to {@code Blaze3D.openUri(URI)}.
 * </ul>
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
        //? if >=26.1 {
        /*com.mojang.blaze3d.Blaze3D.openPath(folder);
        *///?}
        //? if >=1.21.1 && <26.1 {
        net.minecraft.Util.getPlatform().openPath(folder);
        //?}
        // 1.20.4 and 1.20.6 have no openPath, so the directory goes through openFile, which takes a File
        // and is the same hand-off to the desktop. See the class comment for where this changes.
        //? if <1.21.1 {
        /*net.minecraft.Util.getPlatform().openFile(folder.toFile());
        *///?}
    }

    /** Opens a URL in the player's default browser. */
    public static void openUrl(String url) {
        //? if >=26.1 {
        /*com.mojang.blaze3d.Blaze3D.openUri(java.net.URI.create(url));
        *///?}
        //? if <26.1 {
        net.minecraft.Util.getPlatform().openUri(url);
        //?}
    }
}
