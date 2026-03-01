package de.geffeniuse.gac.check.movement;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketEvent;
import de.geffeniuse.gac.check.Check;
import de.geffeniuse.gac.data.GACUser;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

public class NoWebA extends Check {

    private int buffer = 0;

    public NoWebA(GACUser user) {
        super(user, "NoWeb", "Detects moving too fast in webs.");
    }

    @Override
    public void onPacket(PacketEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.POSITION &&
            event.getPacketType() != PacketType.Play.Client.POSITION_LOOK) {
            return;
        }

        Player player = user.getPlayer();
        if (player == null || player.getGameMode() == GameMode.CREATIVE || 
            player.getGameMode() == GameMode.SPECTATOR || player.isFlying()) {
            return;
        }

        if (isInWeb(player)) {
            double deltaXZ = user.getDeltaXZ();
            double deltaY = user.getDeltaY();

            // Vanilla max speed in web is extremely slow (~0.05 horizontal, ~0.05 vertical falling)
            // NoWeb hacks usually move at normal walking speed (0.22+)
            
            // Limit: 0.1 (Generous buffer, essentially 2x vanilla web speed)
            if (deltaXZ > 0.1 || deltaY > 0.1 || deltaY < -0.2) { // Falling too fast
                buffer += 2;
                if (buffer > 5) {
                    fail("moving fast in web (speed=" + String.format("%.2f", deltaXZ) + ")");
                    // Setback
                    player.teleport(new Location(player.getWorld(), user.getLastX(), user.getLastY(), user.getLastZ(), user.getLastYaw(), user.getLastPitch()));
                    buffer = 0;
                }
            } else {
                buffer = Math.max(0, buffer - 1);
            }
        } else {
            buffer = 0;
        }
    }

    private boolean isInWeb(Player player) {
        Location loc = player.getLocation();
        Block feet = loc.getBlock();
        Block legs = loc.clone().add(0, 1, 0).getBlock(); // Web is often at leg height
        return feet.getType() == Material.COBWEB || legs.getType() == Material.COBWEB || 
               feet.getType() == Material.SWEET_BERRY_BUSH || legs.getType() == Material.SWEET_BERRY_BUSH;
    }
}
