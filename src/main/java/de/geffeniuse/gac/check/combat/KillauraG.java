package de.geffeniuse.gac.check.combat;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.EnumWrappers;
import de.geffeniuse.gac.check.Check;
import de.geffeniuse.gac.data.GACUser;
import org.bukkit.entity.Player;

/**
 * KillauraG - Rotation Snap-Back Detection
 * Detects when players snap to a target and immediately snap back.
 * Legit aura clients do this to appear "legit" but it's inhuman behavior.
 */
public class KillauraG extends Check {

    // State tracking
    private float yawBeforeHit = 0;
    private float pitchBeforeHit = 0;
    private boolean waitingForSnapBack = false;
    private int ticksSinceHit = 0;
    private int suspicionLevel = 0;
    private long lastFlagTime = 0;

    // Thresholds - made much more lenient to reduce false positives
    private static final float SNAP_THRESHOLD = 50.0f; // Must rotate at least 50° to hit (was 35)
    private static final float SNAPBACK_THRESHOLD = 3.0f; // Snap back within 3° of original (very precise)
    private static final int MAX_TICKS_FOR_SNAPBACK = 2; // Must snap back within 2 ticks (only catches obvious)

    public KillauraG(GACUser user) {
        super(user, "Killaura (SnapBack)", "Detects rotation snap-back after hits.");
    }

    @Override
    public void onPacket(PacketEvent event) {
        Player player = user.getPlayer();
        if (player == null) return;

        // Ignore vehicles - rotation works differently
        if (player.isInsideVehicle()) {
            waitingForSnapBack = false;
            suspicionLevel = 0;
            return;
        }

        // Track rotation before hits
        if (event.getPacketType() == PacketType.Play.Client.LOOK ||
            event.getPacketType() == PacketType.Play.Client.POSITION_LOOK) {

            if (waitingForSnapBack) {
                ticksSinceHit++;
                checkSnapBack();
            }
            return;
        }

        // Detect hits
        if (event.getPacketType() == PacketType.Play.Client.USE_ENTITY) {
            handleAttack(event);
        }
    }

    private void handleAttack(PacketEvent event) {
        EnumWrappers.EntityUseAction action;
        try {
            action = event.getPacket().getEnumEntityUseActions().read(0).getAction();
        } catch (Exception e) {
            return;
        }

        if (action != EnumWrappers.EntityUseAction.ATTACK) return;

        float currentYaw = user.getLastYaw();
        float currentPitch = user.getLastPitch();

        // Check if there was significant rotation to reach target
        float yawDiff = angleDiff(yawBeforeHit, currentYaw);
        float pitchDiff = Math.abs(pitchBeforeHit - currentPitch);

        if (yawDiff > SNAP_THRESHOLD || pitchDiff > SNAP_THRESHOLD / 2) {
            // Player rotated significantly to hit - watch for snap back
            waitingForSnapBack = true;
            ticksSinceHit = 0;
        }

        // Store current rotation as "before hit" for next attack
        yawBeforeHit = currentYaw;
        pitchBeforeHit = currentPitch;
    }

    private void checkSnapBack() {
        if (!waitingForSnapBack) return;

        float currentYaw = user.getLastYaw();

        // Check if rotated back close to original position
        float yawDiffFromOriginal = angleDiff(yawBeforeHit, currentYaw);

        if (ticksSinceHit <= MAX_TICKS_FOR_SNAPBACK) {
            // Quick snap back to near-original rotation = suspicious
            if (yawDiffFromOriginal < SNAPBACK_THRESHOLD) {
                long now = System.currentTimeMillis();

                // Only count if not too recent (prevent spam from one fight)
                if (now - lastFlagTime > 500) {
                    suspicionLevel++;
                    lastFlagTime = now;
                }

                // Need 12 snapbacks to flag - very conservative
                if (suspicionLevel >= 12) {
                    fail("snapped back " + suspicionLevel + "x");
                    suspicionLevel = 0;
                }

                waitingForSnapBack = false;
            }
        } else {
            // Took too long, probably legit movement
            waitingForSnapBack = false;
            // Decay faster - decay 3 per miss
            suspicionLevel = Math.max(0, suspicionLevel - 3);
        }
    }

    private float angleDiff(float a, float b) {
        float diff = Math.abs(a - b);
        if (diff > 180) diff = 360 - diff;
        return diff;
    }
}
