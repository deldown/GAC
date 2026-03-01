package de.geffeniuse.gac.simulation;

import org.bukkit.Location;
import org.bukkit.World;

import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Manages setback positions for movement checks.
 * Stores validated "safe" positions that can be used for teleporting cheaters back.
 *
 * Similar to GrimAC's setback system.
 */
public class SetbackManager {

    // History of validated positions
    private final ConcurrentLinkedDeque<ValidatedPosition> positionHistory = new ConcurrentLinkedDeque<>();
    private static final int MAX_HISTORY = 30; // ~1.5 seconds of positions

    // Current setback target
    private ValidatedPosition setbackPosition = null;

    // Tracking
    private int ticksSinceLastValid = 0;
    private int consecutiveInvalidTicks = 0;

    /**
     * Record a position as valid (player moved legitimately)
     */
    public void recordValidPosition(double x, double y, double z, float yaw, float pitch, boolean onGround, World world) {
        ValidatedPosition pos = new ValidatedPosition(x, y, z, yaw, pitch, onGround, world, System.currentTimeMillis());

        positionHistory.addFirst(pos);
        while (positionHistory.size() > MAX_HISTORY) {
            positionHistory.removeLast();
        }

        ticksSinceLastValid = 0;
        consecutiveInvalidTicks = 0;

        // Update setback position (use slightly older position for safety)
        if (positionHistory.size() >= 5) {
            int index = 0;
            for (ValidatedPosition p : positionHistory) {
                if (index == 4) { // 5th position back
                    setbackPosition = p;
                    break;
                }
                index++;
            }
        }
    }

    /**
     * Record an invalid tick (player moved suspiciously)
     */
    public void recordInvalidTick() {
        ticksSinceLastValid++;
        consecutiveInvalidTicks++;
    }

    /**
     * Get the setback location to teleport player to
     */
    public Location getSetbackLocation() {
        if (setbackPosition == null && !positionHistory.isEmpty()) {
            // Use oldest position if no setback set
            setbackPosition = positionHistory.getLast();
        }

        if (setbackPosition != null) {
            return new Location(
                setbackPosition.world,
                setbackPosition.x,
                setbackPosition.y,
                setbackPosition.z,
                setbackPosition.yaw,
                setbackPosition.pitch
            );
        }
        return null;
    }

    /**
     * Check if we should setback (enough consecutive invalid ticks)
     */
    public boolean shouldSetback(int threshold) {
        return consecutiveInvalidTicks >= threshold && setbackPosition != null;
    }

    /**
     * Reset after setback
     */
    public void onSetback() {
        consecutiveInvalidTicks = 0;
        ticksSinceLastValid = 0;
    }

    /**
     * Clear all history (on teleport, respawn, etc.)
     */
    public void clear() {
        positionHistory.clear();
        setbackPosition = null;
        ticksSinceLastValid = 0;
        consecutiveInvalidTicks = 0;
    }

    /**
     * Get consecutive invalid ticks
     */
    public int getConsecutiveInvalidTicks() {
        return consecutiveInvalidTicks;
    }

    /**
     * Validated position data class
     */
    public static class ValidatedPosition {
        public final double x, y, z;
        public final float yaw, pitch;
        public final boolean onGround;
        public final World world;
        public final long timestamp;

        public ValidatedPosition(double x, double y, double z, float yaw, float pitch, boolean onGround, World world, long timestamp) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
            this.onGround = onGround;
            this.world = world;
            this.timestamp = timestamp;
        }
    }
}
