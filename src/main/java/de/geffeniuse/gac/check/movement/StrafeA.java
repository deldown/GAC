package de.geffeniuse.gac.check.movement;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketEvent;
import de.geffeniuse.gac.GAC;
import de.geffeniuse.gac.check.Check;
import de.geffeniuse.gac.data.GACUser;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

import java.util.LinkedList;

/**
 * StrafeA - Detects impossible air strafing
 *
 * In vanilla Minecraft:
 * - Air control is very limited
 * - You can only slightly adjust direction while in air
 * - Sharp turns while airborne are impossible
 *
 * Strafe hacks give players full air control, allowing:
 * - 180 degree turns in air
 * - Maintaining speed while changing direction
 * - "Omni-directional" movement
 */
public class StrafeA extends Check {

    // Direction tracking
    private final LinkedList<Double> moveAngles = new LinkedList<>();
    private final LinkedList<Double> angleDiffs = new LinkedList<>();
    private static final int HISTORY_SIZE = 10;

    // State
    private int airTicks = 0;
    private double lastMoveAngle = 0;
    private boolean wasInAir = false;

    // Violation tracking
    private int violations = 0;
    private double totalAngleChange = 0;

    public StrafeA(GACUser user) {
        super(user, "Strafe", "Detects impossible air strafing.");
    }

    @Override
    public void onPacket(PacketEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.POSITION &&
            event.getPacketType() != PacketType.Play.Client.POSITION_LOOK) {
            return;
        }

        Player player = user.getPlayer();
        if (player == null) return;

        // Skip exempt conditions
        if (player.isFlying() || player.getAllowFlight() ||
            player.getGameMode() == GameMode.CREATIVE ||
            player.getGameMode() == GameMode.SPECTATOR ||
            player.isInsideVehicle() || player.isGliding() ||
            player.isRiptiding() || player.isSwimming() ||
            user.isTeleporting() || user.isTakingVelocity()) {
            resetState();
            return;
        }

        double deltaX = user.getDeltaX();
        double deltaZ = user.getDeltaZ();
        double deltaXZ = user.getDeltaXZ();
        boolean onGround = player.isOnGround();

        // Skip tiny movements
        if (deltaXZ < 0.1) {
            if (!onGround) airTicks++;
            else airTicks = 0;
            return;
        }

        // Calculate movement angle
        double moveAngle = Math.toDegrees(Math.atan2(deltaZ, deltaX));

        if (!onGround) {
            airTicks++;

            if (wasInAir && airTicks > 1) {
                // Calculate angle change while in air
                double angleDiff = Math.abs(normalizeAngle(moveAngle - lastMoveAngle));

                moveAngles.addFirst(moveAngle);
                angleDiffs.addFirst(angleDiff);
                while (moveAngles.size() > HISTORY_SIZE) {
                    moveAngles.removeLast();
                    angleDiffs.removeLast();
                }

                totalAngleChange += angleDiff;

                // Check for strafe violations

                // Check 1: Single large angle change in air
                // Vanilla max is about 5-10 degrees per tick in air
                if (angleDiff > 30 && deltaXZ > 0.2) {
                    violations += 2;
                    if (violations >= 6) {
                        user.getMitigation().onMovementViolation("strafe", angleDiff);
                        fail(String.format("sharp turn=%.1f speed=%.2f", angleDiff, deltaXZ));
                        violations = 3;
                    }
                }

                // Check 2: Accumulated angle change
                // If total angle change over 5 ticks is too high
                if (airTicks >= 5 && angleDiffs.size() >= 5) {
                    double recentTotal = 0;
                    int count = 0;
                    for (Double diff : angleDiffs) {
                        if (count++ >= 5) break;
                        recentTotal += diff;
                    }

                    // More than 90 degrees total change in 5 air ticks is suspicious
                    if (recentTotal > 90 && deltaXZ > 0.25) {
                        violations += 2;
                        if (violations >= 6) {
                            user.getMitigation().onMovementViolation("strafe", recentTotal);
                            fail(String.format("accumulated=%.1f ticks=%d", recentTotal, airTicks));
                            violations = 3;
                        }
                    }
                }

                // Check 3: Maintaining high speed while turning
                // In vanilla, sharp turns reduce speed
                if (angleDiff > 20 && deltaXZ > 0.3) {
                    // Should have lost speed but didn't
                    violations++;
                }
            }

            wasInAir = true;
        } else {
            // On ground - reset air tracking
            if (airTicks > 0) {
                // Landing - check total air movement
                if (totalAngleChange > 180 && airTicks > 3) {
                    // Turned more than 180 degrees total while in air
                    violations += 2;
                    if (violations >= 6) {
                        fail(String.format("air control total=%.1f ticks=%d", totalAngleChange, airTicks));
                        violations = 3;
                    }
                }
            }

            airTicks = 0;
            totalAngleChange = 0;
            wasInAir = false;
            violations = Math.max(0, violations - 1);
        }

        lastMoveAngle = moveAngle;
    }

    private void resetState() {
        airTicks = 0;
        totalAngleChange = 0;
        wasInAir = false;
        violations = 0;
        moveAngles.clear();
        angleDiffs.clear();
    }

    private double normalizeAngle(double angle) {
        while (angle > 180) angle -= 360;
        while (angle < -180) angle += 360;
        return angle;
    }
}
