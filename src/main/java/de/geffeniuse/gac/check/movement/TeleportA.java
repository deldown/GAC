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
 * TeleportA - Anti-ClickTP / Instant Teleport
 * Detects movements that are physically impossible in a single tick, 
 * regardless of speed effects.
 */
public class TeleportA extends Check {

    public TeleportA(GACUser user) {
        super(user, "Teleport", "Detects instant illegal teleports (ClickTP).");
    }

    @Override
    public void onPacket(PacketEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.POSITION &&
            event.getPacketType() != PacketType.Play.Client.POSITION_LOOK) {
            return;
        }

        Player player = user.getPlayer();
        if (player == null) return;

        // Exemptions
        if (player.getGameMode() == GameMode.CREATIVE || 
            player.getGameMode() == GameMode.SPECTATOR ||
            user.isTeleporting() || 
            user.isTakingVelocity() || 
            player.isRiptiding() || 
            player.isInsideVehicle() || // VehicleA handles this
            player.isGliding()) { // Elytra can be fast, handled by Fly/Speed
            return;
        }

        double deltaXZ = user.getDeltaXZ();
        double deltaY = user.getDeltaY();

        // 3D distance squared
        double distSq = deltaXZ * deltaXZ + deltaY * deltaY;

        // Max legitimate movement in one tick:
        // Horizontal: Ice+trapdoor+soul speed < 3 blocks/tick
        // Vertical: Terminal velocity ~3.9 blocks/tick
        // Buffer: 8 blocks is a safe hard limit (distSq > 64)
        if (distSq > 64) {
            double dist = Math.sqrt(distSq);
            fail("clickTP dist=" + String.format("%.1f", dist));

            // Setback to position BEFORE this packet
            double fromX = user.getLastX() - user.getDeltaX();
            double fromY = user.getLastY() - user.getDeltaY();
            double fromZ = user.getLastZ() - user.getDeltaZ();

            // Mark as teleporting so other checks ignore the setback
            user.setLastTeleportTime(System.currentTimeMillis());

            Bukkit.getScheduler().runTask(GAC.getInstance(), () -> {
                if (player.isOnline()) {
                    player.teleport(new org.bukkit.Location(
                        player.getWorld(), fromX, fromY, fromZ,
                        player.getLocation().getYaw(), player.getLocation().getPitch()));
                }
            });

            // Kick at VL 3 (faster than base VL>15)
            if (getViolationLevel() >= 3) {
                Bukkit.getScheduler().runTask(GAC.getInstance(), () -> {
                    if (player.isOnline()) {
                        GAC.incrementKicks();
                        player.kickPlayer("§b§lGAC \n\n§7Unfair Advantage detected.\n§fCheck: §bClickTP");
                    }
                });
            }
        }
    }
}
