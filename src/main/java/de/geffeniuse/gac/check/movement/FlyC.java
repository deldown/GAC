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
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * FlyC - Physics-based Fly Detection
 *
 * Unlike SimulationA, this tracks CUMULATIVE expected Y position
 * based on physics simulation, not just single-tick deltas.
 *
 * Key insight: After leaving ground, we KNOW where the player
 * should be based on initial velocity and gravity. Any significant
 * deviation = fly/glide hack.
 */
public class FlyC extends Check {

    // Physics constants
    private static final double GRAVITY = 0.08;
    private static final double DRAG = 0.98;
    private static final double JUMP_VELOCITY = 0.42;
    private static final double TERMINAL_VELOCITY = -3.92;

    // State tracking
    private double jumpStartY = 0;
    private double expectedY = 0;
    private double simulatedVelY = 0;
    private int airTicks = 0;
    private boolean tracking = false;
    private int graceTicks = 40;

    // Violation tracking
    private double cumulativeDeviation = 0;
    private int violationTicks = 0;

    // Thresholds
    private static final double DEVIATION_THRESHOLD = 0.5;     // 0.5 blocks deviation = suspicious
    private static final double INSTANT_FLAG_THRESHOLD = 1.5;  // 1.5 blocks = instant flag
    private static final int VIOLATION_TICKS_THRESHOLD = 5;    // 5 ticks of violations
    private static final int KICK_THRESHOLD = 8;

    public FlyC(GACUser user) {
        super(user, "Fly", "Physics simulation fly detection.");
    }

    @Override
    public void onPacket(PacketEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.POSITION &&
            event.getPacketType() != PacketType.Play.Client.POSITION_LOOK) {
            return;
        }

        Player player = user.getPlayer();
        if (player == null || !player.isOnline()) return;

        // Skip exempt conditions
        if (shouldSkip(player)) {
            resetTracking();
            graceTicks = 20;
            return;
        }

        if (graceTicks > 0) {
            graceTicks--;
            return;
        }

        double currentY = user.getLastY();
        double deltaY = user.getDeltaY();
        boolean onGround = isOnGround(player);
        boolean inLiquid = isInLiquid(player);
        boolean climbing = isClimbing(player);

        // Skip special cases
        if (inLiquid || climbing) {
            resetTracking();
            return;
        }

        if (onGround) {
            resetTracking();
            return;
        }

        // === PLAYER IS IN AIR ===

        if (!tracking) {
            // Just left ground - start tracking
            startTracking(currentY, deltaY, player);
            return;
        }

        // Continue tracking - simulate one tick of physics
        simulateTick(player);
        airTicks++;

        // Compare actual Y to expected Y
        double deviation = currentY - expectedY;

        // Check for violations
        if (Math.abs(deviation) > INSTANT_FLAG_THRESHOLD) {
            // Instant flag for massive deviation
            flag("instant", deviation, airTicks);
            resetTracking();
            return;
        }

        if (deviation > DEVIATION_THRESHOLD) {
            // Player is ABOVE where they should be
            cumulativeDeviation += deviation;
            violationTicks++;

            if (violationTicks >= VIOLATION_TICKS_THRESHOLD) {
                flag("cumulative_high", cumulativeDeviation / violationTicks, airTicks);
                violationTicks = 0;
                cumulativeDeviation = 0;
            }
        } else if (deviation < -DEVIATION_THRESHOLD * 2) {
            // Player is significantly BELOW expected (might be Phase/NoClip)
            // Don't flag for this - they might have hit something
            // But reset tracking as our simulation is off
            resetTracking();
        } else {
            // Within tolerance - decay violation
            violationTicks = Math.max(0, violationTicks - 1);
            cumulativeDeviation *= 0.9;
        }

        // === ADDITIONAL CHECKS ===

        // Check 1: No falling after long air time
        if (airTicks > 20 && deltaY > -0.1) {
            // After 20 ticks in air, player MUST be falling at decent speed
            // Unless they have slow falling
            if (!hasSlowFalling(player)) {
                flag("no_fall", deltaY, airTicks);
            }
        }

        // Check 2: Rising mid-air without velocity
        if (airTicks > 10 && deltaY > 0.1 && !user.isTakingVelocity()) {
            // Rising in air without knockback = fly
            if (!hasLevitation(player)) {
                flag("rising", deltaY, airTicks);
            }
        }

        // Check 3: Constant Y (hovering)
        if (airTicks > 15 && Math.abs(deltaY) < 0.01 && user.getDeltaXZ() > 0.1) {
            // Moving horizontally but not vertically = hovering
            flag("hover", deltaY, airTicks);
        }
    }

    private void startTracking(double y, double initialDeltaY, Player player) {
        tracking = true;
        jumpStartY = y;
        airTicks = 0;
        violationTicks = 0;
        cumulativeDeviation = 0;

        // Estimate initial velocity
        if (initialDeltaY > 0.3 && initialDeltaY < 0.5) {
            // Likely a normal jump
            simulatedVelY = JUMP_VELOCITY;

            // Jump boost
            PotionEffect jumpBoost = player.getPotionEffect(PotionEffectType.JUMP_BOOST);
            if (jumpBoost != null) {
                simulatedVelY += (jumpBoost.getAmplifier() + 1) * 0.1;
            }
        } else if (initialDeltaY > 0) {
            // Some upward velocity (maybe from edge or slope)
            simulatedVelY = initialDeltaY;
        } else {
            // Walked off edge
            simulatedVelY = 0;
        }

        // Set expected Y to current position
        expectedY = y;
    }

    private void simulateTick(Player player) {
        // Apply gravity
        simulatedVelY -= GRAVITY;

        // Apply drag
        simulatedVelY *= DRAG;

        // Terminal velocity
        simulatedVelY = Math.max(simulatedVelY, TERMINAL_VELOCITY);

        // Slow falling effect
        if (hasSlowFalling(player)) {
            simulatedVelY = Math.max(simulatedVelY, -0.125);
        }

        // Levitation effect
        PotionEffect levitation = player.getPotionEffect(PotionEffectType.LEVITATION);
        if (levitation != null) {
            simulatedVelY = (levitation.getAmplifier() + 1) * 0.05;
        }

        // Update expected Y
        expectedY += simulatedVelY;
    }

    private void resetTracking() {
        tracking = false;
        airTicks = 0;
        violationTicks = 0;
        cumulativeDeviation = 0;
        simulatedVelY = 0;
        expectedY = 0;
    }

    private void flag(String type, double value, int ticks) {
        String info = String.format("%s (val=%.2f ticks=%d)", type, value, ticks);
        fail(info);

        // Kick for persistent violations
        if (getViolationLevel() >= KICK_THRESHOLD) {
            Bukkit.getScheduler().runTask(GAC.getInstance(), () -> {
                Player player = user.getPlayer();
                if (player != null && player.isOnline()) {
                    GAC.incrementKicks();
                    player.kickPlayer("§b§lGAC \n\n§7Unfair Advantage detected.\n§fCheck: §bFly");
                }
            });
        }
    }

    private boolean shouldSkip(Player player) {
        if (player.isFlying() || player.getAllowFlight() ||
            player.getGameMode() == GameMode.CREATIVE ||
            player.getGameMode() == GameMode.SPECTATOR ||
            player.isInsideVehicle() || player.isGliding() ||
            player.isRiptiding() || player.isSwimming() ||
            user.isTeleporting() || user.isTakingVelocity() ||
            GAC.getTPS() < 18.0) return true;

        // Potion effects that change Y physics
        if (player.hasPotionEffect(PotionEffectType.JUMP_BOOST)) return true;
        if (player.hasPotionEffect(PotionEffectType.LEVITATION))  return true;
        if (player.hasPotionEffect(PotionEffectType.SLOW_FALLING)) return true;

        // Player is in water (includes bubble elevators)
        if (player.isInWater()) return true;

        // Special blocks at feet or head
        Material m = player.getLocation().getBlock().getType();
        if (m == Material.COBWEB || m == Material.HONEY_BLOCK || m == Material.SLIME_BLOCK
                || m == Material.POWDER_SNOW || m == Material.WATER || m == Material.LAVA
                || m == Material.BUBBLE_COLUMN) return true;

        return false;
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
        Material body = player.getLocation().clone().add(0, 1, 0).getBlock().getType();
        return mat == Material.WATER || mat == Material.LAVA ||
               body == Material.WATER || body == Material.LAVA ||
               mat.name().contains("WATER") || mat.name().contains("LAVA");
    }

    private boolean isClimbing(Player player) {
        String name = player.getLocation().getBlock().getType().name();
        return name.contains("LADDER") || name.contains("VINE") ||
               name.contains("SCAFFOLDING") || name.contains("WEEPING") ||
               name.contains("TWISTING") || name.contains("CAVE_VINES");
    }

    private boolean hasSlowFalling(Player player) {
        return player.hasPotionEffect(PotionEffectType.SLOW_FALLING);
    }

    private boolean hasLevitation(Player player) {
        return player.hasPotionEffect(PotionEffectType.LEVITATION);
    }

    public void onTeleport() {
        graceTicks = 40;
        resetTracking();
    }

    public void onVelocity() {
        // Reset tracking when velocity is received
        resetTracking();
        graceTicks = 10;
    }
}
