package de.geffeniuse.gac.check.movement;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketEvent;
import de.geffeniuse.gac.GAC;
import de.geffeniuse.gac.check.Check;
import de.geffeniuse.gac.data.GACUser;
import org.bukkit.Bukkit;
import org.bukkit.util.Vector;
import java.util.UUID;

public class VelocityA extends Check {

    // Tracking state
    private boolean tracking = false;
    private double startX, startZ;
    private double velocityX, velocityZ;
    private int ticksSinceKB = 0;

    // Heuristics
    private int suspiciousCount = 0;
    private long lastSuspiciousTime = 0;
    private static final int SUSPICIOUS_THRESHOLD = 2;
    private static final long SUSPICIOUS_DECAY_MS = 15000; // 15 second memory

    // Thresholds
    private static final int CHECK_TICKS = 6; // Check faster
    private static final double MIN_VELOCITY = 0.15; // Check smaller KB too
    private static final double MIN_PERCENTAGE = 0.65; // Require 65% of expected motion
    private static final int KICK_THRESHOLD = 6;

    // Debug
    private static final boolean DEBUG = false; // Disabled debug spam

    public VelocityA(GACUser user) {
        super(user, "Velocity", "Checks knockback taken.");
    }

    @Override
    public void onPacket(PacketEvent event) {
        // 2. INCOMING: Player position update - check if they took the knockback
        if (event.getPacketType() == PacketType.Play.Client.POSITION ||
            event.getPacketType() == PacketType.Play.Client.POSITION_LOOK) {

            // Check if user has pending velocity from GACUser
            if (user.getVelocityTicks() > 0 && !tracking) {
                Vector vel = user.getServerVelocity();
                if (vel != null) {
                    double horizontalVelocity = Math.sqrt(vel.getX() * vel.getX() + vel.getZ() * vel.getZ());

                    if (horizontalVelocity > MIN_VELOCITY) {
                        tracking = true;
                        velocityX = vel.getX();
                        velocityZ = vel.getZ();
                        startX = user.getLastX();
                        startZ = user.getLastZ();
                        ticksSinceKB = 0;
                        // Clear the pending state so we don't restart tracking mid-air
                        user.setVelocityTicks(0);
                    } else {
                         user.setVelocityTicks(0);
                    }
                }
            }

            handlePositionPacket();
        }
    }

    private void handlePositionPacket() {
        if (!tracking) return;

        ticksSinceKB++;

        // Too early - wait at least 3 ticks for KB to apply fully
        if (ticksSinceKB < 3) return;

        // Get current position from packet data
        double currentX = user.getLastX();
        double currentZ = user.getLastZ();

        // Calculate actual movement
        double movedX = currentX - startX;
        double movedZ = currentZ - startZ;
        double movedDistance = Math.sqrt(movedX * movedX + movedZ * movedZ);

        // Calculate expected movement
        double expectedDistance = Math.sqrt(velocityX * velocityX + velocityZ * velocityZ);
        
        // Adjust expectation based on ticks passed (velocity decay)
        // Simple heuristic: initially high, decays
        // After 6-8 ticks, they should have moved at least a good chunk of the initial vector length
        
        // Early pass: If they moved enough, clear tracking
        if (movedDistance >= expectedDistance * MIN_PERCENTAGE) {
            tracking = false;
            return;
        }

        // Time to check!
        if (ticksSinceKB >= CHECK_TICKS) {
            final double finalMovedDistance = movedDistance;
            final double finalExpectedDistance = expectedDistance;

            // Check for block collision before flagging
            Bukkit.getScheduler().runTask(GAC.getInstance(), () -> {
                if (user.getPlayer() == null || !user.getPlayer().isOnline() || !isEnabled()) {
                    tracking = false;
                    return;
                }

                // Check if there's a block in the knockback direction
                boolean blockedByWall = isBlockedByWall();

                if (blockedByWall) {
                    tracking = false;
                    return;
                }

                double percentage = (finalExpectedDistance > 0) ? (finalMovedDistance / finalExpectedDistance) * 100 : 100;

                // Suspicious: Didn't move enough and no wall blocking
                if (finalMovedDistance < finalExpectedDistance * MIN_PERCENTAGE) {
                    long now = System.currentTimeMillis();

                    // Decay suspicion if too much time passed
                    if (now - lastSuspiciousTime > SUSPICIOUS_DECAY_MS) {
                        suspiciousCount = 0;
                    }

                    suspiciousCount++;
                    lastSuspiciousTime = now;

                    // Only flag after multiple suspicious events (heuristics)
                    if (suspiciousCount >= SUSPICIOUS_THRESHOLD) {
                        fail(String.format("%.0f%% x%d", percentage, suspiciousCount));

                        // Kick after KICK_THRESHOLD violations
                        if (getViolationLevel() >= KICK_THRESHOLD) {
                            GAC.incrementKicks();
                            user.getPlayer().kickPlayer("§b§lGAC \n\n§7Unfair Advantage detected.\n§fCheck: §bVelocity");
                        } else {
                            // Setback - nudge them in direction of velocity
                            user.getPlayer().setVelocity(new Vector(velocityX, user.getPlayer().getVelocity().getY(), velocityZ));
                        }

                        suspiciousCount = 0;
                    }
                } else {
                    // Passed - decay suspicion
                    suspiciousCount = Math.max(0, suspiciousCount - 1);
                }

                tracking = false;
            });

            // Prevent double-checking
            tracking = false;
        }
    }

    private boolean isBlockedByWall() {
        if (user.getPlayer() == null) return true;

        org.bukkit.Location loc = user.getPlayer().getLocation();

        // Only check if velocity is significant
        double horizVel = Math.sqrt(velocityX * velocityX + velocityZ * velocityZ);
        if (horizVel < 0.1) return false;

        org.bukkit.util.Vector kbDirection = new org.bukkit.util.Vector(velocityX, 0, velocityZ).normalize();

        // Check at multiple distances - player might already be against a wall
        double[] distances = {0.3, 0.6, 1.0, 1.5};
        double[] heights = {0.1, 1.0}; // feet, head

        for (double dist : distances) {
            org.bukkit.Location checkLoc = loc.clone().add(kbDirection.clone().multiply(dist));

            for (double height : heights) {
                org.bukkit.block.Block block = checkLoc.clone().add(0, height, 0).getBlock();
                if (block.getType().isSolid()) {
                    return true;
                }
            }
        }
        
        // Also check directly behind them (in case they backed into a wall)
        org.bukkit.Location behind = loc.clone().subtract(kbDirection.clone().multiply(0.5));
        if (behind.getBlock().getType().isSolid()) return true;

        return false;
    }
}
