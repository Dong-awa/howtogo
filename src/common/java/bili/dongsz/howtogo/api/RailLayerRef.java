package bili.dongsz.howtogo.api;

/**
 * One machine-read rail layer, described rather than handed over.
 *
 * <h2>Why a description and not the geometry</h2>
 * Create's track graph and MTR's marks are rebuilt from their own data on their own schedule, and
 * neither belongs to this mod. A caller that needs the segments should ask the layer through
 * {@link WorldDataApi#railLayerSegments(String)} at the moment it draws, not hold on to what a
 * rebuilt layer had a second ago; what is stable enough to remember is the shape of the layer --
 * which layer it is, what it is called, how much is in it, and whether it may be edited at all.
 *
 * <h2>Editable</h2>
 * Always false for a layer read out of another mod, and that is not a placeholder: these layers are
 * declared read-only everywhere in this mod, the write API refuses them, and a caller that meant to
 * change one wants to be told so at the point it asks rather than to see its edit disappear on the
 * next rebuild. Player-drawn roads are not here at all -- they are
 * {@link WorldDataApi#roadsSnapshot()}, and those are editable, because the player drew them.
 *
 * @param id              stable id of the layer, unique across the mod's own and any registered
 * @param displayNameKey  translation key for the layer's name, not the translated text
 * @param segmentCount    how many segments the layer holds right now
 * @param editable        whether the write API may change it; false for every machine-read layer
 */
public record RailLayerRef(String id, String displayNameKey, int segmentCount, boolean editable) {
}
