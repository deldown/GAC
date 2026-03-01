package de.geffeniuse.gac.check.movement;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketEvent;
import de.geffeniuse.gac.GAC;
import de.geffeniuse.gac.check.Check;
import de.geffeniuse.gac.data.GACUser;

/**
 * CrasherD - Protocol Anomalies & Spam
 * Addresses specific crashers from deep research:
 * - Recipe Book / Advancement Spam (Memory leaks)
 * - Invalid Float Coordinates (NaN/Infinity)
 */
public class CrasherD extends Check {

    private long lastSecond = System.currentTimeMillis();
    private int recipePackets = 0;
    private int advancementPackets = 0;

    private static final int MAX_RECIPE_PPS = 10;
    private static final int MAX_ADV_PPS = 5;

    public CrasherD(GACUser user) {
        super(user, "CrasherD", "Protocol anomalies and GUI spam.");
    }

    @Override
    public void onPacket(PacketEvent event) {
        long now = System.currentTimeMillis();
        if (now - lastSecond >= 1000) {
            recipePackets = 0;
            advancementPackets = 0;
            lastSecond = now;
        }

        PacketType type = event.getPacketType();

        // 1. Invalid Float Coordinates (NaN/Infinity)
        if (type == PacketType.Play.Client.POSITION || type == PacketType.Play.Client.POSITION_LOOK ||
            type == PacketType.Play.Client.VEHICLE_MOVE) {
            
            double x = event.getPacket().getDoubles().read(0);
            double y = event.getPacket().getDoubles().read(1);
            double z = event.getPacket().getDoubles().read(2);
            
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                event.setCancelled(true);
                fail("NaN/Infinity coordinates");
                user.getPlayer().kickPlayer("§b§lGAC \n\n§cCrash Attempt Detected.\n§fInvalid Floats");
            }
            
            // Check floats (yaw/pitch)
            if (type == PacketType.Play.Client.POSITION_LOOK) {
                float yaw = event.getPacket().getFloat().read(0);
                float pitch = event.getPacket().getFloat().read(1);
                if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) {
                    event.setCancelled(true);
                    fail("NaN/Infinity rotation");
                }
            }
        }

        // 2. Recipe Book Spam
        if (type == PacketType.Play.Client.RECIPE_SETTINGS || type == PacketType.Play.Client.RECIPE_DISPLAYED) {
            recipePackets++;
            if (recipePackets > MAX_RECIPE_PPS) {
                event.setCancelled(true);
                if (recipePackets == MAX_RECIPE_PPS + 1) fail("recipe book spam");
            }
        }

        // 3. Advancement Tab Spam
        if (type == PacketType.Play.Client.ADVANCEMENTS) {
            advancementPackets++;
            if (advancementPackets > MAX_ADV_PPS) {
                event.setCancelled(true);
                if (advancementPackets == MAX_ADV_PPS + 1) fail("advancement spam");
            }
        }
    }
}
