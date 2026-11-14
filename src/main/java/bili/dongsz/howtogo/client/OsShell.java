package bili.dongsz.howtogo.client;

import java.nio.file.Path;

/**
 * Hands a path or a URL to the operating system in the shape each node wants. This is a separate class because the answer changes on two axes: Util.OS.openPath does not exist on 1.20.4/1.20.6 (use openFile/openUri/openUrl), 1.21.11 moved Util from net.minecraft to net.minecraft.util and kept the accessor, and 26.3 dropped the accessor so both calls go to com.mojang.blaze3d.Blaze3D as openPath(Path)/openUri(URI). Boundaries were read from each node's merged jar, not inferred from version numbers. A //? marker is a line comment, so it cannot sit mid-expression; a method body gives it a line of its own. Failures are the caller's to report.
 */
public final class OsShell {

    private OsShell() {
    }

    /** Opens a folder in the platform's file manager. The caller creates the directory first. */
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
        // 1.20.4 and 1.20.6 have no openPath, so the directory goes through openFile(File).
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
