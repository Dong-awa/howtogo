package bili.dongsz.howtogo.api;

/**
 * A layer of points and lines contributed by another mod.
 *
 * <h2>The version is the whole protocol</h2>
 * The mod does not poll this source for changes and cannot see what it holds: it asks
 * {@link #version()} -- a single integer -- and compares it with the number it read beside the data
 * it is currently drawing. When the two differ the layer is read again from scratch; when they are
 * equal nothing happens at all. So a source that changes its data must raise the number in the same
 * breath, and a source that returns a constant is a source whose layer is read once and never
 * refreshed.
 *
 * <p>Any change of value will do, as long as it is a change: a counter incremented on every edit, the
 * identity hash of the structure behind the data, a serial number from a save load. The comparison is
 * {@code !=} on an {@code int}, not "greater than", so a number that wraps or restarts is not a
 * problem either -- the only thing that must be true is that it differs from the last one the mod saw
 * whenever the data it describes differs.
 *
 * <h2>Write, don't keep</h2>
 * {@link #write} is called on the client thread, once per re-read, with a writer that is only good for
 * that call. Everything written becomes the mod's copy: the source may drop what it wrote immediately,
 * or reuse its own objects freely, because nothing is retained. The copy is what the map draws, and it
 * is never written to disk and never offered to the player's editor.
 *
 * <h2>What the layer is for</h2>
 * It is drawn on the world map alongside the player's roads and the machine-read rail layers -- the
 * same colouring, the same storey sorting, the same zoom thinning. It is <em>not</em> routed over and
 * it is <em>not</em> editable: a route is planned through the player's own roads, and a line an addon
 * drew is a statement about the world rather than a road the player may fix.
 *
 * <h2>Example</h2>
 * <pre>{@code
 * public final class MySource implements RoadDataSource {
 *     private int version;
 *
 *     @Override
 *     public int version() {
 *         return version;
 *     }
 *
 *     @Override
 *     public void write(RoadDataWriter writer, String dimension) {
 *         if (!"minecraft:overworld".equals(dimension)) {
 *             return;                       // nothing of mine down there
 *         }
 *         writer.point("a", 100, 64, 200, "North gate", PointKind.STATION);
 *         writer.point("b", 140, 64, 200, "South gate", PointKind.ENDPOINT);
 *         writer.line("ab", "a", "b", RoadClass.PATH, 0, false);
 *     }
 *
 *     void onMyDataChanged() {
 *         version++;                        // the mod re-reads on its next tick
 *     }
 * }
 * }</pre>
 */
public interface RoadDataSource {

    /**
     * A number that changes whenever the data {@link #write} would state has changed.
     *
     * <p>Read on the client tick, so it must be cheap and must not block: it is asked every tick, and
     * the answer is compared with the one from the last read. See the class documentation.
     */
    int version();

    /**
     * States this source's points and lines for one dimension.
     *
     * <p>Called on the client thread when the version has changed and the player is in a world. The
     * writer collects everything written during the call; what is not written is not in the layer, so
     * a re-read states the layer in full rather than as a difference from the last one.
     *
     * <p>A throw from here is caught and reported against this source, and the layer is left as it
     * was: one addon's bad day does not take the map down with it.
     *
     * @param writer    where to state the layer
     * @param dimension the dimension's id, for example {@code minecraft:overworld}; which world the
     *                  coordinates are in, since coordinates alone mean nothing across dimensions
     */
    void write(RoadDataWriter writer, String dimension);
}
