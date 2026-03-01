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
import org.bukkit.util.Vector;

import java.util.LinkedList;

/**
 * KillauraK - Aimbot/Snap Detection
 * Detects instant snap aiming at targets (aimbot).
 * Checks rotation acceleration patterns on hits.
 */
public class KillauraK extends Check {

    // Track rotation before hits
    private final LinkedList<Float> preHitYawDeltas = new LinkedList<>();
    private final LinkedList<Float> preHitPitchDeltas = new LinkedList<>();
    private final LinkedList<Long> hitTimes = new LinkedList<>();

    private float lastYaw = 0;
    private float lastPitch = 0;
    private float lastYawDelta = 0;
    private float lastPitchDelta = 0;
    private long lastPacketTime = 0;
    private int snapCount = 0;
    private int suspicion = 0;

    // Thresholds - made less sensitive to reduce false positives
    private static final double SNAP_THRESHOLD = 45.0; // Sudden rotation > 45 degrees
    private static final double ACCELERATION_THRESHOLD = 25.0; // Sudden acceleration
    private static final int SUSPICION_THRESHOLD = 8;
    private static final int KICK_THRESHOLD = 8;
    private static final int MAX_SAMPLES = 20;

    public KillauraK(GACUser user) {
        super(user, "Killaura", "Aimbot detection.");
    }

    @Override
    public void onPacket(PacketEvent event) {
        Player player = user.getPlayer();
        if (player == null) return;

        if (player.getGameMode() == GameMode.CREATIVE ||
            player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }

        // Track rotation on LOOK packets
        if (event.getPacketType() == PacketType.Play.Client.LOOK ||
            event.getPacketType() == PacketType.Play.Client.POSITION_LOOK) {

            long now = System.currentTimeMillis();

            float yaw = player.getLocation().getYaw();
            float pitch = player.getLocation().getPitch();

            float yawDelta = Math.abs(yaw - lastYaw);
            float pitchDelta = Math.abs(pitch - lastPitch);

            // Normalize yaw delta
            if (yawDelta > 180) yawDelta = 360 - yawDelta;

            // Calculate acceleration (change in rotation speed)
            float yawAccel = Math.abs(yawDelta - lastYawDelta);
            float pitchAccel = Math.abs(pitchDelta - lastPitchDelta);

            // Store recent deltas for hit analysis
            preHitYawDeltas.add(yawDelta);
            preHitPitchDeltas.add(pitchDelta);
            while (preHitYawDeltas.size() > MAX_SAMPLES) {
                preHitYawDeltas.removeFirst();
            }
            while (preHitPitchDeltas.size() > MAX_SAMPLES) {
                preHitPitchDeltas.removeFirst();
            }

            lastYaw = yaw;
            lastPitch = pitch;
            lastYawDelta = yawDelta;
            lastPitchDelta = pitchDelta;
            lastPacketTime = now;
        }

        // Check on INTERACT (hit)
        if (event.getPacketType() == PacketType.Play.Client.USE_ENTITY) {
            long now = System.currentTimeMillis();

            // Track hit times
            hitTimes.add(now);
            hitTimes.removeIf(t -> now - t > 3000);

            Bukkit.getScheduler().runTask(GAC.getInstance(), () -> {
                if (player == null || !player.isOnline()) return;

                try {
                    int entityId = event.getPacket().getIntegers().read(0);
                    Entity target = null;

                    for (Entity e : player.getWorld().getEntities()) {
                        if (e.getEntityId() == entityId) {
                            target = e;
                            break;
                        }
                    }

                    if (!(target instanceof LivingEntity)) return;

                    // ========== CHECK 1: Snap before hit ==========
                    // Large sudden rotation right before hitting
                    if (preHitYawDeltas.size() >= 3) {
                        // Get the rotation in the last few packets
                        float recentYaw = 0;
                        float recentPitch = 0;
                        int count = 0;
                        for (int i = preHitYawDeltas.size() - 1; i >= preHitYawDeltas.size() - 3 && i >= 0; i--) {
                            recentYaw += preHitYawDeltas.get(i);
                            recentPitch += preHitPitchDeltas.get(i);
                            count++;
                        }

                        if (count > 0) {
                            recentYaw /= count;
                            recentPitch /= count;

                            double totalSnap = Math.sqrt(recentYaw * recentYaw + recentPitch * recentPitch);

                            if (totalSnap > SNAP_THRESHOLD) {
                                snapCount++;
                                suspicion += 2;

                                if (snapCount >= 3 && suspicion >= SUSPICION_THRESHOLD) {
                                    fail(String.format("snap=%.1f x%d", totalSnap, snapCount));
                                    snapCount = 0;
                                    suspicion = 0;

                                    if (getViolationLevel() >= KICK_THRESHOLD) {
                                        kick(player);
                                    }
                                }
                            }
                        }
                    }

                    // ========== CHECK 2: Perfect aim angle ==========
                    // Check if player is looking EXACTLY at target
                    Location eyeLoc = player.getEyeLocation();
                    Location targetLoc = ((LivingEntity) target).getEyeLocation();

                    Vector toTarget = targetLoc.toVector().subtract(eyeLoc.toVector()).normalize();
                    Vector lookDir = eyeLoc.getDirection();

                    double aimAngle = Math.toDegrees(Math.acos(Math.min(1.0, lookDir.dot(toTarget))));

                    // Perfect aim (< 2 degrees) with snap = very suspicious
                    if (aimAngle < 2.0 && lastYawDelta > 10) {
                        suspicion += 2;

                        if (suspicion >= SUSPICION_THRESHOLD) {
                            fail(String.format("perfectAim=%.1f° snap=%.1f", aimAngle, lastYawDelta));
                            suspicion = 0;

                            if (getViolationLevel() >= KICK_THRESHOLD) {
                                kick(player);
                            }
                        }
                    }

                    // ========== CHECK 3: Consistent snap pattern ==========
                    // Multiple hits with large rotations = aimbot
                    if (hitTimes.size() >= 5) {
                        double avgYawDelta = 0;
                        for (float d : preHitYawDeltas) avgYawDelta += d;
                        avgYawDelta /= preHitYawDeltas.size();

                        // High average rotation per hit = targeting multiple players
                        if (avgYawDelta > 15) {
                            suspicion++;
                        }
                    }

                } catch (Exception e) {
                    // Ignore errors
                }
            });

            // Decay on normal behavior
            if (suspicion > 0 && lastYawDelta < 5) {
                suspicion--;
            }
        }
    }

    private void kick(Player player) {
        GAC.incrementKicks();
        player.kickPlayer("§b§lGAC \n\n§7Unfair Advantage detected.\n§fCheck: §bAimbot");
    }
}
