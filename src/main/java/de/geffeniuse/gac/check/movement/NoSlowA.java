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
import org.bukkit.inventory.ItemStack;

/**
 * NoSlowA - Detects NoSlowdown cheats.
 * Checks if player is moving at full speed while using items (eating, bowing, blocking).
 */
public class NoSlowA extends Check {

    private boolean isUsingItem = false;
    private int invalidTicks = 0;

    // Movement Multipliers when using items
    private static final double BLOCK_MULTIPLIER = 0.2; // 20% speed (sword block in old versions, shield in new)
    private static final double USE_MULTIPLIER = 0.2;   // Eating/Drinking/Bowing

    public NoSlowA(GACUser user) {
        super(user, "NoSlow", "Detects moving fast while using items.");
    }

    @Override
    public void onPacket(PacketEvent event) {
        // Check Movement
        if (event.getPacketType() == PacketType.Play.Client.POSITION ||
            event.getPacketType() == PacketType.Play.Client.POSITION_LOOK) {

            Player player = user.getPlayer();
            if (player == null) return;

            // Use Bukkit's isHandRaised() - this checks if player is ACTIVELY using an item
            // (eating, drinking, blocking, drawing bow, etc.)
            // This is much more reliable than packet tracking
            boolean actuallyUsingItem = false;
            try {
                actuallyUsingItem = player.isHandRaised();
            } catch (NoSuchMethodError e) {
                // Fallback for older versions - less reliable
                actuallyUsingItem = isUsingItem;
            }

            // Skip conditions
            if (!actuallyUsingItem || player.getGameMode() == GameMode.CREATIVE ||
                player.getGameMode() == GameMode.SPECTATOR ||
                player.isFlying() || player.getAllowFlight() ||
                player.isGliding() || player.isRiptiding() ||
                player.isSwimming() || player.isInsideVehicle()) {
                invalidTicks = 0;
                return;
            }

            ItemStack hand = player.getInventory().getItemInMainHand();
            ItemStack offhand = player.getInventory().getItemInOffHand();

            // Only items that slow you down
            boolean slowingItem = isSlowing(hand.getType()) || isSlowing(offhand.getType());
            if (!slowingItem) {
                invalidTicks = 0;
                return;
            }

            double deltaXZ = user.getDeltaXZ();
            
            // Max allowed speed while using item is roughly walkSpeed * 0.2
            // Base walk is ~0.22, so max is ~0.044 - 0.05
            // But jumping/sprinting complicates it.
            // NoSlow hackers usually move at full sprint speed (0.28+) or full walk speed (0.22)
            
            double limit = 0.18; // Stricter limit, applied to air too
            
            // Check deltaXZ against limit
            if (deltaXZ > limit) {
                // Verify potion effects again just in case
                if (player.hasPotionEffect(org.bukkit.potion.PotionEffectType.SPEED)) {
                    limit *= 1.2; // Allow 20% more for speed
                }
                
                if (deltaXZ > limit) {
                    invalidTicks++;
                    if (invalidTicks > 5) {
                        fail("noslow (speed=" + String.format("%.2f", deltaXZ) + ")");
                        // Setback
                        player.teleport(new org.bukkit.Location(player.getWorld(), user.getLastX(), user.getLastY(), user.getLastZ(), user.getLastYaw(), user.getLastPitch()));
                        invalidTicks = 0;
                    }
                }
            } else {
                invalidTicks = Math.max(0, invalidTicks - 1);
            }
        }
    }
    
    private boolean isSlowing(Material mat) {
        return mat.isEdible() || mat == Material.BOW || mat == Material.CROSSBOW || 
               mat == Material.TRIDENT || mat == Material.SHIELD || 
               mat == Material.POTION || mat == Material.MILK_BUCKET;
    }
}
