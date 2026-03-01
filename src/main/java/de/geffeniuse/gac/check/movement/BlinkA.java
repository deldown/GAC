package de.geffeniuse.gac.check.movement;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketEvent;
import de.geffeniuse.gac.GAC;
import de.geffeniuse.gac.check.Check;
import de.geffeniuse.gac.data.GACUser;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * BlinkA - Blink/Packet Hold Detection
 * Detects players holding packets and releasing them all at once.
 * This creates a "teleport" effect when they release the packets.
 */
public class BlinkA extends Check {

    private final ConcurrentLinkedDeque<Long> packetTimes = new ConcurrentLinkedDeque<>();
    private long lastPacketTime = 0;
    private int noPacketTicks = 0;
    private Location lastLocation = null;
    private int suspicion = 0;

    private static final int MAX_NO_PACKET_TIME = 1000; // 1 second without packets = suspicious
    private static final double TELEPORT_DISTANCE = 4.0; // Teleporting 4+ blocks after silence
    private static final int SUSPICION_THRESHOLD = 2;
    private static final int KICK_THRESHOLD = 4;

    public BlinkA(GACUser user) {
        super(user, "Blink", "Detects packet holding.");
    }
    
    public void reset() {
        packetTimes.clear();
        lastPacketTime = System.currentTimeMillis();
        lastLocation = null;
        suspicion = 0;
    }

    @Override
    public void onPacket(PacketEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.POSITION &&
            event.getPacketType() != PacketType.Play.Client.POSITION_LOOK &&
            event.getPacketType() != PacketType.Play.Client.LOOK &&
            event.getPacketType() != PacketType.Play.Client.GROUND) {
            return;
        }

        Player player = user.getPlayer();
        if (player == null) return;

        if (player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }

        long now = System.currentTimeMillis();

        // Track packet timing
        packetTimes.add(now);
        packetTimes.removeIf(t -> now - t > 5000);

        // Time since last packet
        long timeSinceLastPacket = now - lastPacketTime;

        // Check for blink pattern
        Bukkit.getScheduler().runTask(GAC.getInstance(), () -> {
            if (player == null || !player.isOnline()) return;

            Location currentLoc = player.getLocation();

            // Pattern 1: Long silence followed by big teleport
            int ping = player.getPing();
            long maxSilence = Math.max(MAX_NO_PACKET_TIME, ping * 3); // Dynamic threshold
            
            if (lastPacketTime > 0 && timeSinceLastPacket > maxSilence) {
                if (lastLocation != null) {
                    double distance = currentLoc.distance(lastLocation);

                    // Allow more distance if silence was longer (legit lag)
                    double maxDist = TELEPORT_DISTANCE + (timeSinceLastPacket / 500.0);
                    
                    if (distance > maxDist) {
                        suspicion += 3;

                        if (suspicion >= SUSPICION_THRESHOLD) {
                            fail(String.format("teleport=%.1f after %dms", distance, timeSinceLastPacket));
                            suspicion = 0;

                            // Teleport back
                            player.teleport(lastLocation);

                            if (getViolationLevel() >= KICK_THRESHOLD) {
                                GAC.incrementKicks();
                                player.kickPlayer("§b§lGAC \n\n§7Unfair Advantage detected.\n§fCheck: §bBlink");
                            }
                        }
                    }
                }
            }

            // Pattern 2: Packet burst after silence
            // Count packets in the last 500ms
            int recentPackets = 0;
            Long[] times = packetTimes.toArray(new Long[0]); // Snapshot to avoid CME
            for (long t : times) {
                if (now - t < 500) recentPackets++;
            }

            // Normal is ~10 packets per 500ms, blink release can be 20+
            // Scale burst limit with ping (laggy players burst packets naturally)
            int burstLimit = 15 + (ping / 50);
            
            if (timeSinceLastPacket > 500 && recentPackets > burstLimit) {
                suspicion += 2;

                if (suspicion >= SUSPICION_THRESHOLD) {
                    fail("packetBurst=" + recentPackets + " after " + timeSinceLastPacket + "ms");
                    suspicion = 0;

                    if (getViolationLevel() >= KICK_THRESHOLD) {
                        GAC.incrementKicks();
                        player.kickPlayer("§b§lGAC \n\n§7Unfair Advantage detected.\n§fCheck: §bBlink");
                    }
                }
            }

            // Pattern 3: Impossible speed after silence
            if (lastLocation != null && timeSinceLastPacket > 500) {
                double distance = currentLoc.distance(lastLocation);
                double speed = distance / (timeSinceLastPacket / 1000.0);

                // Normal max speed is ~0.4 blocks per tick = 8 blocks per second
                // Allow some buffer for lag
                // Scale speed limit with ping somewhat (lag compensation)
                double speedLimit = 15 + (ping / 100.0);
                
                if (speed > speedLimit && distance > 5) {
                    suspicion += 2;

                    if (suspicion >= SUSPICION_THRESHOLD) {
                        fail(String.format("speed=%.1f after silence", speed));
                        suspicion = 0;
                    }
                }
            }

            // Decay suspicion on normal packets
            if (timeSinceLastPacket < 100) {
                suspicion = Math.max(0, suspicion - 1);
            }

            lastLocation = currentLoc.clone();
        });

        lastPacketTime = now;
    }
}
