package de.geffeniuse.gac.check.combat;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketEvent;
import de.geffeniuse.gac.check.Check;
import de.geffeniuse.gac.data.GACUser;
import de.geffeniuse.gac.util.MLDebug;

import java.util.ArrayList;
import java.util.List;

public class AutoClickerA extends Check {

    private final List<Long> intervals = new ArrayList<>();
    private final List<Long> clickTimes = new ArrayList<>();
    private long lastClickTime = 0;
    private int suspicion = 0;

    // Thresholds
    private static final double MAX_STDDEV = 10.0; // Humans usually > 12
    private static final int MAX_CPS = 25; // Butterfly/jitter clicking can reach 20-25
    private static final int SAMPLE_SIZE = 20; // More samples for accuracy

    public AutoClickerA(GACUser user) {
        super(user, "AutoClicker", "Analyzes click patterns.");
    }

    @Override
    public void onPacket(PacketEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.ARM_ANIMATION) return;

        org.bukkit.entity.Player player = user.getPlayer();
        if (player == null) return;

        // Ignore block interactions
        org.bukkit.block.Block targetBlock = player.getTargetBlockExact(5);
        if (targetBlock != null && targetBlock.getType().isSolid()) {
            intervals.clear();
            clickTimes.clear();
            return;
        }

        long now = System.currentTimeMillis();

        // Track click times for CPS
        clickTimes.add(now);
        clickTimes.removeIf(t -> now - t > 1000); // Keep last 1 second

        // Check CPS
        int cps = clickTimes.size();
        if (cps > MAX_CPS) {
            fail("cps=" + cps);
        }

        // Track intervals for consistency
        if (lastClickTime != 0) {
            long delta = now - lastClickTime;
            if (delta > 10 && delta < 400) {
                intervals.add(delta);
            }
        }
        lastClickTime = now;

        // Analyze pattern
        if (intervals.size() >= SAMPLE_SIZE) {
            analyzePattern();
        }
    }

    private void analyzePattern() {
        double stdDev = getStandardDeviation(intervals);
        double kurtosis = getKurtosis(intervals);

        // Check 1: Standard Deviation (consistency)
        boolean tooConsistent = stdDev < MAX_STDDEV;

        // Check 2: Kurtosis (distribution shape)
        // Normal human clicks have kurtosis ~3 (mesokurtic)
        // Autoclickers often have very high or very low kurtosis
        boolean abnormalDistribution = kurtosis < 1.5 || kurtosis > 8.0;

        // Check 3: Duplicate intervals (same delay repeated)
        long duplicates = countDuplicates(intervals);
        boolean tooManyDuplicates = duplicates > intervals.size() * 0.4;

        if (tooConsistent || (abnormalDistribution && tooManyDuplicates)) {
            suspicion++;
        } else {
            suspicion = Math.max(0, suspicion - 1);
        }

        // Calculate suspicion percentage for debug
        // Lower stdDev = more suspicious
        int suspicionPercent = 0;
        if (stdDev < MAX_STDDEV) {
            suspicionPercent = (int) ((1.0 - (stdDev / MAX_STDDEV)) * 80);
        }
        if (abnormalDistribution) {
            suspicionPercent += 20;
        }
        suspicionPercent = Math.min(100, suspicionPercent);

        org.bukkit.entity.Player player = user.getPlayer();
        if (player != null) {
            MLDebug.updateSuspicion(player.getUniqueId(), "AutoClicker", suspicionPercent);
        }

        if (suspicion >= 2) {
            String reason = tooConsistent ? "stdDev=" + String.format("%.1f", stdDev) :
                           "pattern (k=" + String.format("%.1f", kurtosis) + ")";
            fail(reason);
            suspicion = 0;
        }

        intervals.clear();
    }

    private double getStandardDeviation(List<Long> data) {
        double mean = data.stream().mapToLong(Long::longValue).average().orElse(0);
        double variance = data.stream().mapToDouble(a -> Math.pow(a - mean, 2)).sum() / data.size();
        return Math.sqrt(variance);
    }

    private double getKurtosis(List<Long> data) {
        double mean = data.stream().mapToLong(Long::longValue).average().orElse(0);
        double stdDev = getStandardDeviation(data);
        if (stdDev == 0) return 0;

        double fourthMoment = data.stream().mapToDouble(a -> Math.pow((a - mean) / stdDev, 4)).sum() / data.size();
        return fourthMoment; // Excess kurtosis would be -3
    }

    private long countDuplicates(List<Long> data) {
        return data.stream()
            .filter(a -> data.stream().filter(b -> Math.abs(a - b) < 5).count() > 1)
            .count();
    }
}
