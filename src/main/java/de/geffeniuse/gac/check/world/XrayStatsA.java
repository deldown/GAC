package de.geffeniuse.gac.check.world;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketEvent;
import de.geffeniuse.gac.check.Check;
import de.geffeniuse.gac.data.GACUser;
import org.bukkit.Material;
import org.bukkit.block.Block;

public class XrayStatsA extends Check {

    private int stoneMined = 0;
    private int diamondMined = 0;
    private long lastReset = System.currentTimeMillis();

    public XrayStatsA(GACUser user) {
        super(user, "Xray (Stats)", "Analyzes ore-to-stone ratio.");
    }

    @Override
    public void onPacket(PacketEvent event) {
        // We use Bukkit BlockBreakEvent usually, but let's try packet if possible?
        // PacketPlayInBlockDig is sent when player finishes digging.
        if (event.getPacketType() == PacketType.Play.Client.BLOCK_DIG) {
            com.comphenix.protocol.wrappers.EnumWrappers.PlayerDigType type = event.getPacket().getPlayerDigTypes().read(0);
            
            if (type == com.comphenix.protocol.wrappers.EnumWrappers.PlayerDigType.STOP_DESTROY_BLOCK) {
                // He finished digging. But we don't know the block type easily from packet without world lookup.
                // We need to run this on main thread to get block type safely.
                
                com.comphenix.protocol.wrappers.BlockPosition pos = event.getPacket().getBlockPositionModifier().read(0);
                
                org.bukkit.Bukkit.getScheduler().runTask(de.geffeniuse.gac.GAC.getInstance(), () -> {
                    Block block = user.getPlayer().getWorld().getBlockAt(pos.getX(), pos.getY(), pos.getZ());
                    Material mat = block.getType();
                    
                    if (mat == Material.STONE || mat == Material.DEEPSLATE || mat == Material.NETHERRACK) {
                        stoneMined++;
                    } else if (mat == Material.DIAMOND_ORE || mat == Material.DEEPSLATE_DIAMOND_ORE || mat == Material.ANCIENT_DEBRIS) {
                        diamondMined++;
                        checkRatio();
                    }
                });
            }
        }
    }

    private void checkRatio() {
        // Reset every 10 minutes to avoid long-term punishment
        if (System.currentTimeMillis() - lastReset > 600000) {
            stoneMined = 0;
            diamondMined = 0;
            lastReset = System.currentTimeMillis();
            return;
        }

        // Need at least 4 diamonds to judge
        if (diamondMined > 4) {
            // If they mined very few stones per diamond
            // Normal mining: 100+ stone per diamond (Ratio < 0.01)
            // Xray: 5-10 stone per diamond (Ratio > 0.1)
            
            double ratio = (double) diamondMined / Math.max(1, stoneMined);
            
            if (ratio > 0.1) { // > 10% ores
                 fail("ratio=" + String.format("%.2f", ratio * 100) + "%");
            }
        }
    }
}
