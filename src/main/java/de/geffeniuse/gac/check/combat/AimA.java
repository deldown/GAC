package de.geffeniuse.gac.check.combat;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.EnumWrappers;
import de.geffeniuse.gac.check.Check;
import de.geffeniuse.gac.data.GACUser;
import org.bukkit.entity.Player;

public class AimA extends Check {

    private int suspicion = 0;
    private long lastAttackTime = 0;

    public AimA(GACUser user) {
        super(user, "Aim (Consistency)", "Checks for unnatural smooth/consistent rotation.");
    }

    @Override
    public void onPacket(PacketEvent event) {
        // Track attacks
        if (event.getPacketType() == PacketType.Play.Client.USE_ENTITY) {
            try {
                EnumWrappers.EntityUseAction action = event.getPacket().getEnumEntityUseActions().read(0).getAction();
                if (action == EnumWrappers.EntityUseAction.ATTACK) {
                    lastAttackTime = System.currentTimeMillis();
                }
            } catch (Exception ignored) {}
            return;
        }

        // Run on rotation updates
        if (event.getPacketType() != PacketType.Play.Client.LOOK &&
            event.getPacketType() != PacketType.Play.Client.POSITION_LOOK) return;

        Player player = user.getPlayer();
        if (player == null) return;

        // ONLY check when player is in combat (attacked in last 3 seconds)
        long timeSinceAttack = System.currentTimeMillis() - lastAttackTime;
        if (timeSinceAttack > 3000) {
            suspicion = Math.max(0, suspicion - 2); // Decay when not fighting
            return;
        }

        float deltaYaw = user.getDeltaYaw();
        float lastDeltaYaw = user.getLastDeltaYaw();
        
        // Safety check for stationary or near-stationary aim
        if (deltaYaw < 1.0 || lastDeltaYaw < 1.0) {
            suspicion = Math.max(0, suspicion - 1);
            return;
        }

        // We only care if the player is actually rotating significantly
        if (deltaYaw > 1.0 && lastDeltaYaw > 1.0) {

            // Calculate how much the rotation speed changed (Acceleration)
            float accel = Math.abs(deltaYaw - lastDeltaYaw);

            // Humans are shaky. If acceleration is extremely low while moving fast, it's weird.
            // AimAssists often have constant speed (accel ~= 0).

            if (accel < 0.05) {
                suspicion++;
            } else {
                suspicion = Math.max(0, suspicion - 1);
            }

            if (suspicion > 15) { // Increased threshold slightly
                fail("consistency=" + String.format("%.3f", accel));
                suspicion = 10;
            }
        }
    }
}
