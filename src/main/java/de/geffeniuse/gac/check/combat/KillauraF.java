package de.geffeniuse.gac.check.combat;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.EnumWrappers;
import de.geffeniuse.gac.check.Check;
import de.geffeniuse.gac.data.GACUser;

import java.util.LinkedList;

/**
 * KillauraF - Hit Consistency Detection
 * Detects suspiciously consistent hit timing (autoclickers/aura).
 * Humans have natural variance in reaction time, bots are too perfect.
 */
public class KillauraF extends Check {

    private final LinkedList<Long> hitTimes = new LinkedList<>();
    private int consistentCount = 0;
    private int perfectCount = 0; // Count of 100% consistency

    // Thresholds
    private static final int SAMPLE_SIZE = 10;
    private static final double MAX_CONSISTENCY = 0.85; // 85% = suspicious
    private static final double PERFECT_CONSISTENCY = 0.98; // 98%+ = very suspicious
    private static final long TIGHT_WINDOW_MS = 25;
    private static final int CONSISTENT_THRESHOLD = 4;
    private static final int PERFECT_KICK_THRESHOLD = 3; // 3x perfect = kick

    public KillauraF(GACUser user) {
        super(user, "Killaura (Timing)", "Detects consistent hit timing.");
    }

    @Override
    public void onPacket(PacketEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.USE_ENTITY) return;

        EnumWrappers.EntityUseAction action;
        try {
            action = event.getPacket().getEnumEntityUseActions().read(0).getAction();
        } catch (Exception e) {
            return;
        }

        if (action != EnumWrappers.EntityUseAction.ATTACK) return;

        long now = System.currentTimeMillis();
        hitTimes.addLast(now);

        // Keep only recent hits
        while (hitTimes.size() > SAMPLE_SIZE) {
            hitTimes.removeFirst();
        }

        // Need enough samples
        if (hitTimes.size() < SAMPLE_SIZE) return;

        // Calculate intervals between hits
        long[] intervals = new long[hitTimes.size() - 1];
        for (int i = 0; i < intervals.length; i++) {
            intervals[i] = hitTimes.get(i + 1) - hitTimes.get(i);
        }

        // Calculate average interval
        long sum = 0;
        for (long interval : intervals) {
            sum += interval;
        }
        double avgInterval = (double) sum / intervals.length;

        // Skip if hits are too slow (> 1 second apart on average)
        if (avgInterval > 1000) return;

        // Count how many intervals are within tight window of average
        int consistentIntervals = 0;
        for (long interval : intervals) {
            if (Math.abs(interval - avgInterval) < TIGHT_WINDOW_MS) {
                consistentIntervals++;
            }
        }

        double consistency = (double) consistentIntervals / intervals.length;

        // Perfect consistency = instant kick after 3 times
        if (consistency >= PERFECT_CONSISTENCY) {
            perfectCount++;
            fail(String.format("PERFECT consistency=%.0f%% avg=%.0fms", consistency * 100, avgInterval));

            if (perfectCount >= PERFECT_KICK_THRESHOLD) {
                // Kick for blatant autoclicker
                org.bukkit.Bukkit.getScheduler().runTask(de.geffeniuse.gac.GAC.getInstance(), () -> {
                    if (user.getPlayer() != null && user.getPlayer().isOnline()) {
                        de.geffeniuse.gac.GAC.incrementKicks();
                        user.getPlayer().kickPlayer("§b§lGAC \n\n§7Unfair Advantage detected.\n§fCheck: §bAutoClicker");
                    }
                });
                perfectCount = 0;
            }
            hitTimes.clear();
            return;
        }

        // Regular consistency check
        if (consistency > MAX_CONSISTENCY) {
            consistentCount++;
            if (consistentCount >= CONSISTENT_THRESHOLD) {
                fail(String.format("consistency=%.0f%% avg=%.0fms x%d", consistency * 100, avgInterval, consistentCount));
                consistentCount = 0;
                hitTimes.clear();
            }
        } else {
            // Decay
            consistentCount = Math.max(0, consistentCount - 1);
            perfectCount = Math.max(0, perfectCount - 1);
        }
    }
}
