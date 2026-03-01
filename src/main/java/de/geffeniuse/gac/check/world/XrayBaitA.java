package de.geffeniuse.gac.check.world;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.BlockPosition;
import com.comphenix.protocol.wrappers.WrappedBlockData;
import de.geffeniuse.gac.check.Check;
import de.geffeniuse.gac.data.GACUser;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.util.Vector;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

public class XrayBaitA extends Check {

    private final Set<BlockPosition> fakeOres = new HashSet<>();
    private long lastBaitTime = 0;

    public XrayBaitA(GACUser user) {
        super(user, "Xray (Bait)", "Spawns fake ores to bait Xray users.");
    }

    @Override
    public void onPacket(PacketEvent event) {
        // Trigger bait on digging
        if (event.getPacketType() == PacketType.Play.Client.BLOCK_DIG) {
            com.comphenix.protocol.wrappers.EnumWrappers.PlayerDigType type = event.getPacket().getPlayerDigTypes().read(0);
            
            if (type == com.comphenix.protocol.wrappers.EnumWrappers.PlayerDigType.STOP_DESTROY_BLOCK) {
                handleDig(event.getPacket().getBlockPositionModifier().read(0));
            }
        }
    }

    private void handleDig(BlockPosition pos) {
        // 1. Check if he mined a fake ore
        if (fakeOres.contains(pos)) {
            // He mined the bait! BUSTED.
            // Double check: was it actually stone on server?
            org.bukkit.Bukkit.getScheduler().runTask(de.geffeniuse.gac.GAC.getInstance(), () -> {
                Block realBlock = user.getPlayer().getWorld().getBlockAt(pos.getX(), pos.getY(), pos.getZ());
                if (realBlock.getType() != Material.DIAMOND_ORE && realBlock.getType() != Material.DEEPSLATE_DIAMOND_ORE) {
                    fail("mined fake ore");
                    fakeOres.remove(pos);
                }
            });
            return;
        }

        // 2. Chance to spawn bait (every ~5 seconds while mining)
        long now = System.currentTimeMillis();
        if (now - lastBaitTime > 5000) {
            if (ThreadLocalRandom.current().nextInt(100) < 20) { // 20% chance on dig
                spawnBait(pos);
                lastBaitTime = now;
            }
        }
    }

    private void spawnBait(BlockPosition origin) {
        // Find a spot in the wall 3-5 blocks away
        org.bukkit.Bukkit.getScheduler().runTask(de.geffeniuse.gac.GAC.getInstance(), () -> {
            org.bukkit.World world = user.getPlayer().getWorld();
            
            // Random direction
            int dx = ThreadLocalRandom.current().nextInt(3, 6) * (ThreadLocalRandom.current().nextBoolean() ? 1 : -1);
            int dy = ThreadLocalRandom.current().nextInt(-2, 3);
            int dz = ThreadLocalRandom.current().nextInt(3, 6) * (ThreadLocalRandom.current().nextBoolean() ? 1 : -1);
            
            Block target = world.getBlockAt(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
            
            // Only spawn inside Stone/Deepslate (hidden)
            if (target.getType() == Material.STONE || target.getType() == Material.DEEPSLATE || target.getType() == Material.ANDESITE) {
                
                // Send Fake Packet
                PacketContainer fakeBlock = ProtocolLibrary.getProtocolManager().createPacket(PacketType.Play.Server.BLOCK_CHANGE);
                fakeBlock.getBlockPositionModifier().write(0, new BlockPosition(target.getX(), target.getY(), target.getZ()));
                fakeBlock.getBlockData().write(0, WrappedBlockData.createData(Material.DIAMOND_ORE));
                
                try {
                    ProtocolLibrary.getProtocolManager().sendServerPacket(user.getPlayer(), fakeBlock);
                    fakeOres.add(new BlockPosition(target.getX(), target.getY(), target.getZ()));
                    
                    // Auto-remove bait after 20 seconds to save memory/confusion
                    org.bukkit.Bukkit.getScheduler().runTaskLater(de.geffeniuse.gac.GAC.getInstance(), () -> {
                        fakeOres.remove(new BlockPosition(target.getX(), target.getY(), target.getZ()));
                    }, 400L);
                    
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }
}
