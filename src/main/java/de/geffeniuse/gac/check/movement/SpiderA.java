package de.geffeniuse.gac.check.movement;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketEvent;
import de.geffeniuse.gac.GAC;
import de.geffeniuse.gac.check.Check;
import de.geffeniuse.gac.data.GACUser;
import de.geffeniuse.gac.data.TrustedPlayers;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.LinkedList;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * SpiderA - Self-Learning Wall Climb Detection
 * Learns normal climbing patterns and detects wall hacks.
 */
public class SpiderA extends Check {

    // ============ GLOBAL LEARNING ============
    private static final ConcurrentLinkedQueue<Double> globalClimbSpeeds = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<Double> globalClimbDistances = new ConcurrentLinkedQueue<>();

    private static double learnedMeanSpeed = 0.12;
    private static double learnedStdSpeed = 0.05;
    private static double learnedMaxClimb = 3.0;
    private static int totalSamples = 0;
    private static long lastModelUpdate = 0;
    private static final int MAX_GLOBAL_SAMPLES = 3000;
    private static final int MIN_SAMPLES_FOR_DETECTION = 20; // Much lower - immediate detection works anyway

    // ============ PER-PLAYER ============
    private final LinkedList<Double> climbSpeeds = new LinkedList<>();
    private int climbTicks = 0;
    private int suspicion = 0;
    private double climbStartY = 0;
    private double lastY = 0;
    private boolean wasClimbing = false;

    private static final int SUSPICION_THRESHOLD = 8;
    private static final int KICK_THRESHOLD = 10;
    
    public static String getStatus() {
        String state = totalSamples >= MIN_SAMPLES_FOR_DETECTION ? "§aACTIVE" : "§eTRAINING";
        return String.format("§7Spider: §f%d/%d %s §8(Climb: %.2f)", 
            totalSamples, MIN_SAMPLES_FOR_DETECTION, state, learnedMaxClimb);
    }

    public SpiderA(GACUser user) {
        super(user, "Spider", "Self-learning wall climb detection.");
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
            player.isGliding()) {
            climbTicks = 0;
            return;
        }

        double currentY = user.getLastY();
        double deltaY = user.getDeltaY();

        Bukkit.getScheduler().runTask(GAC.getInstance(), () -> {
            if (player == null || !player.isOnline() || !isEnabled()) return;

            boolean onGround = isOnGround(player);
            boolean onClimbable = isOnClimbable(player);
            boolean inLiquid = isInLiquid(player);
            boolean againstWall = isAgainstWall(player);

            // Skip if on legitimate climbable
            if (onClimbable || inLiquid) {
                // Add to learning data for legitimate climbing
                // TRUSTED PLAYERS GET PRIORITY
                if (onClimbable && deltaY > 0) {
                    boolean isTrusted = TrustedPlayers.isTrusted(player.getUniqueId());
                    if (isTrusted) {
                        // Trusted player data - add multiple samples
                        addGlobalSample(deltaY, 0);
                        addGlobalSample(deltaY, 0);
                        addGlobalSample(deltaY, 0);
                    } else {
                        addGlobalSample(deltaY, 0);
                    }
                }
                climbTicks = 0;
                wasClimbing = false;
                return;
            }

            if (onGround) {
                // Finished climbing - check total distance
                if (wasClimbing && climbTicks > 5) {
                    double totalClimb = currentY - climbStartY;

                    if (totalClimb > learnedMaxClimb) {
                        addGlobalSample(0, totalClimb);
                    }
                }
                climbTicks = 0;
                wasClimbing = false;
                climbSpeeds.clear();
                return;
            }

            // Going UP while against a wall but NO ladder = spider
            if (deltaY > 0.02 && againstWall && !onClimbable) {
                if (!wasClimbing) {
                    climbStartY = currentY;
                    wasClimbing = true;
                }
                climbTicks++;

                climbSpeeds.add(deltaY);
                while (climbSpeeds.size() > 30) climbSpeeds.removeFirst();

                double totalClimb = currentY - climbStartY;
                double avgSpeed = getMean(climbSpeeds);

                updateModelIfNeeded();

                // ========== IMMEDIATE DETECTION (works without ML) ==========
                // Vulcan bypass typically uses small climbs - detect any sustained wall climb

                // Check: Climbing up a wall for more than 1 block without ladder
                if (totalClimb > 1.0 && climbTicks > 8) {
                    suspicion += 3;

                    if (suspicion >= SUSPICION_THRESHOLD) {
                        fail(String.format("wallClimb=%.1f ticks=%d", totalClimb, climbTicks));
                        suspicion = 0;
                        climbTicks = 0;

                        if (getViolationLevel() >= KICK_THRESHOLD) {
                            GAC.incrementKicks();
                            player.kickPlayer("§b§lGAC \n\n§7Unfair Advantage detected.\n§fCheck: §bSpider");
                        }
                        return;
                    }
                }

                // ========== ML-ENHANCED DETECTION ==========
                if (totalSamples < MIN_SAMPLES_FOR_DETECTION) return;

                // ========== CHECK 1: Climbing too high without ladder ==========
                if (totalClimb > 1.5 && climbTicks > 10) {
                    suspicion += 2;

                    if (suspicion >= SUSPICION_THRESHOLD) {
                        fail(String.format("climb=%.1f ticks=%d", totalClimb, climbTicks));
                        suspicion = 0;
                        climbTicks = 0;

                        if (getViolationLevel() >= KICK_THRESHOLD) {
                            GAC.incrementKicks();
                            player.kickPlayer("§b§lGAC \n\n§7Unfair Advantage detected.\n§fCheck: §bSpider");
                        }
                    }
                }

                // ========== CHECK 2: Climbing faster than ladder speed ==========
                // Ladder speed is ~0.12 blocks/tick
                if (avgSpeed > learnedMeanSpeed + learnedStdSpeed * 2 && climbTicks > 5) {
                    suspicion += 2;

                    if (suspicion >= SUSPICION_THRESHOLD) {
                        fail(String.format("speed=%.3f (max=%.3f)", avgSpeed, learnedMeanSpeed));
                        suspicion = 0;

                        if (getViolationLevel() >= KICK_THRESHOLD) {
                            GAC.incrementKicks();
                            player.kickPlayer("§b§lGAC \n\n§7Unfair Advantage detected.\n§fCheck: §bSpider");
                        }
                    }
                }

                // ========== CHECK 3: Consistent climb speed (bot-like) ==========
                if (climbSpeeds.size() >= 10) {
                    double speedStd = getStandardDeviation(climbSpeeds);
                    if (speedStd < 0.005 && avgSpeed > 0.05) {
                        suspicion++;
                    }
                }
            } else {
                // Not climbing up against wall
                if (climbTicks > 0) {
                    climbTicks = Math.max(0, climbTicks - 2);
                }
                suspicion = Math.max(0, suspicion - 1);
            }

            lastY = currentY;
        });
    }

    private static synchronized void addGlobalSample(double speed, double distance) {
        if (speed > 0) globalClimbSpeeds.add(speed);
        if (distance > 0) globalClimbDistances.add(distance);
        totalSamples++;

        while (globalClimbSpeeds.size() > MAX_GLOBAL_SAMPLES) globalClimbSpeeds.poll();
        while (globalClimbDistances.size() > MAX_GLOBAL_SAMPLES) globalClimbDistances.poll();
    }

    private static synchronized void updateModelIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastModelUpdate < 30000) return;
        if (globalClimbSpeeds.size() < 20) return;

        lastModelUpdate = now;

        double sum = 0, count = 0;
        for (double d : globalClimbSpeeds) { sum += d; count++; }
        if (count > 0) {
            double mean = sum / count;
            double varSum = 0;
            for (double d : globalClimbSpeeds) varSum += Math.pow(d - mean, 2);
            learnedMeanSpeed = mean;
            learnedStdSpeed = Math.max(0.02, Math.sqrt(varSum / count));
        }

        if (!globalClimbDistances.isEmpty()) {
            double maxDist = 0;
            for (double d : globalClimbDistances) maxDist = Math.max(maxDist, d);
            learnedMaxClimb = Math.max(3.0, maxDist);
        }

        if (totalSamples % 300 == 0) {
            Bukkit.getLogger().info("[GAC-ML] Spider model: " + totalSamples + " samples");
        }
    }

    private double getMean(LinkedList<Double> data) {
        if (data.isEmpty()) return 0;
        double sum = 0;
        for (double d : data) sum += d;
        return sum / data.size();
    }

    private double getStandardDeviation(LinkedList<Double> data) {
        if (data.size() < 2) return 999;
        double mean = getMean(data);
        double var = 0;
        for (double d : data) var += Math.pow(d - mean, 2);
        return Math.sqrt(var / data.size());
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

    private boolean isAgainstWall(Player player) {
        Location loc = player.getLocation();
        double[][] offsets = {{-0.35, 0}, {0.35, 0}, {0, -0.35}, {0, 0.35}};

        for (double[] off : offsets) {
            for (double y = 0.5; y <= 1.5; y += 0.5) {
                Block block = loc.clone().add(off[0], y, off[1]).getBlock();
                if (block.getType().isSolid()) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isOnClimbable(Player player) {
        Location loc = player.getLocation();
        String name = loc.getBlock().getType().name();
        String nameAbove = loc.clone().add(0, 1, 0).getBlock().getType().name();

        return name.contains("LADDER") || name.contains("VINE") || name.contains("SCAFFOLDING") ||
               name.contains("WEEPING") || name.contains("TWISTING") || name.contains("CAVE_VINES") ||
               nameAbove.contains("LADDER") || nameAbove.contains("VINE");
    }

    private boolean isInLiquid(Player player) {
        Material mat = player.getLocation().getBlock().getType();
        return mat == Material.WATER || mat == Material.LAVA;
    }
}
