package de.geffeniuse.gac.check.movement;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketEvent;
import de.geffeniuse.gac.GAC;
import de.geffeniuse.gac.check.Check;
import de.geffeniuse.gac.data.GACUser;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerRiptideEvent;
import org.bukkit.inventory.ItemStack;

public class ElytraA extends Check implements Listener {

    private long lastBoost = 0;
    private int hoverTicks = 0;
    private int invalidSpeedTicks = 0;
    
    // Bounce/Control Hack Detection
    private double lastDeltaY = 0;
    private double previousDeltaXZ = 0; // Track speed changes
    private int bounceTicks = 0;

    // Strict limits for Elytra
    // Max horizontal speed with firework is roughly 1.5 - 2.0 blocks/tick usually, 
    // but 40 degree dives can reach ~3.0.
    // Anything above 4.0 is definitely suspicious or "EntitySpeed".
    private static final double MAX_SPEED = 3.5; 
    
    // If not boosting, maintaining height is impossible.
    // Allow slight buffer for lag/updrafts (if any exist in future, currently none in vanilla)
    private static final int HOVER_LIMIT = 60; // 3.0 seconds

    public ElytraA(GACUser user) {
        super(user, "Elytra", "Detects ElytraFly and Infinite Boost.");
        Bukkit.getPluginManager().registerEvents(this, GAC.getInstance());
    }

    @EventHandler
    public void onBoost(PlayerInteractEvent event) {
        if (!event.getPlayer().getUniqueId().equals(user.getUuid())) return;
        
        if (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            ItemStack item = event.getItem();
            if (item != null && item.getType() == Material.FIREWORK_ROCKET) {
                if (event.getPlayer().isGliding()) {
                    lastBoost = System.currentTimeMillis();
                }
            }
        }
    }
    
    @EventHandler
    public void onRiptide(PlayerRiptideEvent event) {
        if (!event.getPlayer().getUniqueId().equals(user.getUuid())) return;
        lastBoost = System.currentTimeMillis();
    }

    @Override
    public void onPacket(PacketEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.POSITION &&
            event.getPacketType() != PacketType.Play.Client.POSITION_LOOK) {
            return;
        }

        Player player = user.getPlayer();
        if (player == null || !player.isGliding()) {
            hoverTicks = 0;
            bounceTicks = 0;
            return;
        }
        
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;
        if (user.isTakingVelocity()) { // Server velocity (explosions etc) can cause weird movement
            lastBoost = System.currentTimeMillis(); // Treat velocity as a boost
            return;
        }

        double deltaY = user.getDeltaY();
        double deltaXZ = user.getDeltaXZ();
        
        boolean isBoosting = (System.currentTimeMillis() - lastBoost < 2500); // 2.5s grace period

        // 1. Speed Check
        double limit = isBoosting ? MAX_SPEED * 1.5 : MAX_SPEED;
        
        if (deltaXZ > limit) {
            invalidSpeedTicks++;
            if (invalidSpeedTicks > 5) {
                // Setback
                event.setCancelled(true);
                player.teleport(new org.bukkit.Location(player.getWorld(), user.getLastX(), user.getLastY(), user.getLastZ(), user.getLastYaw(), user.getLastPitch()));
                
                fail("elytra speed=" + String.format("%.2f", deltaXZ));
                invalidSpeedTicks = 0;
            }
        } else {
            invalidSpeedTicks = Math.max(0, invalidSpeedTicks - 1);
        }

        // 2. Infinite Fly / Hover Check
        if (!isBoosting) {
            // Rising or hovering check
            // MOMENTUM LOGIC: You can go UP if you lose SPEED (Kinetic -> Potential energy)
            boolean isSwoopingUp = deltaY > 0 && deltaXZ < (previousDeltaXZ - 0.01); 

            if (deltaY >= -0.05 && !isSwoopingUp) { // Not falling significantly AND not validly swooping
                hoverTicks++;
                
                if (hoverTicks > HOVER_LIMIT) {
                    if (deltaXZ > 0.3) {
                        fail("elytra hover/fly (no rockets)");
                        resetVL(); // Mitigation only — don't accumulate VL toward auto-kick
                        Bukkit.getScheduler().runTask(GAC.getInstance(), () -> player.setGliding(false));
                        hoverTicks = 0;
                    }
                }
            } else {
                hoverTicks = Math.max(0, hoverTicks - 2);
            }
            
            // 3. Bounce / Oscillation Check (PacketFly / Control)
            // Changing direction from UP to DOWN rapidly while moving forward
            // Normal glide curve is smooth. Hack is zigzag.
            boolean directionChanged = (lastDeltaY > 0 && deltaY < 0) || (lastDeltaY < 0 && deltaY > 0);
            
            if (directionChanged && Math.abs(deltaY) > 0.05 && deltaXZ > 0.3) {
                bounceTicks += 2;
                if (bounceTicks > 8) { // 4 direction changes in short time
                    fail("elytra oscillation/bounce");
                    bounceTicks = 0;
                }
            } else {
                bounceTicks = Math.max(0, bounceTicks - 1);
            }
            
            lastDeltaY = deltaY;
            previousDeltaXZ = deltaXZ;
        } else {
            hoverTicks = 0;
            bounceTicks = 0;
            previousDeltaXZ = deltaXZ;
        }
    }
}
