package bili.dongsz.howtogo.api;

/**
 * One boarding point, from whichever of the three sources produced it: a player-marked road node, a Create track station, or an MTR station.
 * The name is the display name only. source is a bili.dongsz.howtogo.route.DestinationSource id, so a registered source can recognise its own stations.
 * @param name display name, never null and never blank
 * @param x block X
 * @param y block Y
 * @param z block Z
 * @param source id of the destination source this station came from
 */
public record StationRef(String name, int x, int y, int z, String source) {
}
