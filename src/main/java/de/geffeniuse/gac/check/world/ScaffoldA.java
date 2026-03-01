package de.geffeniuse.gac.check.world;

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
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;

import java.util.LinkedList;

/**
 * ScaffoldA - Scaffold/Tower Detection
 * Detects auto-bridging and towering hacks using BlockPlaceEvent.
 * Uses heuristics to avoid false positives on legitimate bridging.
 */
public class ScaffoldA extends Check implements Listener {

    private final LinkedList<Long> placeTimes = new LinkedList<>();
    private final LinkedList<Float> placePitches = new LinkedList<>();
    private final LinkedList<Double> placeAngles = new LinkedList<>(); // Yaw angles
    private Location lastPlaceLocation = null;
    private int consecutiveBridgePlacements = 0;
    private int suspicion = 0;
    private long lastPlaceTime = 0;

    // Thresholds - more lenient to avoid false positives
    private static final int MAX_BPS = 14; // Max blocks per second (legit is ~8-12)
    private static final double MIN_PITCH_VARIANCE = 0.5; // Very consistent pitch
    private static final double MIN_YAW_VARIANCE = 1.0; // Very consistent yaw
    private static final int CONSECUTIVE_THRESHOLD = 25; // More consecutive placements needed
    private static final int SUSPICION_THRESHOLD = 6; // Need multiple suspicious events
    private static final int KICK_THRESHOLD = 5;

    public ScaffoldA(GACUser user) {
        super(user, "Scaffold", "Detects scaffold/bridging hacks.");
        // Register as event listener
        Bukkit.getPluginManager().registerEvents(this, GAC.getInstance());
    }

    @Override
    public void onPacket(PacketEvent event) {
        // We use BlockPlaceEvent instead for more reliable detection
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();

        // Only check our player
        if (!player.getUniqueId().equals(user.getUuid())) return;

        // Skip creative
        if (player.getGameMode() == GameMode.CREATIVE) return;

        Block placed = event.getBlockPlaced();
        Block against = event.getBlockAgainst();
        Location playerLoc = player.getLocation();

        long now = System.currentTimeMillis();

        // Track placement timing
        placeTimes.add(now);
        placeTimes.removeIf(t -> now - t > 1000);

        int bps = placeTimes.size();

        // Track pitch and yaw for consistency check
        float pitch = player.getLocation().getPitch();
        float yaw = player.getLocation().getYaw();
        placePitches.add(pitch);
        placeAngles.add((double) yaw);
        while (placePitches.size() > 20) {
            placePitches.removeFirst();
        }
        while (placeAngles.size() > 20) {
            placeAngles.removeFirst();
        }

        // Check if bridging pattern
        boolean isBridging = false;

        // Block placed is below player's feet level
        if (placed.getY() <= playerLoc.getY() - 0.5) {
            Block below = playerLoc.clone().add(0, -1, 0).getBlock();
            if (!below.getType().isSolid() || below.equals(placed)) {
                isBridging = true;
            }
        }

        // Placing on side of block while looking very far down
        if (pitch > 75 && against.getY() == placed.getY()) {
            isBridging = true;
        }

        if (isBridging) {
            consecutiveBridgePlacements++;

            // ========== CHECK 1: Impossible BPS ==========
            // Legit players max around 10-12 BPS, scaffold is 15+
            if (bps > MAX_BPS) {
                suspicion += 2;
            }

            // ========== CHECK 2: Perfect pitch consistency ==========
            // Scaffold bots have EXACTLY the same pitch every time
            if (placePitches.size() >= 15) {
                double pitchVariance = getPitchVariance();
                if (pitchVariance < MIN_PITCH_VARIANCE) {
                    suspicion += 2;
                }
            }

            // ========== CHECK 3: Perfect yaw consistency ==========
            // Legit players rotate slightly, scaffold bots don't
            if (placeAngles.size() >= 15) {
                double yawVariance = getYawVariance();
                if (yawVariance < MIN_YAW_VARIANCE) {
                    suspicion += 2;
                }
            }

            // ========== CHECK 4: Impossible placement speed ==========
            // Time between placements (scaffold can place every 50ms, legit ~100ms+)
            if (lastPlaceTime > 0) {
                long timeBetween = now - lastPlaceTime;
                if (timeBetween < 60 && timeBetween > 0) { // Under 60ms is suspicious
                    suspicion += 1;
                }
            }

            // ========== CHECK 5: Long distance bridge with perfect consistency ==========
            if (consecutiveBridgePlacements > CONSECUTIVE_THRESHOLD && lastPlaceLocation != null) {
                double horizDist = Math.sqrt(
                    Math.pow(playerLoc.getX() - lastPlaceLocation.getX(), 2) +
                    Math.pow(playerLoc.getZ() - lastPlaceLocation.getZ(), 2)
                );

                // Long distance + very consistent = suspicious
                if (horizDist > 8 && getPitchVariance() < 1.0) {
                    suspicion += 3;
                }
            }

            // Flag only if enough suspicion accumulated
            if (suspicion >= SUSPICION_THRESHOLD) {
                fail(String.format("bps=%d pitch=%.1f consecutive=%d", bps, getPitchVariance(), consecutiveBridgePlacements));
                suspicion = 0;
                consecutiveBridgePlacements = 0;

                // Report to mitigation system - it handles trolling/kick
                user.getMitigation().onScaffoldViolation("timing");
            }

            if (consecutiveBridgePlacements == 1) {
                lastPlaceLocation = playerLoc.clone();
            }
            lastPlaceTime = now;
        } else {
            // Not bridging - decay
            consecutiveBridgePlacements = Math.max(0, consecutiveBridgePlacements - 2);
            suspicion = Math.max(0, suspicion - 1);
        }
    }

    private double getPitchVariance() {
        if (placePitches.size() < 2) return 999;

        double mean = 0;
        for (float p : placePitches) mean += p;
        mean /= placePitches.size();

        double variance = 0;
        for (float p : placePitches) {
            variance += Math.pow(p - mean, 2);
        }
        return Math.sqrt(variance / placePitches.size());
    }

    private double getYawVariance() {
        if (placeAngles.size() < 2) return 999;

        double mean = 0;
        for (double y : placeAngles) mean += y;
        mean /= placeAngles.size();

        double variance = 0;
        for (double y : placeAngles) {
            variance += Math.pow(y - mean, 2);
        }
        return Math.sqrt(variance / placeAngles.size());
    }
}
