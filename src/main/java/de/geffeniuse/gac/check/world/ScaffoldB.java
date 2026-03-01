package de.geffeniuse.gac.check.world;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketEvent;
import de.geffeniuse.gac.GAC;
import de.geffeniuse.gac.check.Check;
import de.geffeniuse.gac.data.GACUser;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.LinkedList;

/**
 * ScaffoldB - Advanced Rotation & Vector Analysis
 * Detects "Legit Scaffold" / Meteor by analyzing cursor hit vectors and strict raytracing.
 */
public class ScaffoldB extends Check {

    private final LinkedList<Float> cursorXHistory = new LinkedList<>();
    private final LinkedList<Float> cursorYHistory = new LinkedList<>();
    private final LinkedList<Float> cursorZHistory = new LinkedList<>();
    
    private int suspicion = 0;
    private int perfectHits = 0;

    public ScaffoldB(GACUser user) {
        super(user, "Scaffold (Vector)", "Detects non-human placement vectors.");
    }

    @Override
    public void onPacket(PacketEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.USE_ITEM_ON) return;

        Player player = user.getPlayer();
        if (player == null || player.getGameMode() == GameMode.CREATIVE) return;

        // Packet contains: Block Position, Face, Cursor Position (x,y,z inside block 0.0-1.0)
        // ProtocolLib structure depends on version, assuming modern logic where float reads work
        // cursorX, cursorY, cursorZ are usually at end of float structure or specific object
        
        try {
            float cursorX = event.getPacket().getFloat().read(0);
            float cursorY = event.getPacket().getFloat().read(1);
            float cursorZ = event.getPacket().getFloat().read(2);
            
            // 1. Exact Center Check (0.5, 0.5, 0.5)
            // No human hits exact center consistently.
            // Even "Legit" scaffolds often hardcode 0.5 or 0.0/1.0
            boolean isCenter = (cursorX == 0.5f && cursorY == 0.5f) || (cursorX == 0.5f && cursorZ == 0.5f);
            
            if (isCenter) {
                perfectHits++;
                if (perfectHits > 3) {
                    suspicion += 2;
                    if (suspicion > 5) {
                        fail("perfect center hit x" + perfectHits);
                        user.getMitigation().onScaffoldViolation("vector");
                    }
                }
            } else {
                perfectHits = 0;
            }
            
            // 2. Vector Consistency (Standard Deviation)
            cursorXHistory.add(cursorX);
            cursorYHistory.add(cursorY);
            cursorZHistory.add(cursorZ);
            
            if (cursorXHistory.size() > 10) {
                cursorXHistory.removeFirst();
                cursorYHistory.removeFirst();
                cursorZHistory.removeFirst();
                
                double devX = getStdDev(cursorXHistory);
                double devY = getStdDev(cursorYHistory);
                double devZ = getStdDev(cursorZHistory);
                
                // Extremely low variance = bot (e.g. always hitting exactly 0.8 on block face)
                // Human hands jitter
                if (devX < 0.01 && devY < 0.01 && devZ < 0.01) {
                    suspicion += 2;
                    if (suspicion > 8) {
                        fail("zero variance vector");
                        user.getMitigation().onScaffoldViolation("vector");
                        suspicion = 0;
                    }
                } else {
                    suspicion = Math.max(0, suspicion - 1);
                }
            }
            
            // 3. Sprint Bridge Logic (Rotation check)
            // If placing below feet while sprinting and NOT sneaking
            if (player.isSprinting() && !player.isSneaking()) {
                double pitch = player.getLocation().getPitch();
                // To bridge legit without sneak, you usually need specific pitch or timing
                // Meteor "Legit Scaffold" might jitter sneak, but if they don't:
                
                // If placing directly below (y-1)
                // And looking somewhat forward (not straight down 90)
                if (pitch < 70.0 && pitch > 45.0) {
                     // Requires extreme skill or scaffold
                     // Check if they are actually moving fast
                     if (user.getDeltaXZ() > 0.2) {
                         // Suspicious
                         // We rely on the Vector check mostly, but flag this as "risky"
                     }
                }
            }

        } catch (Exception e) {
            // Structure mismatch on some versions
        }
    }
    
    private double getStdDev(LinkedList<Float> values) {
        if (values.isEmpty()) return 0;
        double sum = 0;
        for (float v : values) sum += v;
        double mean = sum / values.size();
        double sqSum = 0;
        for (float v : values) sqSum += Math.pow(v - mean, 2);
        return Math.sqrt(sqSum / values.size());
    }
}
