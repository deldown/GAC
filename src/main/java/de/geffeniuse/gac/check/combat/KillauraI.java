package de.geffeniuse.gac.check.combat;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.EnumWrappers;
import de.geffeniuse.gac.GAC;
import de.geffeniuse.gac.check.Check;
import de.geffeniuse.gac.data.GACUser;
import de.geffeniuse.gac.data.TrustedPlayers;
import de.geffeniuse.gac.util.MLDebug;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.LinkedList;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * KillauraI - Self-Learning ML Combat Analysis
 *
 * Automatically learns normal combat patterns from all players.
 * The longer the server runs, the better it detects killaura.
 */
public class KillauraI extends Check {

    // ============ GLOBAL LEARNING DATA (shared across all players) ============
    private static final ConcurrentLinkedQueue<Double> globalHitIntervals = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<Double> globalRotationSpeeds = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<Double> globalAimAccuracies = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<Double> globalClickEntropy = new ConcurrentLinkedQueue<>();

    // Learned statistics
    private static double learnedMeanInterval = 200.0;
    private static double learnedStdInterval = 80.0;
    private static double learnedMeanRotation = 15.0;
    private static double learnedStdRotation = 10.0;
    private static double learnedMeanAim = 5.0;
    private static double learnedStdAim = 3.0;
    private static double learnedMeanEntropy = 0.6;
    private static double learnedStdEntropy = 0.2;

    private static int totalSamples = 0;
    private static long lastModelUpdate = 0;
    private static final int MIN_SAMPLES_FOR_DETECTION = 15;
    private static final int MAX_GLOBAL_SAMPLES = 10000;
    private static final long MODEL_UPDATE_INTERVAL = 30000;

    // ============ PER-PLAYER DATA ============
    private final LinkedList<Double> hitIntervals = new LinkedList<>();
    private final LinkedList<Double> rotationDeltas = new LinkedList<>();
    private final LinkedList<Double> rotationAccels = new LinkedList<>();
    private final LinkedList<Double> aimAccuracies = new LinkedList<>();
    private final LinkedList<Double> yawDeltas = new LinkedList<>();
    private final LinkedList<Double> pitchDeltas = new LinkedList<>();

    private long lastHitTime = 0;
    private float lastYaw = 0, lastPitch = 0;
    private float lastDeltaYaw = 0;
    private int totalHits = 0;
    private int suspicion = 0;

    private static final int MAX_SAMPLES = 50;
    private static final int MIN_SAMPLES = 15;
    private static final int SUSPICION_THRESHOLD = 4;
    private static final int KICK_THRESHOLD = 6;

    public KillauraI(GACUser user) {
        super(user, "Killaura", "Self-learning combat ML.");
    }

    @Override
    public void onPacket(PacketEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.LOOK ||
            event.getPacketType() == PacketType.Play.Client.POSITION_LOOK) {
            trackRotation();
        }

        if (event.getPacketType() == PacketType.Play.Client.USE_ENTITY) {
            try {
                EnumWrappers.EntityUseAction action = event.getPacket().getEnumEntityUseActions().read(0).getAction();
                if (action == EnumWrappers.EntityUseAction.ATTACK) {
                    analyzeAttack();
                }
            } catch (Exception e) {
                // Ignore
            }
        }
    }

    private void trackRotation() {
        float currentYaw = user.getLastYaw();
        float currentPitch = user.getLastPitch();

        float deltaYaw = Math.abs(currentYaw - lastYaw);
        if (deltaYaw > 180) deltaYaw = 360 - deltaYaw;
        float deltaPitch = Math.abs(currentPitch - lastPitch);

        double totalDelta = Math.sqrt(deltaYaw * deltaYaw + deltaPitch * deltaPitch);

        addSample(rotationDeltas, totalDelta);
        addSample(yawDeltas, (double) deltaYaw);
        addSample(pitchDeltas, (double) deltaPitch);

        // Track acceleration
        double accel = Math.abs(deltaYaw - lastDeltaYaw);
        addSample(rotationAccels, accel);

        lastYaw = currentYaw;
        lastPitch = currentPitch;
        lastDeltaYaw = deltaYaw;
    }

    private void analyzeAttack() {
        long now = System.currentTimeMillis();
        totalHits++;

        // Track hit intervals
        if (lastHitTime > 0) {
            double interval = now - lastHitTime;
            if (interval > 50 && interval < 2000) {
                addSample(hitIntervals, interval);

                // Add to global learning - TRUSTED PLAYERS GET PRIORITY
                Player player = user.getPlayer();
                boolean isTrusted = player != null && TrustedPlayers.isTrusted(player.getUniqueId());

                if (isTrusted) {
                    // Trusted player data is golden - add multiple samples
                    addGlobalSample(interval, getMean(rotationDeltas), getMean(aimAccuracies), calculateEntropy());
                    addGlobalSample(interval, getMean(rotationDeltas), getMean(aimAccuracies), calculateEntropy());
                    addGlobalSample(interval, getMean(rotationDeltas), getMean(aimAccuracies), calculateEntropy());
                } else {
                    addGlobalSample(interval, getMean(rotationDeltas), getMean(aimAccuracies), calculateEntropy());
                }
            }
        }
        lastHitTime = now;

        // Track aim accuracy
        float deltaYaw = Math.abs(user.getDeltaYaw());
        float deltaPitch = Math.abs(user.getDeltaPitch());
        double aimDelta = Math.sqrt(deltaYaw * deltaYaw + deltaPitch * deltaPitch);
        addSample(aimAccuracies, aimDelta);

        // Update global model
        updateModelIfNeeded();

        // Analyze every 5 hits for faster detection
        if (totalHits % 5 == 0 && hasEnoughData()) {
            performAnalysis();
        }
    }

    private void performAnalysis() {
        int checksTriggered = 0;
        double anomalyScore = 0;

        // ============ IMMEDIATE DETECTION (works without ML training) ============
        double intervalCV = getCoefficientOfVariation(hitIntervals);
        double aimStd = getStandardDeviation(aimAccuracies);
        double aimMean = getMean(aimAccuracies);

        // Perfect click timing = bot (CV < 0.08 is inhuman)
        if (intervalCV < 0.08 && hitIntervals.size() >= 10) {
            suspicion += 3;
            if (suspicion >= SUSPICION_THRESHOLD) {
                fail(String.format("perfectTiming CV=%.3f", intervalCV));
                suspicion = 0;
            }
        }

        // Perfect aim consistency = aimbot
        if (aimStd < 1.5 && aimMean < 5.0 && aimAccuracies.size() >= 10) {
            suspicion += 3;
            if (suspicion >= SUSPICION_THRESHOLD) {
                fail(String.format("perfectAim std=%.2f mean=%.2f", aimStd, aimMean));
                suspicion = 0;
            }
        }

        // ============ ML DETECTION ============
        if (totalSamples < MIN_SAMPLES_FOR_DETECTION) return;

        // ========== CHECK 1: Hit Interval Consistency ==========
        double intervalZ = Math.abs(intervalCV - (learnedStdInterval / learnedMeanInterval)) / 0.1;

        // Too consistent = bot (Grim bypass has ~0.1-0.15 CV due to randomization)
        if (intervalCV < 0.12) {
            anomalyScore += intervalZ * 4;
            checksTriggered++;
        }

        // ========== CHECK 2: Rotation Pattern ==========
        double rotMean = getMean(rotationDeltas);
        double rotZ = Math.abs(rotMean - learnedMeanRotation) / Math.max(learnedStdRotation, 1);

        if (rotZ > 2.5) {
            anomalyScore += rotZ * 2;
            checksTriggered++;
        }

        // Check rotation acceleration consistency
        double accelStd = getStandardDeviation(rotationAccels);
        if (accelStd < 0.5) {
            anomalyScore += 15;
            checksTriggered++;
        }

        // ========== CHECK 3: Aim Accuracy ==========
        // (aimMean and aimStd already calculated above)

        // Perfect consistent aim = aimbot
        if (aimStd < 2.0 && aimMean < 8.0) {
            anomalyScore += 25;
            checksTriggered++;
        }

        // ========== CHECK 4: Click Entropy ==========
        double entropy = calculateEntropy();
        double entropyZ = Math.abs(entropy - learnedMeanEntropy) / Math.max(learnedStdEntropy, 0.1);

        if (entropy < 0.3) {
            anomalyScore += entropyZ * 3;
            checksTriggered++;
        }

        // ========== CHECK 5: Yaw/Pitch Correlation ==========
        if (yawDeltas.size() >= MIN_SAMPLES && pitchDeltas.size() >= MIN_SAMPLES) {
            double correlation = Math.abs(calculateCorrelation(yawDeltas, pitchDeltas));
            if (correlation > 0.95) {
                anomalyScore += 15;
                checksTriggered++;
            }
        }

        // ========== DETECTION LOGIC ==========

        // Calculate suspicion percentage for debug
        // Based on anomaly score and checks triggered
        int suspicionPercent = Math.min(100, (int) (anomalyScore * 2) + (checksTriggered * 10));
        Player p = user.getPlayer();
        if (p != null) {
            MLDebug.updateSuspicion(p.getUniqueId(), "Killaura", suspicionPercent);
        }

        if (checksTriggered >= 2) {
            suspicion += checksTriggered;

            if (suspicion >= SUSPICION_THRESHOLD) {
                fail(String.format("anomaly=%.1f checks=%d samples=%d", anomalyScore, checksTriggered, totalSamples));
                suspicion = 0;

                if (getViolationLevel() >= KICK_THRESHOLD) {
                    Bukkit.getScheduler().runTask(GAC.getInstance(), () -> {
                        Player player = user.getPlayer();
                        if (player != null && player.isOnline()) {
                            GAC.incrementKicks();
                            player.kickPlayer("§b§lGAC \n\n§7Unfair Advantage detected.\n§fCheck: §bKillaura");
                        }
                    });
                }
            }
        } else {
            suspicion = Math.max(0, suspicion - 1);
        }
    }

    // ============ GLOBAL LEARNING ============

    private static synchronized void addGlobalSample(double interval, double rotation, double aim, double entropy) {
        globalHitIntervals.add(interval);
        globalRotationSpeeds.add(rotation);
        globalAimAccuracies.add(aim);
        globalClickEntropy.add(entropy);
        totalSamples++;

        while (globalHitIntervals.size() > MAX_GLOBAL_SAMPLES) {
            globalHitIntervals.poll();
            globalRotationSpeeds.poll();
            globalAimAccuracies.poll();
            globalClickEntropy.poll();
        }
    }

    private static synchronized void updateModelIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastModelUpdate < MODEL_UPDATE_INTERVAL) return;
        if (globalHitIntervals.size() < 50) return;

        lastModelUpdate = now;

        double[] intervalStats = calculateGlobalStats(globalHitIntervals);
        double[] rotationStats = calculateGlobalStats(globalRotationSpeeds);
        double[] aimStats = calculateGlobalStats(globalAimAccuracies);
        double[] entropyStats = calculateGlobalStats(globalClickEntropy);

        learnedMeanInterval = intervalStats[0];
        learnedStdInterval = Math.max(20, intervalStats[1]);
        learnedMeanRotation = rotationStats[0];
        learnedStdRotation = Math.max(2, rotationStats[1]);
        learnedMeanAim = aimStats[0];
        learnedStdAim = Math.max(1, aimStats[1]);
        learnedMeanEntropy = entropyStats[0];
        learnedStdEntropy = Math.max(0.1, entropyStats[1]);

        if (totalSamples % 1000 == 0) {
            Bukkit.getLogger().info("[GAC-ML] Killaura model updated: " + totalSamples + " samples");
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

    // ============ UTILITY METHODS ============

    private void addSample(LinkedList<Double> list, double value) {
        list.addLast(value);
        while (list.size() > MAX_SAMPLES) list.removeFirst();
    }

    private boolean hasEnoughData() {
        return hitIntervals.size() >= MIN_SAMPLES && rotationDeltas.size() >= MIN_SAMPLES;
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

    private double getCoefficientOfVariation(LinkedList<Double> data) {
        double mean = getMean(data);
        return mean == 0 ? 999 : getStandardDeviation(data) / mean;
    }

    private double calculateEntropy() {
        if (hitIntervals.size() < 5) return 1.0;

        double totalDiff = 0;
        Double[] arr = hitIntervals.toArray(new Double[0]);
        for (int i = 1; i < arr.length; i++) {
            totalDiff += Math.abs(arr[i] - arr[i - 1]);
        }
        double avgDiff = totalDiff / (arr.length - 1);
        double mean = getMean(hitIntervals);
        return Math.min(1.0, avgDiff / Math.max(mean * 0.3, 1));
    }

    private double calculateCorrelation(LinkedList<Double> x, LinkedList<Double> y) {
        int n = Math.min(x.size(), y.size());
        if (n < 10) return 0;

        Double[] xArr = x.toArray(new Double[0]);
        Double[] yArr = y.toArray(new Double[0]);

        double xMean = getMean(x);
        double yMean = getMean(y);

        double num = 0, xDen = 0, yDen = 0;
        for (int i = 0; i < n; i++) {
            double xd = xArr[i] - xMean;
            double yd = yArr[i] - yMean;
            num += xd * yd;
            xDen += xd * xd;
            yDen += yd * yd;
        }

        double den = Math.sqrt(xDen * yDen);
        return den > 0 ? num / den : 0;
    }

    public static int getTotalSamples() {
        return totalSamples;
    }
}
