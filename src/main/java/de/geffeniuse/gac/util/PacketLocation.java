package de.geffeniuse.gac.util;

import org.bukkit.Location;
import org.bukkit.entity.Player;

public class PacketLocation {
    public double x, y, z;
    public long time;

    public PacketLocation(double x, double y, double z, long time) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.time = time;
    }
    
    public static boolean isNearIce(Player player) {
        Location loc = player.getLocation();
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 0; y++) {
                for (int z = -1; z <= 1; z++) {
                    String name = loc.clone().add(x, y, z).getBlock().getType().name();
                    if (name.contains("ICE")) return true;
                }
            }
        }
        return false;
    }
}