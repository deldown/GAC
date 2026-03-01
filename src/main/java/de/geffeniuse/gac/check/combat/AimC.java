package de.geffeniuse.gac.check.combat;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.EnumWrappers;
import de.geffeniuse.gac.check.Check;
import de.geffeniuse.gac.data.GACUser;
import org.bukkit.entity.Player;

public class AimC extends Check {

    private int linearCount = 0;
    private long lastAttackTime = 0;

    public AimC(GACUser user) {
        super(user, "Aim (Heuristics)", "Detects non-human linear mouse movement.");
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

        if (event.getPacketType() != PacketType.Play.Client.LOOK &&
            event.getPacketType() != PacketType.Play.Client.POSITION_LOOK) return;

        Player player = user.getPlayer();
        if (player == null) return;

        // ONLY check when player is in combat (attacked in last 3 seconds)
        long timeSinceAttack = System.currentTimeMillis() - lastAttackTime;
        if (timeSinceAttack > 3000) {
            linearCount = Math.max(0, linearCount - 2);
            return;
        }

        float deltaYaw = user.getDeltaYaw();
        float deltaPitch = user.getDeltaPitch();

        float lastDeltaYaw = user.getLastDeltaYaw();
        float lastDeltaPitch = user.getLastDeltaPitch();

        // Needs movement to check
        if (deltaYaw < 1.0 || deltaPitch < 1.0) return;

        // Linear Check:
        // If the Ratio of Yaw/Pitch change is EXACTLY the same as the last tick,
        // the player is moving in a perfectly straight line diagonally.
        // Humans curve their mouse.

        float ratio = deltaYaw / deltaPitch;
        float lastRatio = lastDeltaYaw / lastDeltaPitch;

        // Check difference between ratios
        double diff = Math.abs(ratio - lastRatio);

        // If the ratio is practically identical (diff very small) for moving diagonally
        if (diff < 0.001 && deltaYaw > 2.0 && deltaPitch > 2.0) {
            linearCount++;
        } else {
            linearCount = Math.max(0, linearCount - 1);
        }

        if (linearCount > 8) {
            fail("linear movement");
            linearCount = 5;
        }
    }
}