package bili.dongsz.howtogo.api;

/**
 * A layer of points and lines contributed by another mod.
 * The mod never polls the source: it compares version() with the number it read beside the data it is drawing. A different value triggers a full re-read; equal means nothing happens. Any change of value works (!= on an int, wraps allowed), but a constant version means the layer is read once and never refreshed.
 * write() runs on the client thread once per re-read and copies whatever is written; the source keeps nothing and may reuse its objects. The copy is never saved to disk and never offered to the player's editor.
 * Drawn on the world map with the player's roads, but not routed over and not editable.
 */
public interface RoadDataSource {

    /**
     * A number that changes whenever the data write would state has changed. Read on the client tick, so it must be cheap and must not block.
     */
    int version();

    /**
     * States this source's points and lines for one dimension. Called on the client thread after the version changed; the layer is stated in full, not as a delta. A throw is caught and reported, leaving the layer as it was.
     * @param writer    where to state the layer
     * @param dimension the dimension id, e.g. minecraft:overworld
     */
    void write(RoadDataWriter writer, String dimension);
}
