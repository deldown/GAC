package de.geffeniuse.gac.check.combat;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketEvent;
import de.geffeniuse.gac.GAC;
import de.geffeniuse.gac.check.Check;
import de.geffeniuse.gac.data.GACUser;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.LinkedList;

/**
 * KillauraJ - Reach/Distance Analysis
 * Detects reach hacks by analyzing hit distances.
 * Normal reach is 3.0, extended reach goes up to 6.0+
 */
public class KillauraJ extends Check {

    private final LinkedList<Double> hitDistances = new LinkedList<>();
    private int suspicion = 0;
    private int longReachCount = 0;

    // Thresholds
    private static final double MAX_REACH = 3.2; // Vanilla is 3.0, allow slight buffer
    private static final double SUSPICIOUS_REACH = 3.5; // Very suspicious
    private static final double DEFINITE_REACH = 4.0; // Impossible without hacks
    private static final int MAX_SAMPLES = 30;
    private static final int SUSPICION_THRESHOLD = 5;
    private static final int KICK_THRESHOLD = 5;

    public KillauraJ(GACUser user) {
        super(user, "Killaura", "Reach detection.");
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

        // Schedule on main thread for entity access
        Bukkit.getScheduler().runTask(GAC.getInstance(), () -> {
            if (player == null || !player.isOnline() || !isEnabled()) return;

            try {
                // Get attacked entity
                int entityId = event.getPacket().getIntegers().read(0);
                Entity target = null;

                for (Entity e : player.getWorld().getEntities()) {
                    if (e.getEntityId() == entityId) {
                        target = e;
                        break;
                    }
                }

                if (!(target instanceof LivingEntity)) return;

                double distance;
                
                // PING COMPENSATION LOGIC
                int ping = player.getPing();
                org.bukkit.util.Vector eye = player.getEyeLocation().toVector();
                org.bukkit.util.BoundingBox box = target.getBoundingBox();

                if (target instanceof Player) {
                    org.bukkit.util.Vector targetVel = target.getVelocity();
                    double pingSeconds = (ping + 50) / 1000.0;
                    
                    double compX = targetVel.getX() * pingSeconds;
                    double compY = targetVel.getY() * pingSeconds;
                    double compZ = targetVel.getZ() * pingSeconds;
                    
                    org.bukkit.util.BoundingBox expanded = box.clone().expand(Math.abs(compX), Math.abs(compY), Math.abs(compZ));
                    expanded.expand(0.1);
                    
                    double dx = Math.max(0, Math.max(expanded.getMinX() - eye.getX(), eye.getX() - expanded.getMaxX()));
                    double dy = Math.max(0, Math.max(expanded.getMinY() - eye.getY(), eye.getY() - expanded.getMaxY()));
                    double dz = Math.max(0, Math.max(expanded.getMinZ() - eye.getZ(), eye.getZ() - expanded.getMaxZ()));
                    
                    distance = Math.sqrt(dx*dx + dy*dy + dz*dz);
                } else {
                    double dx = Math.max(0, Math.max(box.getMinX() - eye.getX(), eye.getX() - box.getMaxX()));
                    double dy = Math.max(0, Math.max(box.getMinY() - eye.getY(), eye.getY() - box.getMaxY()));
                    double dz = Math.max(0, Math.max(box.getMinZ() - eye.getZ(), eye.getZ() - box.getMaxZ()));
                    distance = Math.sqrt(dx*dx + dy*dy + dz*dz);
                }

                // Store distance
                hitDistances.add(distance);
                while (hitDistances.size() > MAX_SAMPLES) {
                    hitDistances.removeFirst();
                }

                // ========== CHECK 1: Single impossible reach ==========
                if (distance > 4.5) { // Updated to 4.5
                    suspicion += 3;
                    if (suspicion >= SUSPICION_THRESHOLD) {
                        fail(String.format("reach=%.2f (impossible)", distance));
                        suspicion = 0;

                        if (getViolationLevel() >= KICK_THRESHOLD) {
                            kick(player);
                        }
                    }
                }

                // ========== CHECK 2: Suspicious reach ==========
                else if (distance > 3.5) { // Updated to 3.5
                    suspicion += 2;
                    longReachCount++;

                    if (longReachCount >= 4 && suspicion >= SUSPICION_THRESHOLD) {
                        fail(String.format("reach=%.2f x%d", distance, longReachCount));
                        suspicion = 0;
                        longReachCount = 0;

                        if (getViolationLevel() >= KICK_THRESHOLD) {
                            kick(player);
                        }
                    }
                }

                // ========== CHECK 3: Extended reach pattern ==========
                else if (distance > 3.1) { // Updated to 3.1
                    longReachCount++;

                    // Multiple slightly-extended reaches = suspicious
                    if (longReachCount >= 6) {
                        suspicion += 2;

                        if (suspicion >= SUSPICION_THRESHOLD) {
                            double avgReach = getAverageDistance();
                            fail(String.format("avgReach=%.2f count=%d", avgReach, longReachCount));
                            suspicion = 0;
                            longReachCount = 0;
                        }
                    }
                } else {
                    // Normal hit - decay
                    longReachCount = Math.max(0, longReachCount - 1);
                    suspicion = Math.max(0, suspicion - 1);
                }

            } catch (Exception e) {
                // Ignore packet reading errors
            }
        });
    }

    private double getAverageDistance() {
        if (hitDistances.isEmpty()) return 0;
        double sum = 0;
        for (double d : hitDistances) sum += d;
        return sum / hitDistances.size();
    }

    private void kick(Player player) {
        GAC.incrementKicks();
        player.kickPlayer("§b§lGAC \n\n§7Unfair Advantage detected.\n§fCheck: §bReach");
    }
}
