package bili.dongsz.howtogo.client;

/**
 * A screen that re-renders its parent underneath itself, so the parent keeps showing behind it.
 *
 * <h2>Why this is a marker the screen has to declare</h2>
 * Three of this mod's screens are dialogs over another screen: the road/place name prompt, the line
 * editor and the place details dialog all call {@code parent.render(...)} before drawing their own
 * panel, and the parent is the world map itself when they are opened from it. That is what makes the
 * map readable behind the panel while a name is being typed, and it is a property of the dialog's
 * drawing, not something the screen stack can work out from the outside.
 *
 * <h2>What it is used for</h2>
 * The world map draws its own road and place labels, its marks and its HUD <em>over</em> the map in
 * screen coordinates, so any of them drawn while one of these dialogs is up would land on top of the
 * panel being typed into. {@link RoadElementRenderer} asks this marker and holds those passes back
 * while a dialog is over the map; see there. It is not what decides whether the swap animates: that
 * is "is the target one of this mod's own screens" -- see {@link Screens}.
 *
 * <p>Declaring it must stay in step with the drawing: a dialog that re-renders the map and does not
 * declare this gets the map's labels drawn across it, and a screen that declares it without drawing
 * the map under itself holds the labels back for no reason.
 */
public interface ParentBackdrop {
}
