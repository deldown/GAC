package de.geffeniuse.gac.check.combat;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketEvent;
import de.geffeniuse.gac.GAC;
import de.geffeniuse.gac.check.Check;
import de.geffeniuse.gac.data.GACUser;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

/**
 * CriticalsA - Criticals Hack Detection
 * Detects players getting critical hits without proper jumping.
 * Focuses on "Packet Criticals" (Micro-jumps / Packet Spoofing).
 */
public class CriticalsA extends Check implements Listener {

    private int suspicion = 0;
    private double lastY = 0;
    private int airTicks = 0;
    private int smallOffsetCount = 0;

    // Detection thresholds
    private static final int SUSPICION_THRESHOLD = 5;
    private static final int KICK_THRESHOLD = 10;

    public CriticalsA(GACUser user) {
        super(user, "Criticals", "Detects critical hit hacks.");
        Bukkit.getPluginManager().registerEvents(this, GAC.getInstance());
    }

    @Override
    public void onPacket(PacketEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.POSITION &&
            event.getPacketType() != PacketType.Play.Client.POSITION_LOOK) {
            return;
        }

        Player player = user.getPlayer();
        if (player == null) return;

        // Skip if gliding with elytra - false positive
        if (player.isGliding()) return;

        double currentY = user.getLastY();
        double deltaY = user.getDeltaY();
        
        // Check for specific Packet Criticals offsets
        // Common offsets: 0.0625, 0.015625, 1.1E-14, etc.
        // We look for tiny Y changes that are NOT full jumps (0.42)
        
        double absDelta = Math.abs(deltaY);
        
        if (absDelta > 0.0 && absDelta < 0.2) {
             // Modulo check for 0.0625 (1/16th block)
             // Many hacks use 1/16th intervals to glitch ground state
             if (absDelta % 0.015625 < 0.001 || Math.abs(absDelta - 0.0625) < 0.001 || Math.abs(absDelta - 0.0125) < 0.001) {
                 smallOffsetCount++;
                 
                 if (smallOffsetCount >= 3) {
                     // Suspicious mini-hopping pattern
                     Bukkit.getScheduler().runTask(GAC.getInstance(), () -> {
                         fail("packetCrit pattern (offset=" + String.format("%.4f", deltaY) + ")");
                     });
                     smallOffsetCount = 0;
                 }
             }
        } else {
            // Reset if normal movement or full stop
            if (absDelta > 0.3 || absDelta == 0) {
                smallOffsetCount = 0;
            }
        }

        Bukkit.getScheduler().runTask(GAC.getInstance(), () -> {
            if (player == null || !player.isOnline()) return;

            boolean onGround = isOnGround(player);

            if (onGround) {
                airTicks = 0;
            } else {
                airTicks++;
            }

            lastY = currentY;
        });
    }

    @EventHandler
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player)) return;

        Player player = (Player) event.getDamager();
        if (!player.getUniqueId().equals(user.getUuid())) return;

        if (player.getGameMode() == GameMode.CREATIVE ||
            player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }

        // Skip if gliding with elytra - false positive
        if (player.isGliding()) return;

        Bukkit.getScheduler().runTask(GAC.getInstance(), () -> {
            if (player == null || !player.isOnline()) return;

            // Pattern 2: Micro-jump criticals (very small jumps)
            // Normal jump is 0.42 initial velocity
            // Crit hack often does 0.05-0.1 jumps
            if (airTicks > 0 && airTicks <= 3) {
                double jumpHeight = user.getLastY() - lastY;

                // Suspicious if jumping tiny amount but attacking (trying to crit)
                // Allow some slab walking (0.5), but detecting < 0.3 is usually good
                if (jumpHeight > 0.01 && jumpHeight < 0.35) {
                    
                    // Filter out stairs/slabs
                    if (!isNearStairsOrSlabs(player)) {
                        suspicion += 2;
    
                        if (suspicion >= SUSPICION_THRESHOLD) {
                            fail(String.format("microJump=%.3f", jumpHeight));
                            suspicion = 0;
                            
                            if (getViolationLevel() >= KICK_THRESHOLD) {
                                GAC.incrementKicks();
                                player.kickPlayer("§b§lGAC \n\n§7Unfair Advantage detected.\n§fCheck: §bCriticals");
                            }
                        }
                    }
                }
            } else {
                // Decay
                suspicion = Math.max(0, suspicion - 1);
            }
        });
    }

    private boolean isOnGround(Player player) {
        Location loc = player.getLocation();
        for (double x = -0.3; x <= 0.3; x += 0.3) {
            for (double z = -0.3; z <= 0.3; z += 0.3) {
                if (loc.clone().add(x, -0.1, z).getBlock().getType().isSolid()) {
                    return true;
                }
            }
        }
        return false;
    }
    
    private boolean isNearStairsOrSlabs(Player player) {
        for (double x = -0.5; x <= 0.5; x += 0.5) {
            for (double z = -0.5; z <= 0.5; z += 0.5) {
                for (double y = -0.5; y <= 1.0; y += 0.5) {
                    String name = player.getLocation().clone().add(x, y, z).getBlock().getType().name();
                    if (name.contains("STAIR") || name.contains("SLAB") || name.contains("STEP") || name.contains("SNOW")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
