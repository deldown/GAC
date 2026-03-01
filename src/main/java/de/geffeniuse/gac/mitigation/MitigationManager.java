package de.geffeniuse.gac.mitigation;

import de.geffeniuse.gac.GAC;
import de.geffeniuse.gac.data.GACUser;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.concurrent.ThreadLocalRandom;

/**
 * MitigationManager - Cancel/Reduce cheats instead of kicking
 *
 * Philosophy: Don't kick unless 100% certain. Instead:
 * - Cancel illegal hits (Reach, Killaura)
 * - Cancel illegal block breaks (FastBreak)
 * - Setback illegal movement (Speed, Fly)
 * - TROLL scaffold users (cancel, ghost blocks, delayed removal)
 *
 * ML Integration:
 * - ML provides confidence scores, NOT punishment decisions
 * - Confidence scores influence mitigation intensity
 * - Mitigations are subtle and asymmetric (cheater doesn't know why they're failing)
 *
 * This reduces impact of false positives while still stopping cheaters.
 */
public class MitigationManager {

    private final GACUser user;

    // Mitigation state
    private boolean reachMitigation = false;
    private boolean killauraMitigation = false;
    private boolean speedMitigation = false;
    private boolean scaffoldMitigation = false;

    // Mitigation levels (higher = more aggressive)
    private int reachLevel = 0;
    private int killauraLevel = 0;
    private int speedLevel = 0;
    private int scaffoldLevel = 0;

    // Hit cancellation
    private int hitsToCancel = 0;
    private int hitsCancelled = 0;

    // Block place cancellation
    private int placesToCancel = 0;
    private int placesCancelled = 0;

    // Setback
    private Location lastValidLocation = null;
    private int setbackCount = 0;

    // Thresholds for different actions
    private static final int CANCEL_THRESHOLD = 3;      // Start cancelling
    private static final int SETBACK_THRESHOLD = 6;     // Start setbacks
    private static final int KICK_THRESHOLD = 25;       // Only kick if VERY certain (raised for scaffold)

    // Scaffold troll thresholds
    private static final int SCAFFOLD_TROLL_START = 3;   // Start light trolling
    private static final int SCAFFOLD_GHOST_START = 8;   // Start ghost blocks
    private static final int SCAFFOLD_HEAVY_START = 15;  // Heavy trolling
    private static final int SCAFFOLD_KICK = 30;         // Finally kick

    // Decay tracking
    private long lastScaffoldViolation = 0;
    private static final long SCAFFOLD_DECAY_INTERVAL = 3000; // Decay every 3 seconds of no violations

    // ML Cloud mitigation values (0.0 - 1.0)
    private double mlReachReduction = 0.0;
    private double mlHitRegLoss = 0.0;
    private double mlBlockFailRate = 0.0;
    private double mlSpeedReduction = 0.0;
    private long lastMLUpdate = 0;
    private static final long ML_UPDATE_INTERVAL = 10000; // Update ML values every 10 seconds

    public MitigationManager(GACUser user) {
        this.user = user;

        // Start passive decay task - reduces levels over time when no violations
        Bukkit.getScheduler().runTaskTimer(GAC.getInstance(), this::passiveDecay, 60L, 60L); // Every 3 seconds

    }

    /**
     * Passive decay - reduces violation levels over time when no new violations
     */
    private void passiveDecay() {
        long now = System.currentTimeMillis();

        // Scaffold decay - if no violation in last 3 seconds, reduce level
        if (scaffoldLevel > 0 && now - lastScaffoldViolation > SCAFFOLD_DECAY_INTERVAL) {
            scaffoldLevel = Math.max(0, scaffoldLevel - 1);
            if (scaffoldLevel < SCAFFOLD_TROLL_START) {
                scaffoldMitigation = false;
            }
        }

        // Also decay other levels passively
        if (reachLevel > 0 && !reachMitigation) {
            reachLevel = Math.max(0, reachLevel - 1);
        }
        if (killauraLevel > 0 && !killauraMitigation) {
            killauraLevel = Math.max(0, killauraLevel - 1);
        }
        if (speedLevel > 0 && !speedMitigation) {
            speedLevel = Math.max(0, speedLevel - 1);
        }
    }

    /**
     * Report a reach violation
     * @return true if the hit should be cancelled
     */
    public boolean onReachViolation(double reach, double maxReach) {
        reachLevel++;

        if (reachLevel >= CANCEL_THRESHOLD) {
            reachMitigation = true;
            hitsToCancel = Math.min(hitsToCancel + 2, 10);

            // Log but don't kick unless extreme
            if (reachLevel >= KICK_THRESHOLD && reach > maxReach + 1.0) {
                // Only kick for blatant reach (4+ blocks)
                return true; // Signal to kick
            }
        }

        // Decay
        Bukkit.getScheduler().runTaskLater(GAC.getInstance(), () -> {
            reachLevel = Math.max(0, reachLevel - 1);
            if (reachLevel < CANCEL_THRESHOLD) {
                reachMitigation = false;
            }
        }, 100L); // 5 seconds

        return false;
    }

    /**
     * Check if a hit should be cancelled
     * ONLY uses Cloud ML - local checks do NOT cancel hits
     * @return true if hit should be cancelled
     */
    public boolean shouldCancelHit() {
        // Local mitigation based on violation levels
        if (reachMitigation || killauraMitigation) {
            if (hitsToCancel > 0) {
                hitsToCancel--;
                hitsCancelled++;
                return true;
            }
        }
        return false;
    }

    public double getEffectiveReachReduction() {
        return reachMitigation ? Math.min(0.5, reachLevel * 0.05) : 0.0;
    }

    public double getEffectiveSpeedReduction() {
        return speedMitigation ? Math.min(0.3, speedLevel * 0.04) : 0.0;
    }

    /**
     * Report a killaura violation
     */
    public void onKillauraViolation(String type) {
        killauraLevel++;

        if (killauraLevel >= CANCEL_THRESHOLD) {
            killauraMitigation = true;
            hitsToCancel = Math.min(hitsToCancel + 1, 5);
        }

        // Decay
        Bukkit.getScheduler().runTaskLater(GAC.getInstance(), () -> {
            killauraLevel = Math.max(0, killauraLevel - 1);
            if (killauraLevel < CANCEL_THRESHOLD) {
                killauraMitigation = false;
            }
        }, 200L); // 10 seconds
    }

    /**
     * Report a speed/fly violation
     * @return true if player should be set back
     */
    public boolean onMovementViolation(String type, double severity) {
        speedLevel++;

        if (speedLevel >= SETBACK_THRESHOLD) {
            speedMitigation = true;
            return true; // Signal setback needed
        }

        // Decay
        Bukkit.getScheduler().runTaskLater(GAC.getInstance(), () -> {
            speedLevel = Math.max(0, speedLevel - 1);
            if (speedLevel < SETBACK_THRESHOLD) {
                speedMitigation = false;
            }
        }, 60L); // 3 seconds

        return false;
    }

    /**
     * Perform setback to last valid location
     */
    public void setback() {
        Player player = user.getPlayer();
        if (player == null || lastValidLocation == null) return;

        setbackCount++;

        Bukkit.getScheduler().runTask(GAC.getInstance(), () -> {
            if (player.isOnline()) {
                player.teleport(lastValidLocation);
            }
        });
    }

    /**
     * Record a valid location (for setbacks)
     */
    public void recordValidLocation(Location loc) {
        this.lastValidLocation = loc.clone();
    }

    /**
     * Report a scaffold violation - TROLLING SYSTEM
     * Call this when a scaffold check DETECTS a violation
     * @return ScaffoldAction telling what to do with the block
     */
    public ScaffoldAction onScaffoldViolation(String type) {
        scaffoldLevel++;
        scaffoldMitigation = true;
        lastScaffoldViolation = System.currentTimeMillis();

        // KICK CHECK - only after many violations
        if (scaffoldLevel >= SCAFFOLD_KICK) {
            return ScaffoldAction.KICK;
        }

        // Determine troll action based on level
        return getScaffoldTrollAction();
    }

    /**
     * Get current scaffold action without incrementing violation level
     * Use this in MitigationListener for ongoing mitigation
     */
    public ScaffoldAction getCurrentScaffoldAction() {
        if (!scaffoldMitigation || scaffoldLevel < SCAFFOLD_TROLL_START) {
            return ScaffoldAction.ALLOW;
        }

        if (scaffoldLevel >= SCAFFOLD_KICK) {
            return ScaffoldAction.KICK;
        }

        return getScaffoldTrollAction();
    }

    /**
     * Get the troll action for scaffold based on current level
     */
    private ScaffoldAction getScaffoldTrollAction() {
        ThreadLocalRandom random = ThreadLocalRandom.current();

        // Level 3-7: Light trolling (10-30% cancel)
        if (scaffoldLevel >= SCAFFOLD_TROLL_START && scaffoldLevel < SCAFFOLD_GHOST_START) {
            double cancelChance = 0.1 + (scaffoldLevel - SCAFFOLD_TROLL_START) * 0.04; // 10-30%
            if (random.nextDouble() < cancelChance) {
                return ScaffoldAction.CANCEL;
            }
            return ScaffoldAction.ALLOW;
        }

        // Level 8-14: Ghost blocks (30-60% cancel, 20-40% ghost)
        if (scaffoldLevel >= SCAFFOLD_GHOST_START && scaffoldLevel < SCAFFOLD_HEAVY_START) {
            double roll = random.nextDouble();
            double cancelChance = 0.3 + (scaffoldLevel - SCAFFOLD_GHOST_START) * 0.04; // 30-60%
            double ghostChance = 0.2 + (scaffoldLevel - SCAFFOLD_GHOST_START) * 0.03; // 20-40%

            if (roll < cancelChance) {
                return ScaffoldAction.CANCEL;
            } else if (roll < cancelChance + ghostChance) {
                return ScaffoldAction.GHOST_BLOCK;
            }
            return ScaffoldAction.ALLOW;
        }

        // Level 15-29: Heavy trolling (60-90% cancel, 30-50% ghost, 10-20% delayed break)
        if (scaffoldLevel >= SCAFFOLD_HEAVY_START) {
            double roll = random.nextDouble();
            double cancelChance = 0.6 + (scaffoldLevel - SCAFFOLD_HEAVY_START) * 0.02; // 60-90%
            double ghostChance = 0.3 + (scaffoldLevel - SCAFFOLD_HEAVY_START) * 0.013; // 30-50%
            double delayedBreakChance = 0.1 + (scaffoldLevel - SCAFFOLD_HEAVY_START) * 0.007; // 10-20%

            if (roll < delayedBreakChance) {
                return ScaffoldAction.DELAYED_BREAK;
            } else if (roll < delayedBreakChance + ghostChance) {
                return ScaffoldAction.GHOST_BLOCK;
            } else if (roll < delayedBreakChance + ghostChance + cancelChance) {
                return ScaffoldAction.CANCEL;
            }
            return ScaffoldAction.ALLOW;
        }

        return ScaffoldAction.ALLOW;
    }

    /**
     * Check if a block place should be cancelled
     * ONLY uses Cloud ML - local checks do NOT cancel blocks
     */
    public boolean shouldCancelPlace() {
        if (scaffoldMitigation && placesToCancel > 0) {
            placesToCancel--;
            placesCancelled++;
            return true;
        }
        return false;
    }

    /**
     * Apply ghost block effect - block disappears after delay
     */
    public void applyGhostBlock(Block block) {
        if (block == null) return;

        Material originalType = block.getType();
        Location loc = block.getLocation();

        // Block will disappear after 0.5-2 seconds
        int delayTicks = ThreadLocalRandom.current().nextInt(10, 40);

        Bukkit.getScheduler().runTaskLater(GAC.getInstance(), () -> {
            Block b = loc.getBlock();
            if (b.getType() == originalType) {
                b.setType(Material.AIR);

                // Send block change to player to sync
                Player player = user.getPlayer();
                if (player != null && player.isOnline()) {
                    player.sendBlockChange(loc, Material.AIR.createBlockData());
                }
            }
        }, delayTicks);
    }

    /**
     * Apply delayed break effect - block breaks after a longer delay
     * Makes scaffold very unreliable
     */
    public void applyDelayedBreak(Block block) {
        if (block == null) return;

        Material originalType = block.getType();
        Location loc = block.getLocation();

        // Block will break after 1-3 seconds (just as player is trying to walk on it!)
        int delayTicks = ThreadLocalRandom.current().nextInt(20, 60);

        Bukkit.getScheduler().runTaskLater(GAC.getInstance(), () -> {
            Block b = loc.getBlock();
            if (b.getType() == originalType) {
                b.setType(Material.AIR);

                Player player = user.getPlayer();
                if (player != null && player.isOnline()) {
                    player.sendBlockChange(loc, Material.AIR.createBlockData());
                }
            }
        }, delayTicks);
    }

    /**
     * Check if player should be kicked (only for 100% certain cheats)
     */
    public boolean shouldKick(String checkType) {
        // Only kick for extreme cases
        switch (checkType) {
            case "Reach":
                return reachLevel >= KICK_THRESHOLD;
            case "Killaura":
                return killauraLevel >= KICK_THRESHOLD;
            case "Speed":
            case "Fly":
                return speedLevel >= KICK_THRESHOLD;
            case "Scaffold":
                return scaffoldLevel >= SCAFFOLD_KICK;
            default:
                return false;
        }
    }

    /**
     * Get mitigation stats for logging
     */
    public String getStats() {
        return String.format("cancelled: hits=%d places=%d setbacks=%d scaffoldVL=%d",
            hitsCancelled, placesCancelled, setbackCount, scaffoldLevel);
    }

    // Getters
    public boolean isReachMitigation() { return reachMitigation; }
    public boolean isKillauraMitigation() { return killauraMitigation; }
    public boolean isSpeedMitigation() { return speedMitigation; }
    public boolean isScaffoldMitigation() { return scaffoldMitigation; }
    public int getReachLevel() { return reachLevel; }
    public int getKillauraLevel() { return killauraLevel; }
    public int getSpeedLevel() { return speedLevel; }
    public int getScaffoldLevel() { return scaffoldLevel; }

    // ML Cloud mitigation getters
    public double getMLReachReduction() { return mlReachReduction; }
    public double getMLHitRegLoss() { return mlHitRegLoss; }
    public double getMLBlockFailRate() { return mlBlockFailRate; }
    public double getMLSpeedReduction() { return mlSpeedReduction; }
    public boolean hasMLMitigation() { return mlReachReduction > 0 || mlHitRegLoss > 0 || mlBlockFailRate > 0 || mlSpeedReduction > 0; }

    /**
     * Actions for scaffold mitigation
     */
    public enum ScaffoldAction {
        ALLOW,          // Let the block place
        CANCEL,         // Cancel the placement
        GHOST_BLOCK,    // Place but remove after 0.5-2 seconds
        DELAYED_BREAK,  // Place but break after 1-3 seconds (troll!)
        KICK            // Too many violations, kick player
    }
}
