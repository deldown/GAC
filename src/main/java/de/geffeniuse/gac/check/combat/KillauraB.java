package de.geffeniuse.gac.check.combat;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketEvent;
import de.geffeniuse.gac.check.Check;
import de.geffeniuse.gac.data.GACUser;

import java.util.ArrayList;
import java.util.List;

public class KillauraB extends Check {

    private final List<Long> clicks = new ArrayList<>();
    private final int MAX_CPS = 18; // Maximum consistent CPS allowed

    public KillauraB(GACUser user) {
        super(user, "Killaura (B)", "Checks for high click speed (CPS).");
    }

    @Override
    public void onPacket(PacketEvent event) {
        // We count swings (Arm Animation) as clicks
        if (event.getPacketType() != PacketType.Play.Client.ARM_ANIMATION) return;

        // Ignore if player is digging/interacting with blocks
        // Simple check: Raytrace for block
        org.bukkit.entity.Player player = user.getPlayer();
        org.bukkit.block.Block targetBlock = player.getTargetBlockExact(5);
        
        if (targetBlock != null && !targetBlock.getType().isAir() && !targetBlock.getType().name().contains("WATER") && !targetBlock.getType().name().contains("LAVA")) {
            // Player is looking at a block, likely mining or placing. 
            // Increase threshold significantly or return
            return;
        }

        long now = System.currentTimeMillis();
        clicks.add(now);

        // Remove clicks older than 1 second
        clicks.removeIf(time -> (now - time) > 1000);

        int cps = clicks.size();

        if (cps > MAX_CPS) {
            fail("cps=" + cps);
        }
    }
}
