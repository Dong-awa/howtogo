package bili.dongsz.howtogo.client;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * How the player is moving right now, read off data the client already has.
 *
 * <p>Facing and travel direction answer different questions, and the navigation readout needs both:
 * a player walking backwards down a road is still on the road, but they are not travelling the way
 * they are looking, and a player in a boat is not travelling the way the route was planned at all.
 * Nothing here is inferred from the route -- this is what the entity is doing, not what it was asked
 * to do.
 *
 * <p>The cases are decided in the order they are declared, so a reader can follow the precedence:
 * being carried beats gliding, gliding beats moving, and moving beats standing.
 */
public record MovementState(Kind kind, String vehicle) {

    /** What the player is doing. */
    public enum Kind {
        /** Being carried: a boat, a minecart, a horse, anything with a passenger seat. */
        RIDING,
        /** Moving under an elytra, which is fast, unsteerable in the usual sense, and airborne. */
        GLIDING,
        /** Going nowhere: standing, or sitting in something that is not moving. */
        STATIONARY,
        /** Travelling the way they are looking. */
        FORWARD,
        /** Travelling, but not the way they are looking: strafing, backing up, or being knocked. */
        OFF_HEADING
    }

    /**
     * Horizontal speed below which the player counts as standing, in blocks per tick.
     *
     * <p>Just under half a block a second, which is well below a sneak and well above the drift left
     * by rounding, so neither is mistaken for the other. Public because anything else asking whether
     * the player is moving at all should get the same answer this does.
     */
    public static final double MOVING_SPEED = 0.02;

    /**
     * How far the travel direction may differ from the facing direction and still count as forward.
     *
     * <p>Forty-five degrees each way, which takes in the diagonal of a keyboard and leaves pure
     * strafing on the other side of the line.
     */
    private static final double FORWARD_ARC_DEGREES = 45.0;

    public MovementState {
        vehicle = vehicle == null ? "" : vehicle;
    }

    /** The player's state, or standing still when there is no player yet. */
    public static MovementState of(LocalPlayer player) {
        if (player == null) {
            return new MovementState(Kind.STATIONARY, null);
        }
        Entity riding = player.getVehicle();
        if (riding != null) {
            // The display name rather than the type, so a named horse reads as the horse the player
            // named rather than as "Horse".
            return new MovementState(Kind.RIDING, riding.getDisplayName().getString());
        }
        if (player.isFallFlying()) {
            // An elytra is not a vehicle -- there is nothing to sit in -- but it is the same kind of
            // fact for a readout: the player is not travelling the way the route was planned.
            return new MovementState(Kind.GLIDING, null);
        }

        double deltaX = player.getDeltaMovement().x;
        double deltaZ = player.getDeltaMovement().z;
        double speed = Math.hypot(deltaX, deltaZ);
        if (speed < MOVING_SPEED) {
            return new MovementState(Kind.STATIONARY, null);
        }
        double off = bearingGap(facingBearing(player), travelBearing(player));
        return new MovementState(off <= FORWARD_ARC_DEGREES ? Kind.FORWARD : Kind.OFF_HEADING, null);
    }

    /** Whether the player is being carried rather than moving under their own power. */
    public boolean carried() {
        return kind == Kind.RIDING || kind == Kind.GLIDING;
    }

    /**
     * Bearing the player is facing, in degrees measured from +X towards +Z.
     *
     * <p>The same convention as {@link bili.dongsz.howtogo.route.Route.Maneuver#bearingAfter()},
     * so a heading and a road direction can be compared without converting either. Taken from the
     * yaw rather than from the movement, because a player standing at a corner who has already
     * turned to look down the new road has turned, and one drifting sideways has not.
     */
    public static double facingBearing(LocalPlayer player) {
        double yaw = Math.toRadians(player.getYRot());
        return Math.toDegrees(Math.atan2(Math.cos(yaw), -Math.sin(yaw)));
    }

    /** Bearing the player is actually travelling, in degrees, or NaN when they are not moving. */
    public static double travelBearing(LocalPlayer player) {
        Vec3 delta = player.getDeltaMovement();
        if (Math.hypot(delta.x, delta.z) < MOVING_SPEED) {
            return Double.NaN;
        }
        return Math.toDegrees(Math.atan2(delta.z, delta.x));
    }

    /** Smallest angle between two bearings, in degrees, always in {@code [0, 180]}. */
    public static double bearingGap(double from, double to) {
        double difference = Math.abs(from - to) % 360.0;
        return difference > 180.0 ? 360.0 - difference : difference;
    }
}
