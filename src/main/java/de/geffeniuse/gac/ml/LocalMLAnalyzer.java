package de.geffeniuse.gac.ml;

import de.geffeniuse.gac.GAC;
import de.geffeniuse.gac.cloud.BehaviorSample;
import de.geffeniuse.gac.data.GACUser;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * LocalMLAnalyzer - Local statistical behavior analysis (no cloud required)
 *
 * Analyzes BehaviorSamples collected by BehaviorCollector to detect
 * cheating patterns using statistical methods:
 * - Z-score / variance analysis
 * - Pattern matching (signature detection)
 * - Temporal accumulation (suspicious pattern over time)
 */
public class LocalMLAnalyzer {

    private static final int MIN_SAMPLES = 12; // ~1 minute of data (5s samples)
    private static final double ALERT_THRESHOLD = 0.72; // Flag if confidence exceeds this

    // Temporal risk accumulation (decays over time)
    private double combatRisk = 0.0;
    private double movementRisk = 0.0;
    private long lastAnalysis = 0;
    private int alertCooldown = 0;

    private static final double RISK_DECAY = 3.0;
    private static final double RISK_KICK_THRESHOLD = 65.0;
    private static final int ALERT_COOLDOWN_TICKS = 200; // 10s between alerts

    private final GACUser user;

    public LocalMLAnalyzer(GACUser user) {
        this.user = user;
    }

    /**
     * Called every second by GACUser tick.
     * Runs analysis when enough samples are available.
     */
    public void tick(List<BehaviorSample> samples) {
        if (alertCooldown > 0) alertCooldown--;

        long now = System.currentTimeMillis();
        // Analyze every 30 seconds
        if (now - lastAnalysis < 30000) return;
        lastAnalysis = now;

        if (samples.size() < MIN_SAMPLES) return;

        // Decay risk over time
        combatRisk = Math.max(0, combatRisk - RISK_DECAY);
        movementRisk = Math.max(0, movementRisk - RISK_DECAY);

        // Run detections
        AnalysisResult combat = analyzeCombat(samples);
        AnalysisResult movement = analyzeMovement(samples);
        AnalysisResult packet = analyzePackets(samples);
        AnalysisResult velocity = analyzeVelocity(samples);

        // Accumulate risk
        if (combat.confidence > ALERT_THRESHOLD) {
            combatRisk += combat.confidence * 12.0;
            alert(combat);
        }
        if (movement.confidence > ALERT_THRESHOLD) {
            movementRisk += movement.confidence * 12.0;
            alert(movement);
        }
        if (packet.confidence > ALERT_THRESHOLD) {
            movementRisk += packet.confidence * 10.0;
            alert(packet);
        }
        if (velocity.confidence > ALERT_THRESHOLD) {
            combatRisk += velocity.confidence * 10.0;
            alert(velocity);
        }

        // Kick if temporal risk is very high (persistent cheating)
        if (combatRisk > RISK_KICK_THRESHOLD) {
            kick("CombatML risk=" + String.format("%.0f", combatRisk));
            combatRisk = 0;
        } else if (movementRisk > RISK_KICK_THRESHOLD) {
            kick("MovementML risk=" + String.format("%.0f", movementRisk));
            movementRisk = 0;
        }
    }

    // ==================== COMBAT ANALYSIS ====================

    private AnalysisResult analyzeCombat(List<BehaviorSample> samples) {
        double confidence = 0.0;
        String reason = "";

        // CPS consistency (autoclicker = very consistent CPS)
        double cpsVar = varianceDouble(samples.stream()
                .mapToDouble(s -> s.cps).boxed().collect(java.util.stream.Collectors.toList()));
        double avgCps = samples.stream().mapToDouble(s -> s.cps).average().orElse(0);
        if (avgCps >= 12 && cpsVar < 1.5) {
            confidence += 0.35;
            reason = "AutoClick cps=" + String.format("%.1f", avgCps) + " var=" + String.format("%.2f", cpsVar);
        }

        // Low click entropy + high CPS = autoclicker
        double avgEntropy = samples.stream().mapToDouble(s -> s.clickEntropy).average().orElse(1.0);
        if (avgCps >= 10 && avgEntropy < 0.25) {
            confidence += 0.35;
            reason = "AutoClick entropy=" + String.format("%.2f", avgEntropy);
        }

        // Rotation variance (aimbot = very smooth rotation + high accuracy)
        double yawVar = average(samples.stream()
                .mapToDouble(s -> s.yawVariance).boxed().collect(java.util.stream.Collectors.toList()));
        double hitAcc = average(samples.stream()
                .mapToDouble(s -> s.hitAccuracy).boxed().collect(java.util.stream.Collectors.toList()));
        if (yawVar < 2.0 && hitAcc > 0.88 && hitAcc > 0) {
            confidence += 0.4;
            reason = "Aimbot yawVar=" + String.format("%.2f", yawVar) + " acc=" + String.format("%.0f%%", hitAcc * 100);
        }

        // Instant target switch with high accuracy
        double avgSwitchTime = average(samples.stream()
                .mapToDouble(s -> s.targetSwitchSpeed).boxed().collect(java.util.stream.Collectors.toList()));
        if (avgSwitchTime > 0 && avgSwitchTime < 100 && hitAcc > 0.85) {
            confidence += 0.3;
            reason = "KillAura switch=" + String.format("%.0fms", avgSwitchTime);
        }

        // Snap count (sudden large angle changes = aimbot snapping)
        double avgSnaps = average(samples.stream()
                .mapToDouble(s -> s.snapCount).boxed().collect(java.util.stream.Collectors.toList()));
        if (avgSnaps > 4) {
            confidence += 0.25;
            reason = "AimbotSnap snaps=" + String.format("%.1f", avgSnaps) + "/5s";
        }

        return new AnalysisResult(Math.min(1.0, confidence), "Combat", reason);
    }

    // ==================== MOVEMENT ANALYSIS ====================

    private AnalysisResult analyzeMovement(List<BehaviorSample> samples) {
        double confidence = 0.0;
        String reason = "";

        // Speed hack: consistently WELL above sprint speed (0.29 b/t)
        // 0.45 gives headroom for sprint-jump peaks (~0.35-0.40) and Speed II (~0.38)
        double avgSpeed = average(samples.stream()
                .mapToDouble(s -> s.avgSpeed).boxed().collect(java.util.stream.Collectors.toList()));
        double speedVar = average(samples.stream()
                .mapToDouble(s -> s.speedVariance).boxed().collect(java.util.stream.Collectors.toList()));
        if (avgSpeed > 0.45 && speedVar < 0.01) {
            confidence += 0.5;
            reason = "Speed avg=" + String.format("%.3f", avgSpeed);
        }

        // Fly hack: high air/ground ratio + deltaY not falling
        // airRatio > 2.5 means player spends 2.5x more time in air than on ground
        double airRatio = average(samples.stream()
                .mapToDouble(s -> s.airGroundRatio).boxed().collect(java.util.stream.Collectors.toList()));
        double avgDeltaY = average(samples.stream()
                .mapToDouble(s -> s.avgDeltaY).boxed().collect(java.util.stream.Collectors.toList()));
        if (airRatio > 3.0 && avgDeltaY > -0.03) {
            confidence += 0.55;
            reason = "Fly airRatio=" + String.format("%.1f", airRatio) + " dY=" + String.format("%.3f", avgDeltaY);
        }

        // Bhop removed — sprint-jumping is legitimate and causes false positives

        return new AnalysisResult(Math.min(1.0, confidence), "Movement", reason);
    }

    // ==================== PACKET ANALYSIS ====================

    private AnalysisResult analyzePackets(List<BehaviorSample> samples) {
        double confidence = 0.0;
        String reason = "";

        // Timer: high PPS with very low timing variance
        double avgPps = average(samples.stream()
                .mapToDouble(s -> s.pps).boxed().collect(java.util.stream.Collectors.toList()));
        double timingVar = average(samples.stream()
                .mapToDouble(s -> s.packetTimingVariance).boxed().collect(java.util.stream.Collectors.toList()));

        if (avgPps > 24 && timingVar < 15) {
            confidence += 0.55;
            reason = "Timer pps=" + String.format("%.1f", avgPps) + " var=" + String.format("%.1f", timingVar);
        }

        // Blink: very low PPS spikes (holding packets then releasing)
        double ppsVar = varianceDouble(samples.stream()
                .mapToDouble(s -> s.pps).boxed().collect(java.util.stream.Collectors.toList()));
        double minPps = samples.stream().mapToDouble(s -> s.pps).min().orElse(20);
        double maxPps = samples.stream().mapToDouble(s -> s.pps).max().orElse(20);
        if (minPps < 4 && maxPps > 35 && ppsVar > 80) {
            confidence += 0.5;
            reason = "Blink pps=" + String.format("%.0f", minPps) + "-" + String.format("%.0f", maxPps);
        }

        return new AnalysisResult(Math.min(1.0, confidence), "Packet", reason);
    }

    // ==================== VELOCITY ANALYSIS ====================

    private AnalysisResult analyzeVelocity(List<BehaviorSample> samples) {
        double avgKbCompliance = average(samples.stream()
                .mapToDouble(s -> s.knockbackComplianceRate).filter(v -> v > 0)
                .boxed().collect(java.util.stream.Collectors.toList()));

        if (avgKbCompliance <= 0) return new AnalysisResult(0, "Velocity", "");

        double confidence = 0.0;
        String reason = "";

        if (avgKbCompliance < 0.15) {
            confidence = 0.85;
            reason = "AntiKB compliance=" + String.format("%.0f%%", avgKbCompliance * 100);
        } else if (avgKbCompliance < 0.35) {
            confidence = 0.5;
            reason = "ReducedKB compliance=" + String.format("%.0f%%", avgKbCompliance * 100);
        }

        return new AnalysisResult(Math.min(1.0, confidence), "Velocity", reason);
    }

    // ==================== HELPERS ====================

    private void alert(AnalysisResult result) {
        if (alertCooldown > 0) return;
        if (result.reason.isEmpty()) return;

        alertCooldown = ALERT_COOLDOWN_TICKS;
        Player player = user.getPlayer();
        if (player == null || !player.isOnline()) return;

        String conf = String.format("%.0f%%", result.confidence * 100);
        String msg = "§b§lGAC-ML §8» §e" + player.getName() +
                " §7[§bLocal ML§7] §c" + result.category + ": §f" + result.reason +
                " §7(§b" + conf + "§7)";

        GAC.getInstance().getLogger().info(msg);
        Bukkit.getScheduler().runTask(GAC.getInstance(), () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.hasPermission("gac.alerts")) p.sendMessage(msg);
            }
        });
    }

    private void kick(String reason) {
        Player player = user.getPlayer();
        if (player == null || !player.isOnline()) return;

        Bukkit.getScheduler().runTask(GAC.getInstance(), () -> {
            if (!player.isOnline()) return;
            GAC.incrementKicks();
            player.kickPlayer("§b§lGAC \n\n§7Unfair Advantage detected.\n§fCheck: §bLocalML\n§8(" + reason + ")");
            String msg = "§b§lGAC-ML §8» §cKicked §e" + player.getName() + " §7via LocalML §8(" + reason + ")";
            GAC.getInstance().getLogger().warning(msg);
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.hasPermission("gac.alerts")) p.sendMessage(msg);
            }
        });
    }

    private double average(List<Double> values) {
        if (values.isEmpty()) return 0.0;
        return values.stream().mapToDouble(d -> d).average().orElse(0.0);
    }

    private double varianceDouble(List<Double> values) {
        if (values.size() < 2) return 0.0;
        double mean = average(values);
        return values.stream().mapToDouble(d -> Math.pow(d - mean, 2)).average().orElse(0.0);
    }

    public static class AnalysisResult {
        public final double confidence;
        public final String category;
        public final String reason;

        public AnalysisResult(double confidence, String category, String reason) {
            this.confidence = confidence;
            this.category = category;
            this.reason = reason;
        }
    }
}
