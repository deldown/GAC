package de.geffeniuse.gac.check.combat;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketEvent;
import de.geffeniuse.gac.GAC;
import de.geffeniuse.gac.check.Check;
import de.geffeniuse.gac.data.GACUser;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

/**
 * KillauraL - Multi-Aura/Target Switching Detection
 * Detects when players hit multiple targets in rapid succession.
 * Normal players focus on one target, killaura hits everyone nearby.
 */
public class KillauraL extends Check {

    // Track hits per target
    private final Map<Integer, Long> targetHitTimes = new HashMap<>();
    private final LinkedList<Integer> hitSequence = new LinkedList<>();
    private final LinkedList<Long> hitTimes = new LinkedList<>();

    private int lastTargetId = -1;
    private long lastSwitchTime = 0;
    private int rapidSwitchCount = 0;
    private int suspicion = 0;

    // Thresholds - more aggressive
    private static final long RAPID_SWITCH_TIME = 300; // 300ms between target switches
    private static final int MAX_UNIQUE_TARGETS = 3; // 3 unique targets in 2 seconds
    private static final long WINDOW_TIME = 2000; // 2 second window
    private static final int SUSPICION_THRESHOLD = 3;
    private static final int KICK_THRESHOLD = 4;

    public KillauraL(GACUser user) {
        super(user, "Killaura", "Multi-target detection.");
    }

    @Override
    public void onPacket(PacketEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.USE_ENTITY) {
            return;
        }

        Player player = user.getPlayer();
        if (player == null) return;

        if (player.getGameMode() == GameMode.CREATIVE ||
            player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }

        try {
            int entityId = event.getPacket().getIntegers().read(0);
            long now = System.currentTimeMillis();

            // Track this hit
            hitTimes.add(now);
            hitSequence.add(entityId);
            targetHitTimes.put(entityId, now);

            // Clean old data
            hitTimes.removeIf(t -> now - t > WINDOW_TIME);
            hitSequence.removeIf(id -> {
                Long hitTime = targetHitTimes.get(id);
                return hitTime == null || now - hitTime > WINDOW_TIME;
            });
            targetHitTimes.entrySet().removeIf(e -> now - e.getValue() > WINDOW_TIME);

            // ========== CHECK 1: Rapid Target Switching ==========
            if (lastTargetId != -1 && lastTargetId != entityId) {
                long timeSinceSwitch = now - lastSwitchTime;

                if (timeSinceSwitch < RAPID_SWITCH_TIME) {
                    rapidSwitchCount++;

                    // Multiple rapid switches = multi-aura
                    if (rapidSwitchCount >= 3) {
                        suspicion += 2;

                        if (suspicion >= SUSPICION_THRESHOLD) {
                            fail(String.format("rapidSwitch x%d (%dms)", rapidSwitchCount, timeSinceSwitch));
                            suspicion = 0;
                            rapidSwitchCount = 0;

                            if (getViolationLevel() >= KICK_THRESHOLD) {
                                kick(player);
                            }
                        }
                    }
                } else {
                    rapidSwitchCount = Math.max(0, rapidSwitchCount - 1);
                }

                lastSwitchTime = now;
            }

            // ========== CHECK 2: Too Many Unique Targets ==========
            int uniqueTargets = targetHitTimes.size();

            if (uniqueTargets >= MAX_UNIQUE_TARGETS) {
                // Check hit distribution - killaura hits everyone equally
                Map<Integer, Integer> hitCounts = new HashMap<>();
                for (int id : hitSequence) {
                    hitCounts.merge(id, 1, Integer::sum);
                }

                // Check if hits are distributed (multi-aura pattern)
                boolean distributed = true;
                int maxHits = 0;
                int minHits = Integer.MAX_VALUE;

                for (int count : hitCounts.values()) {
                    maxHits = Math.max(maxHits, count);
                    minHits = Math.min(minHits, count);
                }

                // If all targets have similar hit counts = suspicious
                if (maxHits - minHits <= 2 && uniqueTargets >= 4) {
                    suspicion += 3;

                    if (suspicion >= SUSPICION_THRESHOLD) {
                        fail(String.format("multiTarget=%d distributed", uniqueTargets));
                        suspicion = 0;

                        if (getViolationLevel() >= KICK_THRESHOLD) {
                            kick(player);
                        }
                    }
                }

                // Simply too many targets in short time
                if (uniqueTargets >= 5) {
                    suspicion += 2;

                    if (suspicion >= SUSPICION_THRESHOLD) {
                        fail(String.format("targets=%d in 1s", uniqueTargets));
                        suspicion = 0;

                        if (getViolationLevel() >= KICK_THRESHOLD) {
                            kick(player);
                        }
                    }
                }
            }

            // ========== CHECK 3: Alternating Target Pattern ==========
            // Killaura often alternates: A-B-A-B-A-B
            if (hitSequence.size() >= 6) {
                int alternateCount = 0;
                Integer[] recent = hitSequence.toArray(new Integer[0]);

                for (int i = recent.length - 6; i < recent.length - 2; i++) {
                    if (i >= 0 && recent[i].equals(recent[i + 2]) && !recent[i].equals(recent[i + 1])) {
                        alternateCount++;
                    }
                }

                // Consistent alternating pattern
                if (alternateCount >= 3) {
                    suspicion += 2;

                    if (suspicion >= SUSPICION_THRESHOLD) {
                        fail("alternatingPattern");
                        suspicion = 0;

                        if (getViolationLevel() >= KICK_THRESHOLD) {
                            kick(player);
                        }
                    }
                }
            }

            lastTargetId = entityId;

            // Decay
            if (uniqueTargets <= 1) {
                suspicion = Math.max(0, suspicion - 1);
                rapidSwitchCount = Math.max(0, rapidSwitchCount - 1);
            }

        } catch (Exception e) {
            // Ignore packet errors
        }
    }

    private void kick(Player player) {
        GAC.incrementKicks();
        Bukkit.getScheduler().runTask(GAC.getInstance(), () -> {
            if (player != null && player.isOnline()) {
                player.kickPlayer("§b§lGAC \n\n§7Unfair Advantage detected.\n§fCheck: §bMulti-Aura");
            }
        });
    }
}
