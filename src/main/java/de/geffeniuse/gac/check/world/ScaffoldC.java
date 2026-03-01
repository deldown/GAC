package de.geffeniuse.gac.check.world;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketEvent;
import de.geffeniuse.gac.check.Check;
import de.geffeniuse.gac.data.GACUser;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

import java.util.LinkedList;

/**
 * ScaffoldC - Rotation Entropy Analysis
 * Detects "snappy" or "too smooth" rotations common in scaffold hacks.
 */
public class ScaffoldC extends Check {

    private final LinkedList<Float> deltaYawHistory = new LinkedList<>();
    private final LinkedList<Float> deltaPitchHistory = new LinkedList<>();
    private int suspicion = 0;

    public ScaffoldC(GACUser user) {
        super(user, "Scaffold (Rotation)", "Detects robotic rotation patterns.");
    }

    @Override
    public void onPacket(PacketEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.LOOK && 
            event.getPacketType() != PacketType.Play.Client.POSITION_LOOK) return;

        Player player = user.getPlayer();
        if (player == null || player.getGameMode() == GameMode.CREATIVE) return;

        float deltaYaw = user.getDeltaYaw();
        float deltaPitch = user.getDeltaPitch();
        
        // Ignore tiny movements (jitter) or huge snaps (teleport)
        if (deltaYaw < 0.1 || deltaYaw > 20.0) return;

        deltaYawHistory.add(deltaYaw);
        deltaPitchHistory.add(deltaPitch);
        
        if (deltaYawHistory.size() > 20) {
            deltaYawHistory.removeFirst();
            deltaPitchHistory.removeFirst();
            
            // Check for GCD (Greatest Common Divisor) - "Cinematic" / GCD bypass hacks often fail this
            // Or simple variance check
            
            double varianceYaw = getVariance(deltaYawHistory);
            
            // Extremely low variance in DELTA yaw means they are turning at a CONSTANT speed
            // Humans accelerate and decelerate. Bots turn linearly.
            if (varianceYaw < 0.05) { // Almost constant turn speed
                suspicion += 2;
                if (suspicion > 10) {
                    fail("robotic rotation (linear)");
                    user.getMitigation().onScaffoldViolation("rotation");
                    suspicion = 0;
                }
            } else {
                suspicion = Math.max(0, suspicion - 1);
            }
            
            // Snap check: If pitch is CONSTANT while Yaw changes wildly (Scaffold keeping pitch down)
            double variancePitch = getVariance(deltaPitchHistory);
            if (variancePitch < 0.001 && getVariance(deltaYawHistory) > 1.0) {
                // Pitch locked?
                if (player.getLocation().getPitch() > 70.0) { // Looking down
                     // Suspicious pitch lock
                     suspicion++;
                     if (suspicion > 15) {
                         fail("pitch lock");
                         user.getMitigation().onScaffoldViolation("pitchlock");
                         suspicion = 0;
                     }
                }
            }
        }
    }
    
    private double getVariance(LinkedList<Float> values) {
        if (values.isEmpty()) return 0;
        double mean = 0;
        for (float v : values) mean += v;
        mean /= values.size();
        
        double var = 0;
        for (float v : values) var += Math.pow(v - mean, 2);
        return var / values.size();
    }
}
