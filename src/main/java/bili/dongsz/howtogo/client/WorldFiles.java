package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.HowToGo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;

/**
 * Where this mod's per-world data files live.
 *
 * <h2>Why this is its own class</h2>
 * Two things are stored per world and dimension now -- the road network and the names of the
 * automatically detected rail layer -- and they have to agree on which world and which dimension
 * they are talking about, or a name saved in one dimension would be looked up in another. One
 * resolver, used by both, is the only way that cannot drift apart; two copies of the same four lines
 * would agree today and disagree after the next edit to either.
 *
 * <h2>Why the config directory</h2>
 * Not the save folder: on a multiplayer server the client has no access to the save at all. The
 * dimension is part of the file name because coordinates are only meaningful within one dimension.
 */
final class WorldFiles {

    private WorldFiles() {
    }

    /**
     * {@code config/howtogo/<world>/<dimension><suffix>.json} for the level now loaded.
     *
     * @param suffix distinguishes the files stored for one dimension; the road network passes
     *               {@code ""}, which keeps its path exactly what it was before this class existed
     */
    static Path of(String suffix) {
        Minecraft mc = Minecraft.getInstance();
        Object level = mc.level;

        String dimension = "unknown";
        if (level instanceof ClientLevel clientLevel) {
            dimension = clientLevel.dimension().location().toString();
        }
        Path root = FMLPaths.CONFIGDIR.get().resolve(HowToGo.MODID);
        return root.resolve(sanitize(worldKey(mc))).resolve(sanitize(dimension) + suffix + ".json");
    }

    /**
     * Which world the data belongs to.
     *
     * <p>A singleplayer world is named by its level name and a server by its address, because those
     * are the two things that stay the same across sessions. Two worlds with the same name are the
     * one case this cannot tell apart, and they would share a file.
     */
    private static String worldKey(Minecraft mc) {
        if (mc.hasSingleplayerServer() && mc.getSingleplayerServer() != null) {
            return "sp_" + mc.getSingleplayerServer().getWorldData().getLevelName();
        }
        ServerData server = mc.getCurrentServer();
        if (server != null && server.ip != null && !server.ip.isBlank()) {
            return "mp_" + server.ip;
        }
        return "unknown";
    }

    /** Strips characters that are not safe in a file name. */
    private static String sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "unknown";
        }
        return raw.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
