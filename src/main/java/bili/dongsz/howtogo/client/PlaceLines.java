package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.transit.TransitLine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The lines calling at one block, from both places a line can come from.
 *
 * <h2>Why two lists and not one</h2>
 * A station's lines can be the player's own -- built in this mod's line editor, stored in the
 * dimension's {@code -lines.json} -- or MTR's, imported read-only from the other mod. They are two
 * different kinds of data with two different lifetimes, so they are kept separately by two stores.
 * A place card, though, asks one question: "what can I board here?" This is the one function that
 * merges the two answers, so the card does not have to know which store a line came from and the
 * two stores do not have to know about each other.
 *
 * <h2>Why the ids are de-duplicated</h2>
 * The same line can be in both lists: a player's line and an imported MTR line share an id when the
 * import was the source of both. Drawing it twice would show the same service as two chips, one of
 * which is the other's stale copy. The player's own copy wins, because that is the one the editor
 * may have recoloured or renamed -- the MTR copy is the other mod's data and is replaced on the next
 * import.
 *
 * <p>Called by a card that is drawn every frame, so it allocates nothing until it is asked and
 * returns a fresh immutable list: the two stores hand out live lists that the line editor mutates,
 * and a caller must not end up holding a view of one of them.
 */
public final class PlaceLines {

    private PlaceLines() {
    }

    /**
     * Every line calling at the block {@code (x, z)}, the player's first and MTR's after it.
     *
     * <p>An empty list is the normal answer for a place that is not a station, and the card draws no
     * line chips for it; there is deliberately no null return, because "no lines" is a value every
     * caller would otherwise have to write the same two lines to handle.
     */
    public static List<TransitLine> at(int x, int z) {
        Map<String, TransitLine> byId = new LinkedHashMap<>();
        for (TransitLine line : TransitLineStore.get()) {
            if (line != null && line.callsAt(x, z)) {
                byId.putIfAbsent(line.id(), line);
            }
        }
        for (TransitLine line : MtrTransit.lines()) {
            if (line != null && line.callsAt(x, z)) {
                byId.putIfAbsent(line.id(), line);
            }
        }
        return List.copyOf(byId.values());
    }
}
