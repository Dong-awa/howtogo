package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.transit.TransitLine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The lines calling at one block, from both places a line can come from.
 *
 * The player's own lines (dimension's -lines.json) and MTR's imported lines have different lifetimes and stores; the card asks one question and must not know which store a line came from.
 * Ids are de-duplicated, the player's copy winning: it is the one the editor may have recoloured or renamed, while the MTR copy is replaced on the next import.
 * Called every frame, so it returns a fresh immutable list rather than a live list the line editor mutates.
 */
public final class PlaceLines {

    private PlaceLines() {
    }

    /**
     * Every line calling at block (x, z), the player's first and MTR's after it. Empty when not a station; never null.
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
