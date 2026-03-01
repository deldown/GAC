package de.geffeniuse.gac.check.movement;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketEvent;
import de.geffeniuse.gac.GAC;
import de.geffeniuse.gac.check.Check;
import de.geffeniuse.gac.data.GACUser;
import de.geffeniuse.gac.util.MLDebug;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

import java.util.LinkedList;
import java.util.concurrent.ConcurrentLinkedQueue;

public class FlyB extends Check implements Listener {

    private static final ConcurrentLinkedQueue<Double> globalYVelocities = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<Double> globalAirTimes = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<Double> globalGravityDeviations = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<Double> globalGroundRatios = new ConcurrentLinkedQueue<>();

    private static double learnedMeanVelocity = -0.08;
    private static double learnedStdVelocity = 0.15;
    private static double learnedMeanAirTime = 500.0;
    private static double learnedStdAirTime = 300.0;
    private static double learnedMeanGravity = 0.08;
    private static double learnedStdGravity = 0.05;
    private static double learnedMeanGroundRatio = 0.7;
    private static double learnedStdGroundRatio = 0.2;

    private static int totalSamples = 0;
    private static long lastModelUpdate = 0;
    private static final int MIN_SAMPLES_FOR_DETECTION = 50;   // Reduced from 200 - faster detection
    private static final int MAX_GLOBAL_SAMPLES = 10000;
    private static final long MODEL_UPDATE_INTERVAL = 30000;
    private static final double GRAVITY = 0.08;

    private final LinkedList<Double> yVelocities = new LinkedList<>();
    private final LinkedList<Double> yAccelerations = new LinkedList<>();
    private final LinkedList<Double> gravityDevs = new LinkedList<>();
    private final LinkedList<Double> airTimes = new LinkedList<>();

    private double lastY = 0;
    private double lastVelocityY = 0;
    private long airStartTime = 0;
    private boolean wasInAir = false;
    private int ticksInAir = 0;
    private int ticksOnGround = 0;
    private int totalTicks = 0;
    private int suspicion = 0;
    private long lastMaceHit = 0;

    private static final int MAX_SAMPLES = 100;
    private static final int MIN_SAMPLES = 20;          // Reduced from 30
    private static final int SUSPICION_THRESHOLD = 5;   // Reduced from 8 - more sensitive
    private static final int KICK_THRESHOLD = 8;        // Reduced from 10

    public FlyB(GACUser user) {
        super(user, "Fly", "Self-learning fly ML.");
        Bukkit.getPluginManager().registerEvents(this, GAC.getInstance());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCombat(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player)) return;
        Player player = (Player) event.getDamager();
        if (!player.getUniqueId().equals(user.getUuid())) return;

        String item = player.getInventory().getItemInMainHand().getType().name();
        if (item.contains("MACE") && !event.isCancelled() && event.getDamage() > 0) {
            lastMaceHit = System.currentTimeMillis();
            suspicion = 0;
        }
    }

    @Override
    public void onPacket(PacketEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.POSITION &&
            event.getPacketType() != PacketType.Play.Client.POSITION_LOOK) {
            return;
        }

        Player player = user.getPlayer();
        if (player == null) return;

        if (player.isFlying() || player.getAllowFlight() ||
            player.getGameMode() == GameMode.CREATIVE ||
            player.getGameMode() == GameMode.SPECTATOR ||
            player.isInsideVehicle() || player.isGliding() ||
            player.isRiptiding()) {
            return;
        }

        long timeSinceMaceHit = System.currentTimeMillis() - lastMaceHit;
        if (timeSinceMaceHit < 2000) {
            return;
        }

        collectData(player);
        totalTicks++;

        if (totalTicks % 10 == 0 && hasEnoughData()) {
            analyzePatterns(player);
        }
    }

    private void collectData(Player player) {
        long now = System.currentTimeMillis();
        double currentY = user.getLastY();
        double deltaY = user.getDeltaY();

        double velocityY = deltaY;
        double accelerationY = velocityY - lastVelocityY;

        addSample(yVelocities, velocityY);
        addSample(yAccelerations, accelerationY);

        double expectedAccel = wasInAir ? -GRAVITY : 0;
        double gravityDev = Math.abs(accelerationY - expectedAccel);
        addSample(gravityDevs, gravityDev);

        Bukkit.getScheduler().runTask(GAC.getInstance(), () -> {
            if (player == null || !player.isOnline()) return;

            // Fix: If completely stationary, force ground state
            boolean stationary = deltaY == 0 && user.getDeltaXZ() == 0;
            boolean inAir = !stationary && !isOnGround(player) && !isInLiquid(player) && !isClimbing(player);

            if (inAir) {
                if (!wasInAir) {
                    airStartTime = now;
                }
                ticksInAir++;
            } else {
                if (wasInAir && airStartTime > 0) {
                    double airTime = now - airStartTime;
                    addSample(airTimes, airTime);

                    if (airTime > 100 && airTime < 10000) {
                        addGlobalSample(getMean(yVelocities), airTime, getMean(gravityDevs), getGroundRatio());
                    }
                }
                ticksOnGround++;
            }

            wasInAir = inAir;
        });

        lastY = currentY;
        lastVelocityY = velocityY;
    }

    private void analyzePatterns(Player player) {
        updateModelIfNeeded();

        if (totalSamples < MIN_SAMPLES_FOR_DETECTION) return;

        int checksTriggered = 0;
        double anomalyScore = 0;

        double velMean = getMean(yVelocities);
        double velStd = getStandardDeviation(yVelocities);

        if (Math.abs(velMean) < 0.02 && velStd < 0.02 && getAirRatio() > 0.5) {
            anomalyScore += 25;
            checksTriggered++;
        }

        double velZ = Math.abs(velMean - learnedMeanVelocity) / Math.max(learnedStdVelocity, 0.01);
        if (velZ > 3 && velStd < 0.05) {
            anomalyScore += velZ * 3;
            checksTriggered++;
        }

        double gravMean = getMean(gravityDevs);
        double gravZ = Math.abs(gravMean - learnedMeanGravity) / Math.max(learnedStdGravity, 0.01);

        if (gravMean > learnedMeanGravity * 2 && getAirRatio() > 0.5) {
            anomalyScore += gravZ * 3;
            checksTriggered++;
        }

        if (airTimes.size() >= 5) {
            double airMean = getMean(airTimes);
            double airZ = Math.abs(airMean - learnedMeanAirTime) / Math.max(learnedStdAirTime, 100);

            if (airMean > learnedMeanAirTime * 3) {
                anomalyScore += airZ * 2;
                checksTriggered++;
            }

            for (double at : airTimes) {
                if (at > 8000) {
                    anomalyScore += 30;
                    checksTriggered++;
                    break;
                }
            }
        }

        double groundRatio = getGroundRatio();
        double groundZ = Math.abs(groundRatio - learnedMeanGroundRatio) / Math.max(learnedStdGroundRatio, 0.1);

        if (groundRatio < 0.15 && ticksInAir + ticksOnGround > 100) {
            anomalyScore += groundZ * 3;
            checksTriggered++;
        }

        int zeroAccel = 0;
        for (double a : yAccelerations) {
            if (Math.abs(a) < 0.005) zeroAccel++;
        }

        if ((double) zeroAccel / yAccelerations.size() > 0.5 && getAirRatio() > 0.4) {
            anomalyScore += 20;
            checksTriggered++;
        }

        // Calculate suspicion percentage for debug
        int suspicionPercent = Math.min(100, (int) (anomalyScore * 1.5) + (checksTriggered * 15));
        MLDebug.updateSuspicion(player.getUniqueId(), "Fly", suspicionPercent);

        if (checksTriggered >= 2) {
            suspicion += checksTriggered;

            if (suspicion >= SUSPICION_THRESHOLD) {
                fail(String.format("anomaly=%.1f checks=%d samples=%d", anomalyScore, checksTriggered, totalSamples));
                suspicion = 0;

                if (getViolationLevel() >= KICK_THRESHOLD) {
                    Bukkit.getScheduler().runTask(GAC.getInstance(), () -> {
                        if (player != null && player.isOnline()) {
                            GAC.incrementKicks();
                            player.kickPlayer("§b§lGAC \n\n§7Unfair Advantage detected.\n§fCheck: §bFly");
                        }
                    });
                }
            }
        } else {
            suspicion = Math.max(0, suspicion - 1);
        }
    }

    private static synchronized void addGlobalSample(double velocity, double airTime, double gravDev, double groundRatio) {
        globalYVelocities.add(velocity);
        globalAirTimes.add(airTime);
        globalGravityDeviations.add(gravDev);
        globalGroundRatios.add(groundRatio);
        totalSamples++;

        while (globalYVelocities.size() > MAX_GLOBAL_SAMPLES) {
            globalYVelocities.poll();
            globalAirTimes.poll();
            globalGravityDeviations.poll();
            globalGroundRatios.poll();
        }
    }

    private static synchronized void updateModelIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastModelUpdate < MODEL_UPDATE_INTERVAL) return;
        if (globalYVelocities.size() < 50) return;

        lastModelUpdate = now;

        double[] velStats = calculateGlobalStats(globalYVelocities);
        double[] airStats = calculateGlobalStats(globalAirTimes);
        double[] gravStats = calculateGlobalStats(globalGravityDeviations);
        double[] groundStats = calculateGlobalStats(globalGroundRatios);

        learnedMeanVelocity = velStats[0];
        learnedStdVelocity = Math.max(0.05, velStats[1]);
        learnedMeanAirTime = airStats[0];
        learnedStdAirTime = Math.max(100, airStats[1]);
        learnedMeanGravity = gravStats[0];
        learnedStdGravity = Math.max(0.02, gravStats[1]);
        learnedMeanGroundRatio = groundStats[0];
        learnedStdGroundRatio = Math.max(0.1, groundStats[1]);

        if (totalSamples % 1000 == 0) {
            Bukkit.getLogger().info("[GAC-ML] Fly model updated: " + totalSamples + " samples");
        }
    }

    private static double[] calculateGlobalStats(ConcurrentLinkedQueue<Double> data) {
        if (data.isEmpty()) return new double[]{0, 1};

        double sum = 0;
        int count = 0;
        for (double d : data) {
            sum += d;
            count++;
        }
        double mean = sum / count;

        double varSum = 0;
        for (double d : data) {
            varSum += Math.pow(d - mean, 2);
        }
        double std = Math.sqrt(varSum / count);

        return new double[]{mean, std};
    }

    private void addSample(LinkedList<Double> list, double value) {
        list.addLast(value);
        while (list.size() > MAX_SAMPLES) list.removeFirst();
    }

    private boolean hasEnoughData() {
        return yVelocities.size() >= MIN_SAMPLES;
    }

    private double getAirRatio() {
        int total = ticksInAir + ticksOnGround;
        return total > 0 ? (double) ticksInAir / total : 0;
    }

    private double getGroundRatio() {
        int total = ticksInAir + ticksOnGround;
        return total > 0 ? (double) ticksOnGround / total : 0.5;
    }

    private double getMean(LinkedList<Double> data) {
        if (data.isEmpty()) return 0;
        double sum = 0;
        for (double d : data) sum += d;
        return sum / data.size();
    }

    private double getStandardDeviation(LinkedList<Double> data) {
        if (data.size() < 2) return 0;
        double mean = getMean(data);
        double var = 0;
        for (double d : data) var += Math.pow(d - mean, 2);
        return Math.sqrt(var / data.size());
    }

    private boolean isOnGround(Player player) {
        if (player.getLocation().getY() < 0) return true; // Void safety

        // Check wider area (0.4) to catch edges
        for (double x = -0.4; x <= 0.4; x += 0.4) {
            for (double z = -0.4; z <= 0.4; z += 0.4) {
                if (player.getLocation().clone().add(x, -0.5, z).getBlock().getType().isSolid()) {
                    return true;
                }
            }
        }
        
        // Extra check for fences/walls/etc which might have weird hitboxes
        // If client says onGround and we are VERY close to a block, trust it slightly more
        if (player.isOnGround()) {
             for (double x = -0.5; x <= 0.5; x += 0.5) {
                for (double z = -0.5; z <= 0.5; z += 0.5) {
                    if (player.getLocation().clone().add(x, -0.6, z).getBlock().getType().isSolid()) {
                        return true;
                    }
                }
            }
        }
        
        return false;
    }

    private boolean isInLiquid(Player player) {
        Material block = player.getLocation().getBlock().getType();
        return block == Material.WATER || block == Material.LAVA ||
               block.name().contains("WATER") || block.name().contains("LAVA");
    }

    private boolean isClimbing(Player player) {
        String name = player.getLocation().getBlock().getType().name();
        return name.contains("LADDER") || name.contains("VINE") ||
               name.contains("SCAFFOLDING") || name.contains("WEEPING") ||
               name.contains("TWISTING") || name.contains("CAVE_VINES") ||
               name.contains("CHAIN") || name.contains("TRAPDOOR");
    }

    public static int getTotalSamples() {
        return totalSamples;
    }
}
