package de.geffeniuse.gac.check.combat;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.EnumWrappers;
import de.geffeniuse.gac.check.Check;
import de.geffeniuse.gac.data.GACUser;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

public class KillauraA extends Check {

    // Maximum allowed angle (FOV) in degrees.
    private static final double MAX_ANGLE = 90.0; // Increased from 60 - more lenient

    // Heuristics
    private int suspicionCount = 0;
    private long lastSuspiciousTime = 0;
    private static final int THRESHOLD = 5; // Need 5 suspicious hits to flag
    private static final long DECAY_MS = 5000; // 5 second memory

    public KillauraA(GACUser user) {
        super(user, "Killaura (A)", "Checks if the player is facing the target.");
    }

    @Override
    public void onPacket(PacketEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.USE_ENTITY) return;

        PacketContainer packet = event.getPacket();
        try {
            if (packet.getEntityUseActions().size() == 0) return;
            EnumWrappers.EntityUseAction action = packet.getEntityUseActions().read(0);
            if (action != EnumWrappers.EntityUseAction.ATTACK) return;

            Entity target = packet.getEntityModifier(event.getPlayer().getWorld()).read(0);
            if (target == null) return;

            Player player = user.getPlayer();

            Vector playerLoc = player.getEyeLocation().toVector();
            Vector targetLoc = target.getLocation().toVector().add(new Vector(0, target.getHeight() / 2.0, 0));

            Vector directionToTarget = targetLoc.clone().subtract(playerLoc).normalize();
            Vector playerDirection = player.getEyeLocation().getDirection().normalize();

            float angle = (float) Math.toDegrees(playerDirection.angle(directionToTarget));

            if (angle > MAX_ANGLE) {
                long now = System.currentTimeMillis();

                // Decay suspicion
                if (now - lastSuspiciousTime > DECAY_MS) {
                    suspicionCount = 0;
                }

                suspicionCount++;
                lastSuspiciousTime = now;

                // Only flag after multiple suspicious hits
                if (suspicionCount >= THRESHOLD) {
                    fail("angle=" + String.format("%.1f", angle) + " x" + suspicionCount);
                    suspicionCount = 0;
                }
            } else {
                // Good hit - decay suspicion
                suspicionCount = Math.max(0, suspicionCount - 1);
            }
        } catch (Exception e) {
            // Ignore malformed
        }
    }
}
