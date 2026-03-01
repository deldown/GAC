package de.geffeniuse.gac.check.combat;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import de.geffeniuse.gac.check.Check;
import de.geffeniuse.gac.data.GACUser;
import org.bukkit.GameMode;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

public class KillauraC extends Check {

    // Suspicion: only flag after multiple misses in a short window
    private final java.util.Deque<Long> missTimes = new java.util.ArrayDeque<>();
    private static final int MISS_THRESHOLD = 4;
    private static final long MISS_WINDOW_MS = 3000;

    public KillauraC(GACUser user) {
        super(user, "Killaura (Ray)", "Checks if player is actually looking at the target hitbox.");
    }

    @Override
    public void onPacket(PacketEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.USE_ENTITY) return;
        PacketContainer packet = event.getPacket();

        try {
            int entityId = packet.getIntegers().read(0);

            // Capture packet-time player direction (before the tick runs and position changes)
            double eyeX = user.getLastX();
            double eyeY = user.getLastY() + 1.62;
            double eyeZ = user.getLastZ();
            float yaw   = user.getLastYaw();
            float pitch = user.getLastPitch();

            org.bukkit.Bukkit.getScheduler().runTask(de.geffeniuse.gac.GAC.getInstance(), () -> {
                try {
                    Player player = user.getPlayer();
                    if (player == null || !player.isOnline() || !isEnabled()) return;
                    if (player.getGameMode() == GameMode.CREATIVE ||
                        player.getGameMode() == GameMode.SPECTATOR) return;

                    Entity target = null;
                    for (Entity e : player.getWorld().getEntities()) {
                        if (e.getEntityId() == entityId) { target = e; break; }
                    }
                    if (target == null) return;

                    // Build direction from packet-time yaw/pitch
                    double radYaw   = Math.toRadians(yaw);
                    double radPitch = Math.toRadians(pitch);
                    double dx = -Math.sin(radYaw) * Math.cos(radPitch);
                    double dy = -Math.sin(radPitch);
                    double dz =  Math.cos(radYaw) * Math.cos(radPitch);
                    Vector eye = new Vector(eyeX, eyeY, eyeZ);
                    Vector dir = new Vector(dx, dy, dz);

                    BoundingBox box = target.getBoundingBox();

                    // Expansion: account for ping, mace, and client-server position offset
                    boolean isMace = player.getInventory().getItemInMainHand().getType().name().contains("MACE");
                    int ping = player.getPing();
                    double expansion = isMace ? 1.5 : 0.5; // base 0.5 instead of 0.25
                    expansion += Math.min(ping / 300.0, 1.0); // up to +1.0 for high ping

                    BoundingBox expandedBox = box.clone().expand(expansion);
                    RayTraceResult result = expandedBox.rayTrace(eye, dir, 7.5);

                    if (result == null) {
                        long now = System.currentTimeMillis();
                        missTimes.addLast(now);
                        while (!missTimes.isEmpty() && now - missTimes.peekFirst() > MISS_WINDOW_MS) {
                            missTimes.pollFirst();
                        }
                        if (missTimes.size() >= MISS_THRESHOLD) {
                            fail("direction invalid x" + missTimes.size());
                            missTimes.clear();
                        }
                    } else {
                        // Hit was valid — slowly drain miss counter
                        if (!missTimes.isEmpty()) missTimes.pollFirst();
                    }
                } catch (Exception ignored) {}
            });

        } catch (Exception ignored) {}
    }
}