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
import org.bukkit.entity.Boat;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

import java.util.LinkedList;
import java.util.Queue;

/**
 * JesusA - Advanced Water Walk Detection
 * Detects various Jesus hack methods including NCP/Vulcan/Watchdog bypasses.
 *
 * Detection methods:
 * 1. Ground spoof - Client claims onGround while on water
 * 2. Solid collision - Moving on water like it's solid
 * 3. Bounce pattern - Repeated small bounces on water surface
 * 4. Dolphin bypass - Rapid in/out of water to reset AC
 * 5. Y-level consistency - Staying at same Y on water too long
 */
public class JesusA extends Check {

    // Packet-based ground tracking
    private boolean packetOnGround = false;

    // Y-position tracking for bounce/consistency detection
    private final Queue<Double> yPositions = new LinkedList<>();
    private static final int Y_HISTORY_SIZE = 20;

    // Water contact tracking
    private int ticksOnWaterSurface = 0;
    private int ticksInWater = 0;
    private int ticksAboveWater = 0;
    private double lastWaterSurfaceY = -999;

    // Bypass detection counters
    private int waterEnterExitCount = 0;
    private long lastWaterStateChange = 0;
    private boolean lastInWater = false;

    // Bounce detection
    private int bounceCount = 0;
    private double lastBounceY = 0;
    private boolean wasRising = false;

    // Violation tracking
    private int violations = 0;
    private int groundSpoofVL = 0;
    private int solidVL = 0;
    private int bounceVL = 0;
    private int consistencyVL = 0;

    private static final int FLAG_THRESHOLD = 4;
    private static final int KICK_VL = 8;

    public JesusA(GACUser user) {
        super(user, "Jesus", "Detects walking on water.");
    }

    @Override
    public void onPacket(PacketEvent event) {
        // Capture onGround from packet
        if (event.getPacketType() == PacketType.Play.Client.POSITION ||
            event.getPacketType() == PacketType.Play.Client.POSITION_LOOK) {
            try {
                packetOnGround = event.getPacket().getBooleans().read(0);
            } catch (Exception ignored) {}
        }

        if (event.getPacketType() != PacketType.Play.Client.POSITION &&
            event.getPacketType() != PacketType.Play.Client.POSITION_LOOK) {
            return;
        }

        Player player = user.getPlayer();
        if (player == null) return;

        // Skip exempt conditions
        if (player.getGameMode() == GameMode.CREATIVE ||
            player.getGameMode() == GameMode.SPECTATOR ||
            player.isInsideVehicle() || player.isFlying() ||
            player.getAllowFlight() || player.isRiptiding() ||
            user.isTeleporting() || user.isTakingVelocity()) {
            resetState();
            return;
        }

        Bukkit.getScheduler().runTask(GAC.getInstance(), () -> {
            if (player == null || !player.isOnline()) return;

            // Skip if in boat or near boat (must be in main thread)
            if (isNearBoat(player)) {
                resetState();
                return;
            }

            Location loc = player.getLocation();
            double y = loc.getY();
            double deltaY = user.getDeltaY();
            double deltaXZ = user.getDeltaXZ();

            // Check water state
            boolean inWater = isInWater(loc);
            boolean onWaterSurface = isOnWaterSurface(loc);
            boolean aboveWater = isAboveWater(loc);
            boolean touchingWater = isTouchingWater(loc);

            // Skip checks
            if (hasFrostWalker(player)) {
                resetState();
                return;
            }

            // Swimming is allowed
            if (player.isSwimming() || inWater) {
                ticksInWater++;
                ticksOnWaterSurface = 0;
                ticksAboveWater = 0;
            } else if (onWaterSurface || (aboveWater && touchingWater)) {
                ticksOnWaterSurface++;
                ticksInWater = 0;
                ticksAboveWater = 0;
            } else if (aboveWater) {
                ticksAboveWater++;
                ticksOnWaterSurface = 0;
                ticksInWater = 0;
            } else {
                ticksOnWaterSurface = 0;
                ticksInWater = 0;
                ticksAboveWater = 0;
            }

            // Track Y positions
            yPositions.offer(y);
            if (yPositions.size() > Y_HISTORY_SIZE) {
                yPositions.poll();
            }

            // ========== CHECK 1: Ground Spoof ==========
            // Client claims onGround but is actually on water
            if (packetOnGround && (onWaterSurface || touchingWater) && !inWater) {
                // Make sure there's no solid block nearby
                if (!hasSolidBelow(loc)) {
                    groundSpoofVL++;
                    if (groundSpoofVL >= 3) {
                        flag("groundSpoof");
                        groundSpoofVL = 0;
                    }
                }
            } else {
                groundSpoofVL = Math.max(0, groundSpoofVL - 1);
            }

            // ========== CHECK 2: Solid Water (Walking normally on water) ==========
            // Moving horizontally on water surface like it's solid
            if (onWaterSurface && !inWater && deltaXZ > 0.1) {
                // Check if Y is stable (not sinking)
                if (Math.abs(deltaY) < 0.05 && ticksOnWaterSurface > 3) {
                    solidVL += 2;
                    if (solidVL >= 6) {
                        flag(String.format("solidWater speed=%.2f ticks=%d", deltaXZ, ticksOnWaterSurface));
                        solidVL = 0;

                        // Setback
                        setback(player, loc);
                    }
                }
                // Moving too fast on water without dolphin's grace
                else if (deltaXZ > 0.25 && !hasDolphinsGrace(player)) {
                    solidVL++;
                    if (solidVL >= 5) {
                        flag(String.format("waterSpeed=%.2f", deltaXZ));
                        solidVL = 0;
                    }
                }
            } else {
                solidVL = Math.max(0, solidVL - 1);
            }

            // ========== CHECK 3: Bounce Pattern (Dolphin/Legit Jesus) ==========
            // Detects rapid up/down motion on water surface
            boolean currentlyRising = deltaY > 0.01;

            if (touchingWater || onWaterSurface) {
                if (wasRising && !currentlyRising && Math.abs(y - lastBounceY) < 0.5) {
                    // Peak of bounce detected
                    bounceCount++;
                    lastBounceY = y;

                    // Too many bounces in sequence = Jesus
                    if (bounceCount >= 5 && deltaXZ > 0.1) {
                        bounceVL += 2;
                        if (bounceVL >= 4) {
                            flag(String.format("bounce count=%d", bounceCount));
                            bounceVL = 0;
                            bounceCount = 0;
                        }
                    }
                }
                wasRising = currentlyRising;
            } else {
                bounceCount = Math.max(0, bounceCount - 1);
                bounceVL = Math.max(0, bounceVL - 1);
            }

            // ========== CHECK 4: Y-Level Consistency ==========
            // Staying at exact same Y on water for too long
            if (onWaterSurface && yPositions.size() >= 10) {
                double yVariance = calculateYVariance();

                // Very low variance = not sinking naturally
                if (yVariance < 0.001 && deltaXZ > 0.05) {
                    consistencyVL++;
                    if (consistencyVL >= 8) {
                        flag(String.format("yConsistency var=%.4f", yVariance));
                        consistencyVL = 0;
                    }
                }
            } else {
                consistencyVL = Math.max(0, consistencyVL - 1);
            }

            // ========== CHECK 5: Water Enter/Exit Spam (NCP Bypass) ==========
            long now = System.currentTimeMillis();
            boolean currentInWater = inWater || touchingWater;

            if (currentInWater != lastInWater) {
                waterEnterExitCount++;
                lastWaterStateChange = now;
            }

            // Reset counter after 2 seconds of no changes
            if (now - lastWaterStateChange > 2000) {
                waterEnterExitCount = 0;
            }

            // Too many state changes while moving = bypass attempt
            if (waterEnterExitCount >= 8 && deltaXZ > 0.15) {
                flag(String.format("waterSpam count=%d", waterEnterExitCount));
                waterEnterExitCount = 0;

                setback(player, loc);
            }

            lastInWater = currentInWater;
            lastWaterSurfaceY = onWaterSurface ? y : lastWaterSurfaceY;
        });
    }

    private void flag(String info) {
        violations++;
        fail(info);

        if (violations >= KICK_VL) {
            Player player = user.getPlayer();
            if (player != null) {
                GAC.incrementKicks();
                player.kickPlayer("§b§lGAC \n\n§7Unfair Advantage detected.\n§fCheck: §bJesus");
            }
            violations = 0;
        }
    }

    private void setback(Player player, Location loc) {
        // Teleport into water
        Location setbackLoc = loc.clone();
        setbackLoc.setY(setbackLoc.getY() - 1.0);

        Bukkit.getScheduler().runTask(GAC.getInstance(), () -> {
            if (player.isOnline()) {
                player.teleport(setbackLoc);
            }
        });
    }

    private double calculateYVariance() {
        if (yPositions.size() < 2) return 999;

        double sum = 0;
        double sumSq = 0;
        int count = 0;

        for (Double y : yPositions) {
            sum += y;
            sumSq += y * y;
            count++;
        }

        double mean = sum / count;
        return (sumSq / count) - (mean * mean);
    }

    private void resetState() {
        ticksOnWaterSurface = 0;
        ticksInWater = 0;
        ticksAboveWater = 0;
        groundSpoofVL = 0;
        solidVL = 0;
        bounceVL = 0;
        consistencyVL = 0;
        bounceCount = 0;
    }

    private boolean isOnWaterSurface(Location loc) {
        // Feet in air, water below
        Block feetBlock = loc.getBlock();
        if (isWaterOrLava(feetBlock.getType())) {
            return false;
        }

        Block below = loc.clone().add(0, -0.3, 0).getBlock();
        return isWaterOrLava(below.getType());
    }

    private boolean isInWater(Location loc) {
        Block feetBlock = loc.getBlock();
        Block bodyBlock = loc.clone().add(0, 0.4, 0).getBlock();
        return isWaterOrLava(feetBlock.getType()) || isWaterOrLava(bodyBlock.getType());
    }

    private boolean isAboveWater(Location loc) {
        for (double y = -0.5; y >= -3; y -= 0.5) {
            Block block = loc.clone().add(0, y, 0).getBlock();
            if (isWaterOrLava(block.getType())) {
                return true;
            }
            if (block.getType().isSolid()) {
                return false;
            }
        }
        return false;
    }

    private boolean isTouchingWater(Location loc) {
        // Check all around player hitbox
        for (double x = -0.3; x <= 0.3; x += 0.3) {
            for (double z = -0.3; z <= 0.3; z += 0.3) {
                for (double y = 0; y <= 1.8; y += 0.5) {
                    Block block = loc.clone().add(x, y, z).getBlock();
                    if (isWaterOrLava(block.getType())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean hasSolidBelow(Location loc) {
        for (double x = -0.3; x <= 0.3; x += 0.3) {
            for (double z = -0.3; z <= 0.3; z += 0.3) {
                Block block = loc.clone().add(x, -0.1, z).getBlock();
                if (block.getType().isSolid()) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isWaterOrLava(Material mat) {
        return mat == Material.WATER || mat == Material.LAVA;
    }

    private boolean isNearBoat(Player player) {
        return player.getNearbyEntities(2, 2, 2).stream()
                .anyMatch(e -> e instanceof Boat);
    }

    private boolean hasFrostWalker(Player player) {
        try {
            if (player.getInventory().getBoots() != null) {
                return player.getInventory().getBoots().containsEnchantment(
                    org.bukkit.enchantments.Enchantment.FROST_WALKER);
            }
        } catch (Exception ignored) {}
        return false;
    }

    private boolean hasDolphinsGrace(Player player) {
        return player.hasPotionEffect(PotionEffectType.DOLPHINS_GRACE);
    }
}
