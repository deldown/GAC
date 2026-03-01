package de.geffeniuse.gac.check.combat;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.EnumWrappers;
import de.geffeniuse.gac.GAC;
import de.geffeniuse.gac.check.Check;
import de.geffeniuse.gac.data.GACUser;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * KillauraD - Wall/Block Hit Detection
 * Detects when players attack entities through solid blocks.
 */
public class KillauraD extends Check {

    // Heuristics
    private int suspicionCount = 0;
    private long lastSuspiciousTime = 0;
    private static final int THRESHOLD = 4; // Need 4 wall hits to flag
    private static final long DECAY_MS = 8000; // 8 second memory

    public KillauraD(GACUser user) {
        super(user, "Killaura (Wall)", "Detects hits through blocks.");
    }

    @Override
    public void onPacket(PacketEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.USE_ENTITY) return;

        // Only check attacks
        EnumWrappers.EntityUseAction action;
        try {
            action = event.getPacket().getEnumEntityUseActions().read(0).getAction();
        } catch (Exception e) {
            return;
        }

        if (action != EnumWrappers.EntityUseAction.ATTACK) return;

        Player player = user.getPlayer();
        if (player == null || player.getGameMode() == org.bukkit.GameMode.CREATIVE) return;

        // Get target entity
        int entityId = event.getPacket().getIntegers().read(0);

        // Run on main thread for world access
        Bukkit.getScheduler().runTask(GAC.getInstance(), () -> {
            Entity target = null;
            for (Entity e : player.getWorld().getNearbyEntities(player.getLocation(), 10, 10, 10)) {
                if (e.getEntityId() == entityId) {
                    target = e;
                    break;
                }
            }

            if (target == null) return;

            // Raytrace from player eyes to target center
            Location eyeLocation = player.getEyeLocation();
            Location targetLocation = target.getLocation().add(0, target.getHeight() / 2, 0);

            Vector direction = targetLocation.toVector().subtract(eyeLocation.toVector());
            double distance = direction.length();

            if (distance < 0.5 || distance > 6.0) return; // Too close or too far

            direction.normalize();

            // Check for blocks between player and target
            RayTraceResult blockHit = player.getWorld().rayTraceBlocks(
                eyeLocation,
                direction,
                distance - 0.1, // Stop just before target
                FluidCollisionMode.NEVER,
                true // Ignore passable blocks
            );

            // If we hit a block before reaching the target, it's a wall hit
            if (blockHit != null && blockHit.getHitBlock() != null) {
                org.bukkit.Material mat = blockHit.getHitBlock().getType();

                // Must be solid (not passable like grass/flowers)
                if (mat.isSolid()) {
                    long now = System.currentTimeMillis();

                    // Decay suspicion
                    if (now - lastSuspiciousTime > DECAY_MS) {
                        suspicionCount = 0;
                    }

                    suspicionCount++;
                    lastSuspiciousTime = now;

                    // Only flag after multiple wall hits
                    if (suspicionCount >= THRESHOLD) {
                        double blockDist = eyeLocation.distance(blockHit.getHitPosition().toLocation(player.getWorld()));
                        fail(String.format("block=%s dist=%.1f x%d", mat.name(), blockDist, suspicionCount));
                        suspicionCount = 0;
                    }
                }
            }
        });
    }
}
