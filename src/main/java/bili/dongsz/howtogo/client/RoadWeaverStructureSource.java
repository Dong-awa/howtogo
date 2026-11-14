package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.RoadConfig;
import bili.dongsz.howtogo.place.PlaceKeys;
import bili.dongsz.howtogo.road.PlaceKind;
import bili.dongsz.howtogo.route.Destination;
import bili.dongsz.howtogo.route.DestinationSource;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Destinations from the structures RoadWeaver connected with its roads.
 * RoadWeaver joins villages, cabins and landmarks -- exactly the places a player wants to travel
 * to; positions are RoadWeaver's, not measured here. Read-only like CreateStationSource: nothing
 * is saved with the player's places, and with RoadWeaver absent, off or empty the source reports
 * itself unavailable.
 * Names: an alias, else the structure's translation key, else the prettified path, else the raw
 * id; a structure that cannot be identified is named by its coordinates.
 */
public final class RoadWeaverStructureSource implements DestinationSource {

    public static final String ID = "roadweaver";

    private int builtRevision = -1;
    private int builtAliasRevision = -1;
    private List<Destination> built = List.of();

    @Override
    public String id() {
        return ID;
    }

    /**
     * Asked of the layer rather than of destinations(), which builds one destination per structure; called every frame a map is drawn.
     */
    @Override
    public boolean isAvailable() {
        return RoadConfig.roadWeaverStructures() && !RoadWeaverLayer.structures().isEmpty();
    }

    @Override
    public String displayName() {
        return "hud.howtogo.source.roadweaver";
    }

    @Override
    public boolean marksPlaces() {
        return true;
    }

    @Override
    public int priority() {
        return 40;
    }

    /**
     * The structures of the current layer, cached against RoadWeaverLayer.revision(): asked once per
     * frame per map view. Aliases are a second version -- the player's own notes change the names
     * without changing a road.
     */
    @Override
    public List<Destination> destinations() {
        int revision = RoadWeaverLayer.revision();
        int aliasRevision = RoadWeaverLayer.aliasRevision();
        if (revision == builtRevision && aliasRevision == builtAliasRevision) {
            return built;
        }
        List<RoadWeaverLayer.Structure> structures = RoadWeaverLayer.structures();
        List<Destination> destinations = new ArrayList<>(structures.size());
        for (RoadWeaverLayer.Structure structure : structures) {
            // The structure id is what the player's notes attach to, so a new road to the same village cannot orphan them.
            destinations.add(new Destination(nameOf(structure), structure.x(), structure.markerY(),
                    structure.z(), ID, Destination.NO_COLOR, PlaceKind.PLACE, structure.id()));
        }
        built = List.copyOf(destinations);
        builtRevision = revision;
        builtAliasRevision = aliasRevision;
        return built;
    }

    /**
     * What to call one of RoadWeaver's structures, in the player's language where there is a name.
     * An alias in RoadWeaver's own map wins; public so the list, the map label and the route
     * instructions share one string instead of drifting apart.
     */
    public static String nameOf(RoadWeaverLayer.Structure structure) {
        String alias = RoadWeaverLayer.aliasOf(structure);
        if (alias != null) {
            return alias;
        }
        String id = structure.id();
        if (RoadWeaverLayer.isUnknown(id)) {
            return Component.translatable("hud.howtogo.roadweaver.structure",
                    String.valueOf(structure.x()), String.valueOf(structure.z())).getString();
        }
        ResourceLocation location = ResourceLocation.tryParse(id.trim());
        if (location == null) {
            return id;
        }
        String key = "structure." + location.getNamespace() + "." + location.getPath();
        String translated = Component.translatable(key).getString();
        if (!translated.equals(key)) {
            return translated;
        }
        if ("minecraft".equals(location.getNamespace())) {
            return prettify(location.getPath());
        }
        // A mod's structure has no readable name: the id is the honest label, and the namespace names the mod.
        return id;
    }

    /** A structure id's path as a readable label: underscores and slashes as spaces, first letter up. */
    static String prettify(String path) {
        String spaced = path.replace('_', ' ').replace('/', ' ').trim();
        if (spaced.isEmpty()) {
            return spaced;
        }
        return spaced.substring(0, 1).toUpperCase(Locale.ROOT) + spaced.substring(1);
    }
}

