package de.geffeniuse.gac.check.movement;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketEvent;
import de.geffeniuse.gac.GAC;
import de.geffeniuse.gac.check.Check;
import de.geffeniuse.gac.data.GACUser;
import org.bukkit.Bukkit;

public class CrasherB extends Check {

    private long lastReset = System.currentTimeMillis();
    
    // Counters
    private int swings = 0;
    private int swaps = 0;
    private int slots = 0;
    private int actions = 0;

    // Limits (Per Second)
    private static final int MAX_SWINGS = 80;    // Killaura/Nuker often swings fast
    private static final int MAX_SWAPS = 40;     // Offhand swap spam
    private static final int MAX_SLOTS = 50;     // Hotbar scroll spam
    private static final int MAX_ACTIONS = 40;   // Sneak/Sprint spam (Squat Crasher)

    public CrasherB(GACUser user) {
        super(user, "CrasherB", "Detects functional packet spam.");
    }

    @Override
    public void onPacket(PacketEvent event) {
        long now = System.currentTimeMillis();
        if (now - lastReset > 1000) {
            swings = 0;
            swaps = 0;
            slots = 0;
            actions = 0;
            lastReset = now;
        }

        PacketType type = event.getPacketType();

        // 1. Arm Animation (Swing)
        if (type == PacketType.Play.Client.ARM_ANIMATION) {
            swings++;
            if (swings > MAX_SWINGS) {
                event.setCancelled(true);
                if (swings == MAX_SWINGS + 1) fail("swing spam");
            }
        }

        // 2. Held Item Change (Hotbar)
        else if (type == PacketType.Play.Client.HELD_ITEM_SLOT) {
            slots++;
            if (slots > MAX_SLOTS) {
                event.setCancelled(true);
                if (slots == MAX_SLOTS + 1) fail("slot spam");
            }
        }

        // 3. Entity Action (Sneak/Sprint/Elytra)
        else if (type == PacketType.Play.Client.ENTITY_ACTION) {
            actions++;
            if (actions > MAX_ACTIONS) {
                event.setCancelled(true);
                if (actions == MAX_ACTIONS + 1) fail("action spam");
            }
        }

        // 4. Swap Hands (F Key Spam)
        // Usually handled via BlockDig (Swap Item) or UseItem depending on version, 
        // but ProtocolLib maps it often.
        // Actually, pure "Swap Hand" isn't its own packet in 1.8, but in modern it's separate.
        // We'll catch block_dig swap action if applicable, or look for specific calls.
        
        // 5. Jigsaw Generate (Crash Exploit)
        else if (type == PacketType.Play.Client.JIGSAW_GENERATE) {
            // Only OPs / Creative should ever touch this
            if (!user.getPlayer().isOp() && user.getPlayer().getGameMode() != org.bukkit.GameMode.CREATIVE) {
                event.setCancelled(true);
                fail("illegal jigsaw packet");
                user.getPlayer().kickPlayer("§b§lGAC \n\n§cIllegal Packet Detected.");
            }
        }
    }
}
