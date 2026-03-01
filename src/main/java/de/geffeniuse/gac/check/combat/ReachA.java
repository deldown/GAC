package de.geffeniuse.gac.check.combat;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import de.geffeniuse.gac.GAC;
import de.geffeniuse.gac.check.Check;
import de.geffeniuse.gac.data.GACUser;
import de.geffeniuse.gac.util.PacketLocation;
import org.bukkit.GameMode;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

public class ReachA extends Check {

    private static final double BASE_MAX_REACH = 3.3; // Base reach for clean players

    public ReachA(GACUser user) {
        super(user, "Reach", "Checks distance to target hitbox (Backtracked).");
    }

    /**
     * Get effective max reach - reduced for suspicious players via ML
     * Clean player: 3.1 blocks
     * Suspicious player (50% reduction): 3.1 * 0.5 = ~1.55 blocks (very harsh)
     */
    private double getEffectiveMaxReach() {
        double reduction = user.getMitigation().getEffectiveReachReduction();
        // Apply reduction: 0.0 = no change, 0.3 = 30% less reach allowed
        return BASE_MAX_REACH * (1.0 - reduction);
    }

    @Override
    public void onPacket(PacketEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.USE_ENTITY) return;
        PacketContainer packet = event.getPacket();

        try {
            int entityId = packet.getIntegers().read(0);
            
            // We still need the main thread to get the target entity safely
            org.bukkit.Bukkit.getScheduler().runTask(GAC.getInstance(), () -> {
                try {
                    Player player = user.getPlayer();
                    if (player == null || !player.isOnline() || !isEnabled()) return;
                    if (player.getGameMode() == GameMode.CREATIVE) return;

                    Entity target = null;
                    for (Entity e : player.getWorld().getEntities()) {
                        if (e.getEntityId() == entityId) {
                            target = e;
                            break;
                        }
                    }
                    
                    if (target == null) return;

                    double distance;

                    // BACKTRACKING: Only works if target is a player
                    if (target instanceof Player) {
                        Player targetPlayer = (Player) target;
                        
                        int ping = player.getPing();
                        
                        // Ping Compensation
                        Vector eye = player.getEyeLocation().toVector();
                        BoundingBox box = target.getBoundingBox();
                        
                        // Cap ping at 400ms to prevent massive reach exploits with fake lag, but allow legitimate high ping
                        int cappedPing = Math.min(ping, 400);
                        
                        Vector targetVel = target.getVelocity(); // This is server-side velocity
                        double pingSeconds = (cappedPing + 100) / 1000.0; // +100ms buffer
                        
                        // Predict where they WERE
                        double compX = targetVel.getX() * pingSeconds;
                        double compY = targetVel.getY() * pingSeconds;
                        double compZ = targetVel.getZ() * pingSeconds;
                        
                        // Expand the box backwards to cover the "trail"
                        BoundingBox expanded = box.clone().expand(Math.abs(compX), Math.abs(compY), Math.abs(compZ));
                        
                        // Also expand by small amount for general leniency and high ping jitter
                        // Low ping (50ms): 0.15 + 0.025 = 0.175 buffer -> Max ~3.27 reach allowed
                        // High ping (400ms): 0.15 + 0.2 = 0.35 buffer -> Max ~3.45 reach allowed
                        expanded.expand(0.15 + (cappedPing / 2000.0));

                        // Raytrace against this "Time Smeared" box
                        double dx = Math.max(0, Math.max(expanded.getMinX() - eye.getX(), eye.getX() - expanded.getMaxX()));
                        double dy = Math.max(0, Math.max(expanded.getMinY() - eye.getY(), eye.getY() - expanded.getMaxY()));
                        double dz = Math.max(0, Math.max(expanded.getMinZ() - eye.getZ(), eye.getZ() - expanded.getMaxZ()));
                        
                        distance = Math.sqrt(dx*dx + dy*dy + dz*dz);
                    } else {
                        // Mob logic - simpler but with buffer
                        Vector eye = player.getEyeLocation().toVector();
                        BoundingBox box = target.getBoundingBox().expand(0.2);
                        double dx = Math.max(0, Math.max(box.getMinX() - eye.getX(), eye.getX() - box.getMaxX()));
                        double dy = Math.max(0, Math.max(box.getMinY() - eye.getY(), eye.getY() - box.getMaxY()));
                        double dz = Math.max(0, Math.max(box.getMinZ() - eye.getZ(), eye.getZ() - box.getMaxZ()));
                        distance = Math.sqrt(dx*dx + dy*dy + dz*dz);
                    }

                    // Dynamic reach - reduced for suspicious players via ML
                    double effectiveMaxReach = getEffectiveMaxReach();

                    if (distance > effectiveMaxReach) {
                        // Only fail if ping is stable-ish or distance is egregious
                        if (distance > effectiveMaxReach + 1.0 || player.getPing() < 500) {
                            fail("dist=" + String.format("%.2f", distance) + " max=" + String.format("%.2f", effectiveMaxReach));

                            // MITIGATION: Tell mitigation manager about reach violation
                            // Next hits will be cancelled
                            boolean shouldKick = user.getMitigation().onReachViolation(distance, effectiveMaxReach);

                            // Only kick for EXTREME reach (4+ blocks)
                            if (shouldKick && distance > 4.0) {
                                GAC.incrementKicks();
                                player.kickPlayer("§b§lGAC \n\n§7Unfair Advantage detected.\n§fCheck: §bReach (blatant)");
                            }
                        }
                    }
                } catch (Exception e) {
                     // ignore
                }
            });

        } catch (Exception e) {
            // log to console
        }
    }
}
