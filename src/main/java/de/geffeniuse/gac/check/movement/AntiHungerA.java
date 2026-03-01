package de.geffeniuse.gac.check.movement;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketEvent;
import de.geffeniuse.gac.GAC;
import de.geffeniuse.gac.check.Check;
import de.geffeniuse.gac.data.GACUser;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

/**
 * AntiHungerA - Detects hunger reduction exploits.
 * Checks if player is sprinting/jumping without sending appropriate packets.
 */
public class AntiHungerA extends Check {

    private boolean isSprinting = false;
    private double unsprintedDistance = 0.0;

    public AntiHungerA(GACUser user) {
        super(user, "AntiHunger", "Detects hunger avoidance (AntiHunger).");
    }

    @Override
    public void onPacket(PacketEvent event) {
        // Track Sprint Status
        if (event.getPacketType() == PacketType.Play.Client.ENTITY_ACTION) {
            int actionId = event.getPacket().getIntegers().read(1); 
            // Action IDs: 3=Start Sprinting, 4=Stop Sprinting
            if (actionId == 3) {
                isSprinting = true;
                unsprintedDistance = 0; // Reset on valid sprint
            }
            if (actionId == 4) isSprinting = false;
        }

        // Analyze Movement
        if (event.getPacketType() == PacketType.Play.Client.POSITION || 
            event.getPacketType() == PacketType.Play.Client.POSITION_LOOK) {
            
            Player player = user.getPlayer();
            if (player == null) return;
            if (player.getGameMode() == GameMode.CREATIVE || 
                player.getGameMode() == GameMode.SPECTATOR ||
                player.isFlying() || player.getAllowFlight() ||
                player.isInsideVehicle() || player.isGliding() ||
                user.isTakingVelocity()) {
                unsprintedDistance = 0;
                return;
            }

            double deltaXZ = user.getDeltaXZ();
            
            // If moving fast but client claims NOT sprinting
            // 0.28 is safe threshold (higher to avoid false flags)
            if (deltaXZ > 0.28 && !isSprinting) {
                // Check exemptions
                if (player.hasPotionEffect(org.bukkit.potion.PotionEffectType.SPEED)) return;
                if (de.geffeniuse.gac.util.PacketLocation.isNearIce(player)) return;
                if (player.isSwimming() || player.isRiptiding()) return;

                // Accumulate distance
                unsprintedDistance += deltaXZ;

                // Need 100 blocks of "illegal" sprinting to flag (prevents false positives)
                if (unsprintedDistance > 100.0) {
                    fail("AntiHunger");
                    // Force sprint status to true on server to consume hunger
                    player.setSprinting(true);
                    unsprintedDistance = 0;
                }
            } else {
                // Valid movement decays the counter faster
                unsprintedDistance = Math.max(0, unsprintedDistance - 1.0);
            }
        }
    }
}
