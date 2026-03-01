package de.geffeniuse.gac.check.world;

import de.geffeniuse.gac.GAC;
import de.geffeniuse.gac.check.Check;
import de.geffeniuse.gac.data.GACUser;
import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;

import java.util.LinkedList;

/**
 * ScaffoldD - Telly/NCP Scaffold Detection
 *
 * Specifically targets:
 * - Telly scaffold (placing while jumping/sprinting)
 * - Sprint bridging without sneaking
 * - Tower building (upward scaffold)
 * - Impossible placement angles
 */
public class ScaffoldD extends Check implements Listener {

    // Placement tracking
    private final LinkedList<PlacementData> recentPlacements = new LinkedList<>();
    private static final int PLACEMENT_HISTORY = 15;

    // State tracking
    private int airPlaceCount = 0;
    private int sprintPlaceCount = 0;
    private int towerCount = 0;
    private double lastPlaceY = 0;
    private long lastPlaceTime = 0;
    private Location lastPlaceLoc = null;

    // Violation tracking
    private int tellyVL = 0;
    private int sprintVL = 0;
    private int towerVL = 0;
    private int impossibleVL = 0;

    public ScaffoldD(GACUser user) {
        super(user, "Scaffold (Telly)", "Detects Telly and sprint scaffold.");
        Bukkit.getPluginManager().registerEvents(this, GAC.getInstance());
    }

    @Override
    public void onPacket(PacketEvent event) {
        // Track movement state for scaffold checks
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (event.isCancelled()) return;

        Player player = event.getPlayer();
        if (!player.getUniqueId().equals(user.getUuid())) return;
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;

        Block placed = event.getBlockPlaced();
        Block against = event.getBlockAgainst();
        Location playerLoc = player.getLocation();
        long now = System.currentTimeMillis();

        // Only check scaffold-like placements (below or at feet level)
        boolean isScaffoldPlacement = placed.getY() <= playerLoc.getY() &&
                                      placed.getY() >= playerLoc.getY() - 2;

        if (!isScaffoldPlacement) {
            // Check for tower (placing above)
            if (placed.getY() > playerLoc.getY() && against.getY() <= playerLoc.getY()) {
                checkTower(player, placed, now);
            }
            return;
        }

        // Record placement
        PlacementData data = new PlacementData(
            now,
            playerLoc.clone(),
            placed.getLocation(),
            player.isOnGround(),
            player.isSprinting(),
            player.isSneaking(),
            player.getLocation().getPitch(),
            player.getLocation().getYaw(),
            user.getDeltaXZ(),
            user.getDeltaY()
        );
        recentPlacements.addFirst(data);
        while (recentPlacements.size() > PLACEMENT_HISTORY) {
            recentPlacements.removeLast();
        }

        // ========== CHECK 1: Telly (Air placement while moving HORIZONTALLY fast) ==========
        // IMPORTANT: Exclude normal towering (vertical stacking)
        // Towering: high deltaY, low deltaXZ, looking straight down (pitch > 80)
        // Telly: high deltaXZ, placing below while jumping forward

        boolean isTowering = user.getDeltaY() > 0.2 || // Moving up significantly
                             player.getLocation().getPitch() > 80 || // Looking straight down
                             (lastPlaceLoc != null && placed.getY() > lastPlaceLoc.getY()); // Placing higher than before

        if (!player.isOnGround() && user.getDeltaXZ() > 0.2 && !isTowering) {
            airPlaceCount++;

            // Telly pattern: multiple air placements in sequence while moving horizontally
            if (airPlaceCount >= 4) {
                tellyVL += 2;

                if (tellyVL >= 8) {
                    flag("telly", String.format("air=%d speed=%.2f", airPlaceCount, user.getDeltaXZ()));
                    tellyVL = 2;
                    airPlaceCount = 0;
                }
            }
        } else {
            airPlaceCount = Math.max(0, airPlaceCount - 1);
            tellyVL = Math.max(0, tellyVL - 1);
        }

        // ========== CHECK 2: Sprint scaffolding without sneak ==========
        if (player.isSprinting() && !player.isSneaking() && player.isOnGround()) {
            // Placing below while sprinting forward is very hard without sneak
            double deltaXZ = user.getDeltaXZ();

            if (deltaXZ > 0.2) {
                sprintPlaceCount++;

                if (sprintPlaceCount >= 4) {
                    sprintVL += 2;

                    if (sprintVL >= 6) {
                        flag("sprint", String.format("nosneak speed=%.2f count=%d", deltaXZ, sprintPlaceCount));
                        sprintVL = 2;
                        sprintPlaceCount = 0;
                    }
                }
            }
        } else {
            sprintPlaceCount = Math.max(0, sprintPlaceCount - 1);
            sprintVL = Math.max(0, sprintVL - 1);
        }

        // ========== CHECK 3: Impossible placement angle ==========
        // Placing behind while moving forward fast
        if (recentPlacements.size() >= 3) {
            double moveYaw = Math.toDegrees(Math.atan2(user.getDeltaX(), user.getDeltaZ()));
            double lookYaw = player.getLocation().getYaw();
            double angleDiff = Math.abs(normalizeAngle(moveYaw - lookYaw));

            // Moving one direction, looking opposite (behind) while placing fast
            if (angleDiff > 120 && user.getDeltaXZ() > 0.2 && !player.isSneaking()) {
                impossibleVL += 2;

                if (impossibleVL >= 6) {
                    flag("angle", String.format("diff=%.1f speed=%.2f", angleDiff, user.getDeltaXZ()));
                    impossibleVL = 2;
                }
            } else {
                impossibleVL = Math.max(0, impossibleVL - 1);
            }
        }

        // ========== CHECK 4: Consistent timing pattern (bot-like) ==========
        if (recentPlacements.size() >= 5) {
            checkTimingPattern();
        }

        // ========== CHECK 5: Diagonal bridging at high speed ==========
        if (lastPlaceLoc != null && player.isOnGround()) {
            double dx = Math.abs(placed.getX() - lastPlaceLoc.getX());
            double dz = Math.abs(placed.getZ() - lastPlaceLoc.getZ());

            // Diagonal placement (both X and Z changed)
            if (dx >= 1 && dz >= 1 && user.getDeltaXZ() > 0.25) {
                // Diagonal bridging at sprint speed is very hard
                if (!player.isSneaking()) {
                    tellyVL += 2;
                }
            }
        }

        lastPlaceY = placed.getY();
        lastPlaceTime = now;
        lastPlaceLoc = placed.getLocation();
    }

    private void checkTower(Player player, Block placed, long now) {
        // Tower detection: VERY rapid upward block placement (inhuman speed)
        // Normal towering is ~250-400ms per block, hacks are <150ms
        if (placed.getY() > lastPlaceY && lastPlaceTime > 0) {
            long timeDiff = now - lastPlaceTime;

            // Only flag VERY fast tower placement (< 120ms is impossible legit)
            if (timeDiff < 120 && timeDiff > 0) {
                towerCount++;

                if (towerCount >= 5) {
                    towerVL += 2;

                    if (towerVL >= 10) {
                        flag("tower", String.format("speed=%dms count=%d", timeDiff, towerCount));
                        towerVL = 3;
                        towerCount = 0;
                    }
                }
            } else {
                // Normal speed, decay
                towerCount = Math.max(0, towerCount - 1);
            }
        } else {
            towerCount = Math.max(0, towerCount - 1);
            towerVL = Math.max(0, towerVL - 1);
        }

        lastPlaceY = placed.getY();
        lastPlaceTime = now;
    }

    private void checkTimingPattern() {
        // Calculate time differences between placements
        long[] diffs = new long[recentPlacements.size() - 1];
        int i = 0;
        PlacementData prev = null;

        for (PlacementData data : recentPlacements) {
            if (prev != null && i < diffs.length) {
                diffs[i++] = prev.time - data.time;
            }
            prev = data;
        }

        if (i < 5) return; // Need more samples

        // Calculate variance of timing
        double mean = 0;
        for (int j = 0; j < i; j++) mean += diffs[j];
        mean /= i;

        double variance = 0;
        for (int j = 0; j < i; j++) {
            variance += Math.pow(diffs[j] - mean, 2);
        }
        variance /= i;

        // Very consistent timing (low variance) = bot
        // Humans have variance of 500+ usually, bots < 20
        // Only flag if VERY bot-like (variance < 20 and fast placement < 120ms)
        if (variance < 20 && mean < 120) {
            tellyVL += 1;
        }
    }

    private void flag(String type, String info) {
        fail(String.format("%s %s", type, info));

        // Report to mitigation system - it handles trolling and eventual kick
        user.getMitigation().onScaffoldViolation(type);
    }

    private double normalizeAngle(double angle) {
        while (angle > 180) angle -= 360;
        while (angle < -180) angle += 360;
        return angle;
    }

    private static class PlacementData {
        final long time;
        final Location playerLoc;
        final Location blockLoc;
        final boolean onGround;
        final boolean sprinting;
        final boolean sneaking;
        final float pitch;
        final float yaw;
        final double deltaXZ;
        final double deltaY;

        PlacementData(long time, Location playerLoc, Location blockLoc, boolean onGround,
                      boolean sprinting, boolean sneaking, float pitch, float yaw,
                      double deltaXZ, double deltaY) {
            this.time = time;
            this.playerLoc = playerLoc;
            this.blockLoc = blockLoc;
            this.onGround = onGround;
            this.sprinting = sprinting;
            this.sneaking = sneaking;
            this.pitch = pitch;
            this.yaw = yaw;
            this.deltaXZ = deltaXZ;
            this.deltaY = deltaY;
        }
    }
}
