package de.geffeniuse.gac.check.movement;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketEvent;
import de.geffeniuse.gac.GAC;
import de.geffeniuse.gac.check.Check;
import de.geffeniuse.gac.data.GACUser;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vehicle;

public class VehicleA extends Check {

    private int invalidMoves = 0;

    public VehicleA(GACUser user) {
        super(user, "Vehicle", "Detects BoatFly and fast vehicle movement.");
    }

    @Override
    public void onPacket(PacketEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.VEHICLE_MOVE) {
            Player player = user.getPlayer();
            if (player == null || !player.isInsideVehicle()) return;

            Entity vehicle = player.getVehicle();
            if (vehicle == null) return;

            double x = event.getPacket().getDoubles().read(0);
            double y = event.getPacket().getDoubles().read(1);
            double z = event.getPacket().getDoubles().read(2);

            // Calculate distance from actual server-side vehicle location
            double distSq = vehicle.getLocation().distanceSquared(new org.bukkit.Location(vehicle.getWorld(), x, y, z));
            
            // Anti-Desync / Teleport Vehicle Exploit
            if (distSq > 100) { // 10 blocks diff is huge
                event.setCancelled(true);
                fail("vehicle desync/teleport");
                vehicle.teleport(player.getLocation()); // Sync back
                return;
            }

            // Speed Check
            double lastX = user.getLastX();
            double lastY = user.getLastY();
            double lastZ = user.getLastZ();
            
            // We use user's last tracking for delta calculation roughly
            // Or better: rely on server velocity, but simple delta check works for BoatFly
            // BoatFly usually moves > 0.6 blocks/tick consistently in air
            
            // Check if vehicle is in air (simple check)
            boolean inAir = !vehicle.getLocation().getBlock().getType().isSolid() && 
                            !vehicle.getLocation().subtract(0, 1, 0).getBlock().getType().isSolid();

            if (inAir) {
                // Moving UP in air without velocity (Gravity check)
                if (y > lastY + 0.1) {
                    invalidMoves++;
                    if (invalidMoves > 5) {
                        event.setCancelled(true);
                        fail("vehicle fly (up)");
                        // Eject player
                        Bukkit.getScheduler().runTask(GAC.getInstance(), () -> {
                            if (player.isInsideVehicle()) vehicle.removePassenger(player);
                            player.teleport(vehicle.getLocation().subtract(0, 1, 0));
                        });
                        invalidMoves = 0;
                    }
                } else {
                    invalidMoves = Math.max(0, invalidMoves - 1);
                }
            }
        }
    }
}
