package de.geffeniuse.gac.check.movement;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketEvent;
import de.geffeniuse.gac.check.Check;
import de.geffeniuse.gac.data.GACUser;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

/**
 * SimulationA - Y-axis velocity prediction (GrimAC-inspired)
 *
 * Predicts Y velocity deterministically (gravity doesn't depend on player inputs).
 * X/Z movement prediction is skipped — it requires knowing exact client inputs,
 * which leads to false positives. Only Y (fly/hover) is checked here.
 *
 * Physics:
 *   velY_next = (velY - GRAVITY) * DRAG   (when in air)
 *   velY = 0                               (when landing)
 *   velY = JUMP_VEL                        (when jumping)
 */
public class SimulationA extends Check {

    // Minecraft physics constants
    private static final double GRAVITY  = 0.08;
    private static final double DRAG     = 0.98;
    private static final double JUMP_VEL = 0.42;

    // Tolerance to account for floating-point drift and minor inaccuracies
    private static final double THRESHOLD = 0.15;

    // State
    private double velY       = 0;
    private double lastY      = 0;
    private boolean lastGround = true;
    private int airTicks      = 0;
    private int graceTicks    = 80;
    private boolean initialized = false;

    // Buffer before flagging — reduces false positives
    private int buffer = 0;
    private static final int BUFFER_MAX = 5;

    // Last safe location for setback
    private Location safeLocation = null;

    public SimulationA(GACUser user) {
        super(user, "Simulation", "Y-axis gravity prediction (fly detection).");
    }

    @Override
    public void onPacket(PacketEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.POSITION &&
            event.getPacketType() != PacketType.Play.Client.POSITION_LOOK) return;

        Player player = user.getPlayer();
        if (player == null || !player.isOnline()) return;
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;

        double targetY    = event.getPacket().getDoubles().read(1);
        boolean onGround  = event.getPacket().getBooleans().read(0);

        // Grace period: sync state without checking (after join, teleport, etc.)
        if (!initialized || graceTicks > 0 || user.isTeleporting()) {
            if (graceTicks > 0) graceTicks--;
            syncState(targetY, onGround);
            initialized = true;
            return;
        }

        // Skip check if in an exempt state
        if (isExempt(player)) {
            syncState(targetY, onGround);
            buffer = Math.max(0, buffer - 1);
            updateSafeLocation(player, onGround);
            return;
        }

        double actualVelY = targetY - lastY;

        if (lastGround) {
            // Was on ground last tick
            airTicks = 0;
            buffer = Math.max(0, buffer - 1);

            if (!onGround) {
                // Left ground: either jumped or walked off an edge
                // Jumping gives velY = JUMP_VEL (modified by Jump Boost)
                // Walking off an edge gives velY = 0 → then falls
                velY = actualVelY; // Trust what the client sends on ground-leave tick
            } else {
                velY = 0;
            }
        } else {
            // Was in air last tick
            airTicks++;

            if (onGround) {
                // Landed — always valid, reset
                airTicks = 0;
                buffer = Math.max(0, buffer - 1);
                velY = 0;
            } else {
                // Still in air: predict Y velocity
                double predictedVelY = (velY - GRAVITY) * DRAG;

                // Allow 1-tick grace when first entering air (step-up, edge cases)
                if (airTicks >= 2) {
                    double diff = actualVelY - predictedVelY;

                    // Hovering or moving up when should be falling
                    if (diff > THRESHOLD) {
                        buffer++;
                        if (buffer >= BUFFER_MAX) {
                            fail(String.format("hover velY=%.4f expected=%.4f diff=%.4f air=%d",
                                    actualVelY, predictedVelY, diff, airTicks));

                            // Setback
                            if (safeLocation != null && buffer >= BUFFER_MAX + 3) {
                                Location sb = safeLocation;
                                org.bukkit.Bukkit.getScheduler().runTask(
                                    de.geffeniuse.gac.GAC.getInstance(),
                                    () -> { if (player.isOnline()) player.teleport(sb); }
                                );
                                graceTicks = 20;
                                buffer = 0;
                            }
                        }
                    } else {
                        buffer = Math.max(0, buffer - 1);
                    }
                }

                // Update predicted velocity from actual (keep simulation in sync)
                // If movement was close to prediction, trust prediction; otherwise trust actual
                // This prevents error accumulation
                if (Math.abs(actualVelY - predictedVelY) < THRESHOLD) {
                    velY = predictedVelY;
                } else {
                    velY = actualVelY; // resync
                }
            }
        }

        lastY      = targetY;
        lastGround = onGround;
        updateSafeLocation(player, onGround);
    }

    private void syncState(double y, boolean ground) {
        lastY      = y;
        lastGround = ground;
        velY       = 0;
        airTicks   = 0;
    }

    private void updateSafeLocation(Player player, boolean onGround) {
        if (onGround) {
            safeLocation = player.getLocation().clone();
        }
    }

    /**
     * Exempt states where Y prediction is unreliable.
     * GrimAC also exempts these — the physics model doesn't cover them.
     */
    private boolean isExempt(Player player) {
        if (player.isFlying() || player.isGliding() || player.isRiptiding()) return true;
        if (player.isSwimming() || player.isInWater())                        return true;
        if (user.isTakingVelocity())                                          return true;

        // Potion effects that change Y physics
        if (player.hasPotionEffect(PotionEffectType.JUMP_BOOST))   return true;
        if (player.hasPotionEffect(PotionEffectType.LEVITATION))   return true;
        if (player.hasPotionEffect(PotionEffectType.SLOW_FALLING)) return true;

        // Special blocks that affect Y movement
        return isOnSpecialBlock(player);
    }

    private boolean isOnSpecialBlock(Player player) {
        Location loc = player.getLocation();
        // Check feet, one below, one above
        Location[] positions = {
            loc,
            loc.clone().add(0, -0.5, 0),
            loc.clone().add(0,  1.0, 0)
        };
        for (Location pos : positions) {
            Material m = pos.getBlock().getType();
            if (m == Material.SLIME_BLOCK
                    || m == Material.HONEY_BLOCK
                    || m == Material.COBWEB
                    || m == Material.POWDER_SNOW
                    || m == Material.SCAFFOLDING
                    || m == Material.WATER
                    || m == Material.LAVA
                    || m.name().contains("LADDER")
                    || m.name().contains("VINE")) {
                return true;
            }
        }
        return false;
    }
}
