package de.geffeniuse.gac.check.movement;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketEvent;
import de.geffeniuse.gac.GAC;
import de.geffeniuse.gac.check.Check;
import de.geffeniuse.gac.data.GACUser;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * CrasherA - Anti-Crasher & Lag Machine
 * Detects packet flooding, huge payloads, and invalid packets.
 */
public class CrasherA extends Check {

    private long lastSecond = System.currentTimeMillis();
    private int packetsPerSecond = 0;
    
    // Specific Rate Limiters
    private int recipePackets = 0;
    private int tabPackets = 0;
    private int signPackets = 0;
    
    // Limits
    private static final int MAX_PPS = 400; // Normal is 20 (ticks) * ~5-10 packets = ~200 max
    private static final int MAX_PAYLOAD_SIZE = 15000; // Bytes (Book limit is lower)
    private static final int MAX_RECIPE_PPS = 20; // Humans can't do more
    private static final int MAX_TAB_PPS = 50; // Spamming tab lag
    private static final int MAX_SIGN_PPS = 10; // Sign update spam

    public CrasherA(GACUser user) {
        super(user, "Crasher", "Detects packet floods and crashers.");
    }

    @Override
    public void onPacket(PacketEvent event) {
        // PPS Counter
        long now = System.currentTimeMillis();
        if (now - lastSecond >= 1000) {
            packetsPerSecond = 0;
            recipePackets = 0;
            tabPackets = 0;
            signPackets = 0;
            lastSecond = now;
        }

        packetsPerSecond++;

        // 1. Packet Flood Check
        if (packetsPerSecond > MAX_PPS) {
            // Hard limit - cancel everything to save server
            event.setCancelled(true);
            
            // Only alert once per second
            if (packetsPerSecond == MAX_PPS + 1) {
                fail("packet flood (pps=" + packetsPerSecond + ")");
                
                // Kick immediately if extreme
                if (packetsPerSecond > 800) {
                    Bukkit.getScheduler().runTask(GAC.getInstance(), () -> {
                        user.getPlayer().kickPlayer("§b§lGAC \n\n§cCrash Attempt Detected.\n§fPacket Flood");
                    });
                }
            }
            return;
        }
        
        // RECIPE BOOK SPAM
        if (event.getPacketType() == PacketType.Play.Client.RECIPE_SETTINGS || 
            event.getPacketType() == PacketType.Play.Client.RECIPE_DISPLAYED) {
            recipePackets++;
            if (recipePackets > MAX_RECIPE_PPS) {
                event.setCancelled(true);
                if (recipePackets == MAX_RECIPE_PPS + 1) fail("recipe spam");
            }
        }
        
        // TAB COMPLETE SPAM
        if (event.getPacketType() == PacketType.Play.Client.TAB_COMPLETE) {
            tabPackets++;
            if (tabPackets > MAX_TAB_PPS) {
                event.setCancelled(true);
                if (tabPackets == MAX_TAB_PPS + 1) fail("tab complete spam");
            }
        }
        
        // SIGN UPDATE EXPLOITS
        if (event.getPacketType() == PacketType.Play.Client.UPDATE_SIGN) {
            signPackets++;
            if (signPackets > MAX_SIGN_PPS) {
                event.setCancelled(true);
                fail("sign spam");
            }
            
            // Check Text Length (Sign Lag Exploit)
            String[] lines = event.getPacket().getStringArrays().read(0);
            for (String line : lines) {
                if (line.length() > 400) { // JSON text can be long, but >400 is suspicious
                    event.setCancelled(true);
                    fail("huge sign text (" + line.length() + ")");
                    user.getPlayer().kickPlayer("§b§lGAC \n\n§cCrash Attempt Detected.\n§fSign Exploit");
                    return;
                }
            }
        }

        // 2. Custom Payload Check (Book Exploits / NBT Crashers)
        if (event.getPacketType() == PacketType.Play.Client.CUSTOM_PAYLOAD) {
            try {
                // Safely check payload size via reflection to avoid direct Netty dependency
                Object payload = event.getPacket().getModifier().read(1);
                if (payload != null) {
                    java.lang.reflect.Method method = payload.getClass().getMethod("readableBytes");
                    int size = (int) method.invoke(payload);
                    
                    if (size > MAX_PAYLOAD_SIZE) {
                        event.setCancelled(true);
                        fail("huge payload size=" + size);
                        user.getPlayer().kickPlayer("§b§lGAC \n\n§cCrash Attempt Detected.\n§fInvalid Payload");
                    }
                }
            } catch (Exception e) {
                // Ignore reflection errors
            }
        }
        
        // 3. Window Click Spam (Common Crasher)
        if (event.getPacketType() == PacketType.Play.Client.WINDOW_CLICK) {
            if (packetsPerSecond > 60) { // Humans can't click inventory 60 times/sec
                 fail("window click spam");
                 event.setCancelled(true);
            }
            
            // Check invalid slots
            int slot = event.getPacket().getIntegers().read(0);
            if (slot < -1 || slot > 120) { // -1 is outside window, >120 is usually invalid
                 event.setCancelled(true);
            }
        }
        
        // 4. Invalid Float Values (Infinity/NaN)
        if (event.getPacketType() == PacketType.Play.Client.POSITION || 
            event.getPacketType() == PacketType.Play.Client.POSITION_LOOK) {
            
            double x = event.getPacket().getDoubles().read(0);
            double y = event.getPacket().getDoubles().read(1);
            double z = event.getPacket().getDoubles().read(2);
            
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || 
                Math.abs(x) > 30000000 || Math.abs(z) > 30000000) { // World border check
                event.setCancelled(true);
                fail("invalid position (NaN/Inf/OOB)");
                user.getPlayer().kickPlayer("§b§lGAC \n\n§cInvalid Packet Detected.");
            }
        }
    }
}
