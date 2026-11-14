package bili.dongsz.howtogo.api;

/**
 * A machine-read rail layer described rather than handed over: geometry is rebuilt by
 * Create/MTR on their own schedule, so callers re-query railLayerSegments at draw time and
 * only the layer's shape is stable. Editable is false for layers read from another mod.
 * @param id stable id, unique across own and registered layers
 * @param displayNameKey translation key, not the translated text
 * @param segmentCount segments the layer holds now
 * @param editable whether the write API may change it
 */
public record RailLayerRef(String id, String displayNameKey, int segmentCount, boolean editable) {
}
