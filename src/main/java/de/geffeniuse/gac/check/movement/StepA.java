package de.geffeniuse.gac.check.movement;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketEvent;
import de.geffeniuse.gac.GAC;
import de.geffeniuse.gac.check.Check;
import de.geffeniuse.gac.data.GACUser;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/**
 * StepA - Step Hack Detection
 * Detects players stepping up full blocks without jumping.
 * Normal step height is 0.5 blocks, hacks allow 1.0+ blocks.
 */
public class StepA extends Check {

    private int suspicion = 0;
    private double lastY = 0;
    private boolean wasOnGround = true;
    private int groundTicks = 0;

    // Jump tracking
    private int airTicks = 0;
    private double jumpStartY = 0;
    private double maxJumpY = 0;
    private boolean isJumping = false;
    private double previousDeltaY = 0;

    private static final double MAX_STEP_HEIGHT = 0.75; // 0.6 vanilla + 0.15 buffer for edge cases
    private static final int SUSPICION_THRESHOLD = 12;
    private static final int KICK_THRESHOLD = 20;

    public StepA(GACUser user) {
        super(user, "Step", "Detects step hacks.");
    }

    @Override
    public void onPacket(PacketEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.POSITION &&
            event.getPacketType() != PacketType.Play.Client.POSITION_LOOK) {
            return;
        }

        Player player = user.getPlayer();
        if (player == null) return;

        if (player.getGameMode() == GameMode.CREATIVE ||
            player.getGameMode() == GameMode.SPECTATOR ||
            player.isInsideVehicle() || player.isFlying() ||
            player.isClimbing() || player.isSwimming() ||
            player.isGliding() || user.isTakingVelocity() || 
            user.isTeleporting()) {
            return;
        }

        double currentY = user.getLastY();
        double deltaY = user.getDeltaY();

        Bukkit.getScheduler().runTask(GAC.getInstance(), () -> {
            if (player == null || !player.isOnline()) return;

            boolean onGround = isOnGround(player);
            boolean inLiquid = isInLiquid(player);
            boolean onClimbable = isOnClimbable(player);
            boolean onSlime = isOnSlime(player);
            boolean onBed = isOnBed(player);
            boolean nearStairs = isNearStairsOrSlabs(player);
            
            // Check Jump Boost
            int jumpBoost = 0;
            if (player.hasPotionEffect(org.bukkit.potion.PotionEffectType.JUMP_BOOST)) {
                jumpBoost = player.getPotionEffect(org.bukkit.potion.PotionEffectType.JUMP_BOOST).getAmplifier() + 1;
            }

            // Skip if in special conditions
            if (inLiquid || onClimbable || onSlime || onBed) {
                resetJumpTracking();
                wasOnGround = onGround;
                lastY = currentY;
                return;
            }

            if (onGround) {
                groundTicks++;

                // ========== STEP DETECTION ==========
                // Only check when landing from a "jump"
                if (isJumping && airTicks > 0) {
                    double totalHeight = currentY - jumpStartY;
                    double peakHeight = maxJumpY - jumpStartY;

                    // Step hacks have characteristic patterns:
                    // 1. Very few airTicks for height gained (< 5 ticks for 1.5 blocks)
                    // 2. No real "peak" - they go straight up and land
                    // 3. Landing height close to peak height (no falling arc)

                    boolean isSuspicious = false;
                    String reason = "";
                    
                    // Adjust max height for Jump Boost
                    // Base jump is ~1.25 blocks. Each level adds ~0.5 blocks.
                    double maxExpectedHeight = 1.35 + (jumpBoost * 0.75);

                    // Check 1: Impossible speed for height (step hacks are FAST)
                    // Normal jump: ~12 ticks to reach 1.25 blocks peak
                    // Step 2.0: Usually < 3-4 ticks to reach 2.0 blocks
                    if (totalHeight >= 1.0 && airTicks <= 3 && totalHeight > maxExpectedHeight) {
                        isSuspicious = true;
                        reason = String.format("fastStep=%.2f ticks=%d (max=%.2f)", totalHeight, airTicks, maxExpectedHeight);
                        suspicion += 5; // Instant flag for blatant step
                    }
                    
                    // Meteor Step 2.0 often sends multiple packets but very fast
                    if (totalHeight >= 1.9 && airTicks <= 6 && jumpBoost == 0) {
                        isSuspicious = true;
                        reason = String.format("highStep=%.2f ticks=%d", totalHeight, airTicks);
                    }

                    // Check 2: No jump arc (peak == landing)
                    // Normal jump has peak higher than landing
                    // Step hack goes directly to landing height
                    // IGNORE if near stairs/slabs because auto-step can look like this
                    if (totalHeight >= 1.0 && Math.abs(peakHeight - totalHeight) < 0.1 && airTicks < 8 && !nearStairs && jumpBoost == 0) {
                        isSuspicious = true;
                        reason = String.format("noArc=%.2f peak=%.2f", totalHeight, peakHeight);
                    }

                    if (isSuspicious) {
                        suspicion += 3;

                        if (suspicion >= SUSPICION_THRESHOLD) {
                            fail(reason);
                            suspicion = 0;

                            // Setback to safe location
                            safeTeleport(player);

                            if (getViolationLevel() >= KICK_THRESHOLD) {
                                GAC.incrementKicks();
                                player.kickPlayer("§b§lGAC \n\n§7Unfair Advantage detected.\n§fCheck: §bStep");
                            }
                        }
                    }
                }

                resetJumpTracking();
            } else {
                groundTicks = 0;

                // Track jump start
                if (wasOnGround && !onGround) {
                    isJumping = true;
                    jumpStartY = lastY;
                    maxJumpY = currentY;
                    airTicks = 0;
                }

                if (isJumping) {
                    airTicks++;
                    // Track maximum height reached
                    if (currentY > maxJumpY) {
                        maxJumpY = currentY;
                    }
                    
                    previousDeltaY = deltaY;
                }
            }

            // Check for INSTANT step (both packets claim ground but Y increased > 0.6)
            // Ignore if near stairs/slabs/special blocks to prevent false flags
            if (wasOnGround && onGround && deltaY > MAX_STEP_HEIGHT && deltaY < 2.0 && !nearStairs && jumpBoost == 0) {
                Block standingOn = player.getLocation().clone().add(0, -0.1, 0).getBlock();

                if (standingOn.getType().isSolid()) {
                    suspicion += 2;

                    if (suspicion >= SUSPICION_THRESHOLD) {
                        fail(String.format("instantStep=%.2f", deltaY));
                        suspicion = 0;

                        safeTeleport(player);

                        if (getViolationLevel() >= KICK_THRESHOLD) {
                            GAC.incrementKicks();
                            player.kickPlayer("§b§lGAC \n\n§7Unfair Advantage detected.\n§fCheck: §bStep");
                        }
                    }
                }
            }

            wasOnGround = onGround;
            lastY = currentY;

            // Decay suspicion when moving normally
            if (onGround && Math.abs(deltaY) < 0.1) {
                suspicion = Math.max(0, suspicion - 1);
            }
        });
    }

    private void safeTeleport(Player player) {
        // Teleport to last valid location known by GACUser to prevent floor clipping
        Location safe = new Location(player.getWorld(), user.getLastX(), user.getLastY(), user.getLastZ(), user.getLastYaw(), user.getLastPitch());
        player.teleport(safe);
    }
    
    private boolean isNearStairsOrSlabs(Player player) {
        for (double x = -0.5; x <= 0.5; x += 0.5) {
            for (double z = -0.5; z <= 0.5; z += 0.5) {
                for (double y = -0.5; y <= 1.0; y += 0.5) {
                    String name = player.getLocation().clone().add(x, y, z).getBlock().getType().name();
                    if (name.contains("STAIR") || name.contains("SLAB") || name.contains("STEP") || 
                        name.contains("SNOW") || name.contains("CARPET") || name.contains("TRAPDOOR") || 
                        name.contains("LILY") || name.contains("BED") || name.contains("DAYLIGHT") ||
                        name.contains("PETAL") || name.contains("MOSS") || name.contains("DRIPLEAF") ||
                        name.contains("AZALEA") || name.contains("SCULK") || name.contains("SENSOR")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void resetJumpTracking() {
        isJumping = false;
        airTicks = 0;
        maxJumpY = 0;
        jumpStartY = 0;
    }

    private boolean isOnGround(Player player) {
        Location loc = player.getLocation();
        for (double x = -0.3; x <= 0.3; x += 0.3) {
            for (double z = -0.3; z <= 0.3; z += 0.3) {
                if (loc.clone().add(x, -0.1, z).getBlock().getType().isSolid()) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isInLiquid(Player player) {
        Material mat = player.getLocation().getBlock().getType();
        return mat == Material.WATER || mat == Material.LAVA;
    }

    private boolean isOnClimbable(Player player) {
        String name = player.getLocation().getBlock().getType().name();
        return name.contains("LADDER") || name.contains("VINE") || name.contains("SCAFFOLDING");
    }

    private boolean isOnSlime(Player player) {
        Block below = player.getLocation().clone().add(0, -0.1, 0).getBlock();
        String name = below.getType().name();
        return name.contains("SLIME") || name.contains("HONEY");
    }

    private boolean isOnBed(Player player) {
        Block below = player.getLocation().clone().add(0, -0.1, 0).getBlock();
        return below.getType().name().contains("BED");
    }
}
