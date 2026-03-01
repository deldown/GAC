package de.geffeniuse.gac.check.movement;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketEvent;
import de.geffeniuse.gac.GAC;
import de.geffeniuse.gac.check.Check;
import de.geffeniuse.gac.data.GACUser;
import org.bukkit.Bukkit;

import java.util.LinkedList;

/**
 * TimerA - Timer/Game Speed Detection
 * Detects when players send packets faster than normal (timer hack).
 * Normal Minecraft runs at 20 TPS (50ms per tick).
 */
public class TimerA extends Check {

    private final LinkedList<Long> packetTimes = new LinkedList<>();
    private long lastPacketTime = 0;
    private long balanceTime = 0;
    private int violations = 0;
    private int goodPackets = 0; // Track normal packets

    // Expected ~20 packets per second (50ms each)
    private static final long EXPECTED_INTERVAL = 50;
    private static final double MAX_SPEED = 1.08; // Allow 8% variance - detects Timer 1.1+
    private static final double MIN_SPEED = 0.4;
    private static final int SAMPLE_SIZE = 50; // More samples for accuracy
    private static final int KICK_THRESHOLD = 10;
    private static final int VIOLATION_THRESHOLD = 5; // Less sensitive

    public TimerA(GACUser user) {
        super(user, "Timer", "Detects timer/speed hacks.");
    }

    @Override
    public void onPacket(PacketEvent event) {
        // Only count POSITION packets (not LOOK or GROUND - those don't indicate timer)
        if (event.getPacketType() != PacketType.Play.Client.POSITION &&
            event.getPacketType() != PacketType.Play.Client.POSITION_LOOK) {
            return;
        }

        // Only check if actually moving
        double deltaXZ = user.getDeltaXZ();
        double deltaY = Math.abs(user.getDeltaY());
        if (deltaXZ < 0.01 && deltaY < 0.01) {
            // Not moving - don't count
            goodPackets++;
            return;
        }

        long now = System.currentTimeMillis();

        if (lastPacketTime == 0) {
            lastPacketTime = now;
            return;
        }

        long interval = now - lastPacketTime;
        lastPacketTime = now;

        // Ignore very long intervals (lag/afk)
        if (interval > 1000) {
            packetTimes.clear();
            balanceTime = 0;
            goodPackets = 0;
            return;
        }

        // Ignore very short intervals (can be lag spikes)
        if (interval < 10) {
            return;
        }

        packetTimes.add(interval);
        while (packetTimes.size() > SAMPLE_SIZE) {
            packetTimes.removeFirst();
        }

        // Balance tracking
        balanceTime += EXPECTED_INTERVAL - interval;

        // Only check if we have enough samples
        if (packetTimes.size() >= SAMPLE_SIZE) {
            double avgInterval = getAverageInterval();
            double speed = (double) EXPECTED_INTERVAL / avgInterval;

            // Check if consistently too fast
            if (speed > MAX_SPEED) {
                violations++;
                goodPackets = 0;

                if (violations >= VIOLATION_THRESHOLD) {
                    fail(String.format("speed=%.2fx", speed));
                    violations = 0;
                    packetTimes.clear();
                    balanceTime = 0;

                    if (getViolationLevel() >= KICK_THRESHOLD) {
                        Bukkit.getScheduler().runTask(GAC.getInstance(), () -> {
                            if (user.getPlayer() != null && user.getPlayer().isOnline()) {
                                GAC.incrementKicks();
                                user.getPlayer().kickPlayer("§b§lGAC \n\n§7Unfair Advantage detected.\n§fCheck: §bTimer");
                            }
                        });
                    }
                }
            } else {
                // Normal speed - decay violations
                goodPackets++;
                if (goodPackets > 20) {
                    violations = Math.max(0, violations - 1);
                    goodPackets = 0;
                }
            }
        }

        // Cap balance
        if (balanceTime > 500) balanceTime = 500;
        if (balanceTime < -500) balanceTime = -500;
    }

    private double getAverageInterval() {
        if (packetTimes.isEmpty()) return EXPECTED_INTERVAL;
        long sum = 0;
        for (long interval : packetTimes) {
            sum += interval;
        }
        return (double) sum / packetTimes.size();
    }
}
