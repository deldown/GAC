package de.geffeniuse.gac.check.movement;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketEvent;
import de.geffeniuse.gac.GAC;
import de.geffeniuse.gac.check.Check;
import de.geffeniuse.gac.data.GACUser;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public class CrasherC extends Check {

    private long lastSecond = System.currentTimeMillis();
    private int projectiles = 0;
    private int lectern = 0;
    private int maps = 0;

    public CrasherC(GACUser user) {
        super(user, "CrasherC", "Block/Item specific crash protection.");
    }

    @Override
    public void onPacket(PacketEvent event) {
        long now = System.currentTimeMillis();
        if (now - lastSecond >= 1000) {
            projectiles = 0;
            lectern = 0;
            maps = 0;
            lastSecond = now;
        }

        // 1. Projectile Spam (Bow/Trident/Snowball)
        if (event.getPacketType() == PacketType.Play.Client.USE_ITEM) {
            Player p = user.getPlayer();
            ItemStack hand = p.getInventory().getItemInMainHand();
            
            if (hand.getType() == Material.BOW || hand.getType() == Material.CROSSBOW || 
                hand.getType() == Material.TRIDENT || hand.getType() == Material.SNOWBALL || 
                hand.getType() == Material.EGG || hand.getType() == Material.SPLASH_POTION) {
                
                projectiles++;
                if (projectiles > 15) { // 15 throws per second is inhuman
                    event.setCancelled(true);
                    if (projectiles == 16) fail("projectile spam");
                }
            }
        }
        
        // 2. Lectern Crash (Quick Book Change)
        // Usually involves clicking window slots rapidly in lectern GUI
        if (event.getPacketType() == PacketType.Play.Client.WINDOW_CLICK) {
             // If inventory type is Lectern (hard to check packet-only without tracking window ID)
             // We rely on general click speed in CrasherA, but here we can check specific item types if needed.
             // Skipped complex window tracking for stability, CrasherA handles pure click speed.
        }
        
        // 3. Map Data Spam (Creative Set Slot with Maps)
        if (event.getPacketType() == PacketType.Play.Client.SET_CREATIVE_SLOT) {
            ItemStack item = event.getPacket().getItemModifier().read(0);
            if (item != null && item.getType() == Material.FILLED_MAP) {
                maps++;
                if (maps > 5) {
                    event.setCancelled(true);
                    fail("map spam");
                }
            }
        }
    }
}
