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
 * <h2>What the swap does with it</h2>
 * A screen that declares this is not <em>replacing</em> its parent; the parent has to stay where it
 * is, at full strength, because it is still on screen. So {@link Screens#set} installs such a screen
 * at once instead of playing the parent's exit first: an exit would fade the map out under the dialog
 * that is about to redraw it, and the redraw would then pop the parent back to full -- a flicker in
 * the middle of what is supposed to be one surface. The dialog's own entrance is unaffected: it is
 * the thing being added, and it fades in over the parent that never moved.
 *
 * <p>Declaring it must stay in step with the drawing: a screen that re-renders its parent and does not
 * declare this will show the parent's exit followed by the parent's entrance again (the visible
 * symptom is the whole map flashing when the dialog opens), and a screen that declares it and does not
 * draw its parent will leave the previous screen visible for a frame behind nothing.
 */
public interface ParentBackdrop {
}
