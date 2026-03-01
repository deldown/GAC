package de.geffeniuse.gac.check.movement;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import de.geffeniuse.gac.check.Check;
import de.geffeniuse.gac.data.GACUser;

public class BadPacketsA extends Check {

    private boolean isSprinting = false;
    private boolean isSneaking = false;

    public BadPacketsA(GACUser user) {
        super(user, "BadPackets (State)", "Checks for impossible player states (NoSlow, etc).");
    }

    @Override
    public void onPacket(PacketEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.ENTITY_ACTION) {
            PacketContainer packet = event.getPacket();
            com.comphenix.protocol.wrappers.EnumWrappers.PlayerAction action = packet.getPlayerActions().read(0);

            switch (action) {
                case START_SPRINTING:
                    isSprinting = true;
                    break;
                case STOP_SPRINTING:
                    isSprinting = false;
                    break;
                case START_SNEAKING:
                    isSneaking = true;
                    break;
                case STOP_SNEAKING:
                    isSneaking = false;
                    break;
            }
        }
        
        // Pitch Check
        if (event.getPacketType() == PacketType.Play.Client.LOOK || event.getPacketType() == PacketType.Play.Client.POSITION_LOOK) {
            float pitch = event.getPacket().getFloat().read(1);
            if (Math.abs(pitch) > 90.001) { // 0.001 tolerance
                fail("pitch=" + pitch);
                event.setCancelled(true);
            }
        }
    }
    
    // Can be called from Killaura Checks to verify sprinting state
    public boolean isSprinting() {
        return isSprinting;
    }
}
