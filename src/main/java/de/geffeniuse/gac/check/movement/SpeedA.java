package de.geffeniuse.gac.check.movement;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketEvent;
import de.geffeniuse.gac.GAC;
import de.geffeniuse.gac.check.Check;
import de.geffeniuse.gac.data.GACUser;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * SpeedA - Speed Hack Detection
 * Detects players moving faster than possible.
 */
public class SpeedA extends Check {

    private int violations = 0;
    private long lastCheck = 0;
    private long lastGlidingTime = 0;

    // Y-Port detection
    private int airTicks = 0;
    private int groundTicks = 0;
    private double airSpeedSum = 0;
    private int yPortViolations = 0;

    // Base speeds (blocks per tick)
    private static final double BASE_WALK = 0.22;
    private static final double BASE_SPRINT = 0.29;
    private static final double BASE_SNEAK = 0.065;
    private static final double BASE_AIR = 0.36; // Max air strafe speed

    // Multipliers
    private static final double SPEED_EFFECT_MULT = 0.2; // 20% per level
    private static final double ICE_MULT = 1.5;
    private static final double BUFFER = 1.15; // 15% tolerance - more strict

    private static final int KICK_THRESHOLD = 8;

    public SpeedA(GACUser user) {
        super(user, "Speed", "Detects speed hacks.");
    }

    @Override
    public void onPacket(PacketEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.POSITION &&
            event.getPacketType() != PacketType.Play.Client.POSITION_LOOK) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastCheck < 50) return; // Check every 50ms
        lastCheck = now;

        Player player = user.getPlayer();
        if (player == null) return;

        // Track elytra usage for grace period
        if (player.isGliding()) lastGlidingTime = System.currentTimeMillis();

        // Skip checks for certain conditions
        if (player.isFlying() ||
            player.getGameMode() == GameMode.CREATIVE ||
            player.getGameMode() == GameMode.SPECTATOR ||
            player.isInsideVehicle() || player.isGliding() ||
            player.isRiptiding() || player.isSwimming() ||
            user.isTeleporting() || user.isTakingVelocity()) {
            violations = 0;
            return;
        }

        // Grace period after elytra — player still has high momentum after landing
        if (System.currentTimeMillis() - lastGlidingTime < 3000) {
            violations = 0;
            return;
        }

        // Skip if bridging (sneaking + looking down + at edge)
        // FastBridge causes speed spikes when moving at block edges
        if (player.isSneaking() && player.getLocation().getPitch() > 60) {
            // Looking down while sneaking = probably bridging
            return;
        }

        // Server Lag Protection
        if (GAC.getTPS() < 18.0) return;

        double deltaXZ = user.getDeltaXZ();
        double deltaY = user.getDeltaY();
        boolean onGround = player.isOnGround();

        // === BLATANT SPEED CHECK ===
        // Normal sprint-jump: ~0.3-0.4, with speed 2: ~0.5-0.6
        // Speed hack 5.0: ~0.8-1.5 blocks/tick
        // Only flag VERY high speeds to avoid false positives
        double blatantThreshold = 0.8;  // Very high - only catches blatant speed hacks

        if (deltaXZ > blatantThreshold && !player.isGliding() && !user.isTakingVelocity()) {
            violations++;
            GAC.getInstance().getLogger().warning("[SPEED] BLATANT " + player.getName() +
                " speed=" + String.format("%.2f", deltaXZ) + " VL=" + violations);

            if (violations >= 5) {  // Need 5 consecutive high speeds
                fail("BLATANT speed=" + String.format("%.2f", deltaXZ));
                violations = 0;

                if (getViolationLevel() >= KICK_THRESHOLD) {
                    GAC.incrementKicks();
                    Bukkit.getScheduler().runTask(GAC.getInstance(), () -> {
                        if (player.isOnline()) {
                            player.kickPlayer("§b§lGAC \n\n§7Unfair Advantage detected.\n§fCheck: §bSpeed");
                        }
                    });
                }
            }
        } else if (deltaXZ < 0.4) {
            // Decay violations when moving normally
            violations = Math.max(0, violations - 1);
        }

        // Y-Port Detection: Track air/ground pattern
        if (!onGround) {
            airTicks++;
            airSpeedSum += deltaXZ;
            groundTicks = 0;
        } else {
            // Just landed - check for Y-Port pattern
            if (airTicks > 0 && airTicks <= 4) {
                // Short air time (1-4 ticks) = Y-Port pattern
                double avgAirSpeed = airSpeedSum / airTicks;
                double maxAirSpeed = BASE_AIR * getSpeedMultiplier(player);

                if (avgAirSpeed > maxAirSpeed) {
                    yPortViolations++;
                    if (yPortViolations >= 3) {  // Reduced from 8
                        fail("YPort speed=" + String.format("%.2f", avgAirSpeed) + " max=" + String.format("%.2f", maxAirSpeed));
                        yPortViolations = 0;

                        if (getViolationLevel() >= KICK_THRESHOLD) {
                            GAC.incrementKicks();
                            player.kickPlayer("§cKicked by GAC");
                        }
                    }
                }
            }
            airTicks = 0;
            airSpeedSum = 0;
            groundTicks++;

            // Decay y-port violations when walking normally
            if (groundTicks > 10) {
                yPortViolations = Math.max(0, yPortViolations - 1);
            }
        }

        // Calculate max allowed speed
        Bukkit.getScheduler().runTask(GAC.getInstance(), () -> {
            if (player == null || !player.isOnline() || !isEnabled()) return;

            double maxSpeed = calculateMaxSpeed(player);

            // Add ping-based tolerance (high ping = more lenient)
            int ping = player.getPing();
            if (ping > 50) {
                maxSpeed *= 1.0 + (ping / 400.0); // Up to 25% extra at 100ms ping
            }

            // Extra tolerance during vertical movement (but not infinite)
            if (Math.abs(deltaY) > 0.3) {
                maxSpeed *= 1.2;
            }

            if (deltaXZ > maxSpeed) {
                violations++;

                if (violations >= 3) {  // Reduced from 8
                    fail("speed=" + String.format("%.2f", deltaXZ) + " max=" + String.format("%.2f", maxSpeed));
                    violations = 0;

                    if (getViolationLevel() >= KICK_THRESHOLD) {
                        GAC.incrementKicks();
                        player.kickPlayer("§b§lGAC \n\n§7Unfair Advantage detected.\n§fCheck: §bSpeed");
                    }
                }
            } else {
                violations = Math.max(0, violations - 1); // Slower decay
            }
        });
    }

    private double getSpeedMultiplier(Player player) {
        double mult = 1.0;
        PotionEffect speed = player.getPotionEffect(PotionEffectType.SPEED);
        if (speed != null) {
            mult *= 1.0 + (speed.getAmplifier() + 1) * SPEED_EFFECT_MULT;
        }
        return mult * BUFFER;
    }

    private double calculateMaxSpeed(Player player) {
        // If in air, sneaking doesn't slow you down (physics wise you have momentum)
        if (!isOnGround(player)) {
             return BASE_SPRINT * BUFFER; 
        }

        double maxSpeed = BASE_WALK;

        if (player.isSprinting()) {
            maxSpeed = BASE_SPRINT;
        } else if (player.isSneaking()) {
            maxSpeed = BASE_SNEAK;
            
            // Handle Swift Sneak
            ItemStack leggings = player.getInventory().getLeggings();
            if (leggings != null && leggings.hasItemMeta()) {
                 int level = leggings.getEnchantmentLevel(Enchantment.SWIFT_SNEAK);
                 if (level > 0) {
                     // Speed becomes 45%, 60%, 75% of walking speed
                     double multiplier = 0.30 + (level * 0.15); 
                     maxSpeed = BASE_WALK * multiplier;
                 }
            }
        }

        // Speed effect
        PotionEffect speed = player.getPotionEffect(PotionEffectType.SPEED);
        if (speed != null) {
            maxSpeed *= 1.0 + (speed.getAmplifier() + 1) * SPEED_EFFECT_MULT;
        }

        // Slowness effect
        PotionEffect slow = player.getPotionEffect(PotionEffectType.SLOWNESS);
        if (slow != null) {
            maxSpeed *= 1.0 - (slow.getAmplifier() + 1) * 0.15;
        }

        // Ice
        if (isOnIce(player)) {
            maxSpeed *= ICE_MULT;
        }

        // Soul sand
        if (isOnSoulSand(player)) {
            maxSpeed *= 0.4;
        }

        return maxSpeed * BUFFER;
    }

    private boolean isOnIce(Player player) {
        String blockName = player.getLocation().clone().add(0, -0.1, 0).getBlock().getType().name();
        return blockName.contains("ICE");
    }

    private boolean isOnSoulSand(Player player) {
        String blockName = player.getLocation().clone().add(0, -0.1, 0).getBlock().getType().name();
        return blockName.contains("SOUL");
    }

    private boolean isOnGround(Player player) {
        org.bukkit.Location loc = player.getLocation();
        for (double x = -0.3; x <= 0.3; x += 0.3) {
            for (double z = -0.3; z <= 0.3; z += 0.3) {
                if (loc.clone().add(x, -0.1, z).getBlock().getType().isSolid()) {
                    return true;
                }
            }
        }
        return false;
    }
}
