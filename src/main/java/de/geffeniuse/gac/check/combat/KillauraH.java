package de.geffeniuse.gac.check.combat;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.EnumWrappers;
import de.geffeniuse.gac.GAC;
import de.geffeniuse.gac.check.Check;
import de.geffeniuse.gac.data.GACUser;
import org.bukkit.Bukkit;

/**
 * KillauraH - Rotation Speed Analysis
 * Detects inhuman rotation speeds during combat.
 * Humans have physical limits on how fast they can move their mouse.
 */
public class KillauraH extends Check {

    // Statistics tracking
    private float lastYaw = 0;
    private float lastPitch = 0;
    private long lastRotationTime = 0;
    private int impossibleRotations = 0;
    private int suspiciousRotations = 0;

    // Thresholds based on human limits
    // Pro players can do ~180 degrees in ~100ms with high sens, but not instantly
    private static final float MAX_YAW_PER_TICK = 180.0f; // Max degrees per tick (50ms)
    private static final float SUSPICIOUS_YAW_PER_TICK = 120.0f;
    private static final float MAX_PITCH_PER_TICK = 90.0f;

    private static final int IMPOSSIBLE_THRESHOLD = 3; // 3 impossible rotations = flag
    private static final int SUSPICIOUS_THRESHOLD = 8; // 8 suspicious = flag
    private static final int KICK_THRESHOLD = 5;

    public KillauraH(GACUser user) {
        super(user, "Killaura (Rotation)", "Detects inhuman rotation speeds.");
    }

    @Override
    public void onPacket(PacketEvent event) {
        // Track rotations during combat
        if (event.getPacketType() == PacketType.Play.Client.LOOK ||
            event.getPacketType() == PacketType.Play.Client.POSITION_LOOK) {
            checkRotation();
        }

        // Increase sensitivity during attacks
        if (event.getPacketType() == PacketType.Play.Client.USE_ENTITY) {
            EnumWrappers.EntityUseAction action;
            try {
                action = event.getPacket().getEnumEntityUseActions().read(0).getAction();
            } catch (Exception e) {
                return;
            }

            if (action == EnumWrappers.EntityUseAction.ATTACK) {
                analyzeAttackRotation();
            }
        }
    }

    private void checkRotation() {
        float currentYaw = user.getLastYaw();
        float currentPitch = user.getLastPitch();
        long now = System.currentTimeMillis();

        if (lastRotationTime == 0) {
            lastYaw = currentYaw;
            lastPitch = currentPitch;
            lastRotationTime = now;
            return;
        }

        long timeDelta = now - lastRotationTime;
        if (timeDelta < 10) return; // Too fast, skip

        // Calculate rotation deltas
        float yawDelta = Math.abs(currentYaw - lastYaw);
        if (yawDelta > 180) yawDelta = 360 - yawDelta;
        float pitchDelta = Math.abs(currentPitch - lastPitch);

        // Normalize to per-tick (50ms)
        float tickFactor = 50.0f / Math.max(timeDelta, 1);
        float yawPerTick = yawDelta * tickFactor;
        float pitchPerTick = pitchDelta * tickFactor;

        // Check for impossible rotations (inhuman speed)
        if (yawPerTick > MAX_YAW_PER_TICK || pitchPerTick > MAX_PITCH_PER_TICK) {
            impossibleRotations++;
        } else if (yawPerTick > SUSPICIOUS_YAW_PER_TICK) {
            suspiciousRotations++;
        } else {
            // Decay
            impossibleRotations = Math.max(0, impossibleRotations - 1);
            suspiciousRotations = Math.max(0, suspiciousRotations - 1);
        }

        lastYaw = currentYaw;
        lastPitch = currentPitch;
        lastRotationTime = now;
    }

    private void analyzeAttackRotation() {
        // Check accumulated rotation anomalies during combat
        if (impossibleRotations >= IMPOSSIBLE_THRESHOLD) {
            fail(String.format("impossible rotations x%d", impossibleRotations));
            impossibleRotations = 0;

            if (getViolationLevel() >= KICK_THRESHOLD) {
                Bukkit.getScheduler().runTask(GAC.getInstance(), () -> {
                    if (user.getPlayer() != null && user.getPlayer().isOnline()) {
                        GAC.incrementKicks();
                        user.getPlayer().kickPlayer("§b§lGAC \n\n§7Unfair Advantage detected.\n§fCheck: §bKillaura");
                    }
                });
            }
        }

        if (suspiciousRotations >= SUSPICIOUS_THRESHOLD) {
            fail(String.format("suspicious rotations x%d", suspiciousRotations));
            suspiciousRotations = 0;
        }
    }
}
