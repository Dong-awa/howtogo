package bili.dongsz.howtogo.client;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * How the player is moving right now, read off data the client already has. Nothing here is inferred from the route: this is what the entity is doing, not what it was asked to do.
 * Facing and travel direction answer different questions and the readout needs both. Cases are decided in declaration order: carried (boat, minecart, horse -- anything with a passenger seat) beats gliding under an elytra, which beats moving, which beats standing.
 */
public record MovementState(Kind kind, String vehicle) {

    public enum Kind {
        RIDING,
        GLIDING,
        STATIONARY,
        FORWARD,
        OFF_HEADING
    }

    /**
     * Horizontal speed below which the player counts as standing, in blocks per tick. Well below a sneak and well above rounding drift; public so every caller gets the same answer.
     */
    public static final double MOVING_SPEED = 0.02;

    /**
     * Travel direction may differ from facing by this much and still count as forward; takes in the diagonal of a keyboard and leaves pure strafing on the other side.
     */
    private static final double FORWARD_ARC_DEGREES = 45.0;

    public MovementState {
        vehicle = vehicle == null ? "" : vehicle;
    }

    public static MovementState of(LocalPlayer player) {
        if (player == null) {
            return new MovementState(Kind.STATIONARY, null);
        }
        Entity riding = player.getVehicle();
        if (riding != null) {
            // Display name rather than type, so a named horse reads as the horse the player named.
            return new MovementState(Kind.RIDING, riding.getDisplayName().getString());
        }
        if (player.isFallFlying()) {
            // An elytra is not a vehicle, but it is the same kind of fact for a readout: the player is not travelling as the route was planned.
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

    public boolean carried() {
        return kind == Kind.RIDING || kind == Kind.GLIDING;
    }

    /**
     * Bearing the player is facing, in degrees from +X towards +Z. Same convention as Route.Maneuver.bearingAfter(), so a heading and a road direction compare without converting. Taken from yaw, not movement: a player at a corner who has turned to look down the new road has turned, one drifting sideways has not.
     */
    public static double facingBearing(LocalPlayer player) {
        double yaw = Math.toRadians(player.getYRot());
        return Math.toDegrees(Math.atan2(Math.cos(yaw), -Math.sin(yaw)));
    }

    /** Bearing the player is actually travelling, in degrees, or NaN when not moving. */
    public static double travelBearing(LocalPlayer player) {
        Vec3 delta = player.getDeltaMovement();
        if (Math.hypot(delta.x, delta.z) < MOVING_SPEED) {
            return Double.NaN;
        }
        return Math.toDegrees(Math.atan2(delta.z, delta.x));
    }

    /** Smallest angle between two bearings, in degrees, always in [0, 180]. */
    public static double bearingGap(double from, double to) {
        double difference = Math.abs(from - to) % 360.0;
        return difference > 180.0 ? 360.0 - difference : difference;
    }
}
