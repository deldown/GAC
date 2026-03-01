package de.geffeniuse.gac.check.combat;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.*;
import de.geffeniuse.gac.GAC;
import de.geffeniuse.gac.check.Check;
import de.geffeniuse.gac.data.GACUser;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * KillauraTrap - Spawns invisible bots behind players to catch Killaura
 *
 * For 1.21+ compatibility:
 * - Uses SPAWN_ENTITY for armor stands
 * - Uses PLAYER_INFO_UPDATE + SPAWN_ENTITY for fake players (1.20.2+ bundle)
 * - Movement via despawn/respawn (most reliable across versions)
 */
public class KillauraTrap extends Check {

    private final Map<Integer, TrapData> activeTraps = new ConcurrentHashMap<>();
    private long lastTrapSpawn = 0;
    private int trapHits = 0;

    private static int nextEntityId = 500000;

    private static final String[] FAKE_NAMES = {
        "Steve", "Alex", "Herobrine", "Notch", "jeb_", "Dinnerbone"
    };

    // Config
    private static final long TRAP_SPAWN_INTERVAL = 3000;
    private static final long TRAP_LIFETIME = 10000;
    private static final int MAX_ACTIVE_TRAPS = 2;
    private static final boolean DEBUG_VISIBLE = false; // Disabled debug messages
    private static final double BEHIND_DISTANCE = 2.5;

    private int spawnCounter = 0;

    public KillauraTrap(GACUser user) {
        super(user, "KillauraTrap", "Spawns invisible bots to catch killaura.");

        // Spawn task
        Bukkit.getScheduler().runTaskTimer(GAC.getInstance(), this::trySpawnTrap, 100L, 60L);

        // Movement update - respawn at new position every 5 ticks
        Bukkit.getScheduler().runTaskTimer(GAC.getInstance(), this::updateTrapPositions, 20L, 5L);
    }

    @Override
    public void onPacket(PacketEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.USE_ENTITY) return;

        try {
            int entityId = event.getPacket().getIntegers().read(0);

            if (activeTraps.containsKey(entityId)) {
                trapHits++;

                Player player = user.getPlayer();
                if (player == null) return;

                event.setCancelled(true);

                TrapData trap = activeTraps.remove(entityId);
                despawnEntity(trap.entityId);

                user.getMitigation().onKillauraViolation("trap");
                fail(String.format("hit invisible trap (hits=%d, type=%s)", trapHits, trap.isPlayer ? "player" : "armorstand"));

                if (trapHits >= 3) {
                    trapHits = 0;
                }
            }
        } catch (Exception ignored) {}
    }

    private void trySpawnTrap() {
        Player player = user.getPlayer();
        if (player == null || !player.isOnline()) return;

        long now = System.currentTimeMillis();
        if (now - lastTrapSpawn < TRAP_SPAWN_INTERVAL) return;
        if (activeTraps.size() >= MAX_ACTIVE_TRAPS) return;

        if (!DEBUG_VISIBLE && user.getDeltaXZ() < 0.1) return;
        if (!DEBUG_VISIBLE && ThreadLocalRandom.current().nextDouble() > 0.4) return;

        // Alternate between armor stand and fake player
        spawnCounter++;
        if (spawnCounter % 2 == 0) {
            spawnFakePlayer(player);
        } else {
            spawnArmorStand(player);
        }

        lastTrapSpawn = now;
    }

    private Location getBehindLocation(Player player, double offsetX, double offsetZ) {
        Location loc = player.getLocation();
        double yaw = Math.toRadians(loc.getYaw());

        double x = loc.getX() + Math.sin(yaw) * BEHIND_DISTANCE + offsetX;
        double z = loc.getZ() - Math.cos(yaw) * BEHIND_DISTANCE + offsetZ;
        double y = loc.getY();

        return new Location(player.getWorld(), x, y, z);
    }

    private void spawnArmorStand(Player player) {
        try {
            double offsetX = (ThreadLocalRandom.current().nextDouble() - 0.5) * 1.0;
            double offsetZ = (ThreadLocalRandom.current().nextDouble() - 0.5) * 1.0;
            Location spawnLoc = getBehindLocation(player, offsetX, offsetZ);

            int entityId = nextEntityId++;
            UUID entityUuid = UUID.randomUUID();

            TrapData trapData = new TrapData(entityId, entityUuid, null, offsetX, offsetZ, false);
            trapData.currentLoc = spawnLoc.clone();

            // Spawn armor stand
            spawnArmorStandAt(player, entityId, entityUuid, spawnLoc);

            activeTraps.put(entityId, trapData);

            scheduleRemoval(entityId);

        } catch (Exception e) {
            // Silently fail
        }
    }

    private void spawnArmorStandAt(Player player, int entityId, UUID uuid, Location loc) {
        try {
            PacketContainer spawnPacket = new PacketContainer(PacketType.Play.Server.SPAWN_ENTITY);
            spawnPacket.getIntegers().write(0, entityId);
            spawnPacket.getUUIDs().write(0, uuid);
            spawnPacket.getEntityTypeModifier().write(0, org.bukkit.entity.EntityType.ARMOR_STAND);
            spawnPacket.getDoubles().write(0, loc.getX());
            spawnPacket.getDoubles().write(1, loc.getY());
            spawnPacket.getDoubles().write(2, loc.getZ());

            ProtocolLibrary.getProtocolManager().sendServerPacket(player, spawnPacket);

            // Always make invisible (User Request: "Invisible in F5")
            // Since we can't detect F5, making it invisible is the only way to ensure they don't see it.
            // Most Killauras hit invisible entities anyway.
            sendInvisibleMetadata(player, entityId);
            
        } catch (Exception e) {
            // Silently fail
        }
    }

    private void spawnFakePlayer(Player player) {
        try {
            double offsetX = (ThreadLocalRandom.current().nextDouble() - 0.5) * 1.0;
            double offsetZ = (ThreadLocalRandom.current().nextDouble() - 0.5) * 1.0;
            Location spawnLoc = getBehindLocation(player, offsetX, offsetZ);

            int entityId = nextEntityId++;
            UUID fakeUuid = UUID.randomUUID();
            String fakeName = FAKE_NAMES[ThreadLocalRandom.current().nextInt(FAKE_NAMES.length)] +
                              ThreadLocalRandom.current().nextInt(100);

            WrappedGameProfile profile = new WrappedGameProfile(fakeUuid, fakeName);

            TrapData trapData = new TrapData(entityId, fakeUuid, profile, offsetX, offsetZ, true);
            trapData.currentLoc = spawnLoc.clone();
            trapData.fakeName = fakeName;

            // Try to spawn fake player
            boolean success = spawnFakePlayerAt(player, entityId, fakeUuid, profile, spawnLoc);

            if (!success) {
                spawnArmorStand(player);
                return;
            }

            activeTraps.put(entityId, trapData);

            scheduleRemoval(entityId);

        } catch (Exception e) {
            spawnArmorStand(player);
        }
    }

    private boolean spawnFakePlayerAt(Player player, int entityId, UUID uuid, WrappedGameProfile profile, Location loc) {
        try {
            // For 1.21+, fake players are complex. Instead, spawn a zombie/skeleton
            // which killaura will also target (most killauras target all entities)
            // This is more reliable than trying to fake PLAYER_INFO packets

            PacketContainer spawnPacket = new PacketContainer(PacketType.Play.Server.SPAWN_ENTITY);
            spawnPacket.getIntegers().write(0, entityId);
            spawnPacket.getUUIDs().write(0, uuid);
            // Use ZOMBIE instead of PLAYER - killaura targets all living entities
            spawnPacket.getEntityTypeModifier().write(0, org.bukkit.entity.EntityType.ZOMBIE);
            spawnPacket.getDoubles().write(0, loc.getX());
            spawnPacket.getDoubles().write(1, loc.getY());
            spawnPacket.getDoubles().write(2, loc.getZ());

            ProtocolLibrary.getProtocolManager().sendServerPacket(player, spawnPacket);

            // Make invisible (F5 fix)
            sendInvisibleMetadata(player, entityId);

            // Set silent (no sounds)
            sendSilentMetadata(player, entityId);

            return true;

        } catch (Exception e) {
            return false;
        }
    }

    private void sendSilentMetadata(Player player, int entityId) {
        try {
            PacketContainer metaPacket = new PacketContainer(PacketType.Play.Server.ENTITY_METADATA);
            metaPacket.getIntegers().write(0, entityId);

            List<WrappedDataValue> metadata = new ArrayList<>();
            // Index 4 = Silent (boolean)
            metadata.add(new WrappedDataValue(4, WrappedDataWatcher.Registry.get(Boolean.class), true));
            // Index 15 = NoAI for mobs
            metadata.add(new WrappedDataValue(15, WrappedDataWatcher.Registry.get(Byte.class), (byte) 0x01));
            metaPacket.getDataValueCollectionModifier().write(0, metadata);

            ProtocolLibrary.getProtocolManager().sendServerPacket(player, metaPacket);
        } catch (Exception ignored) {}
    }

    private void sendInvisibleMetadata(Player player, int entityId) {
        try {
            PacketContainer metaPacket = new PacketContainer(PacketType.Play.Server.ENTITY_METADATA);
            metaPacket.getIntegers().write(0, entityId);

            List<WrappedDataValue> metadata = new ArrayList<>();
            metadata.add(new WrappedDataValue(0, WrappedDataWatcher.Registry.get(Byte.class), (byte) 0x20));
            metaPacket.getDataValueCollectionModifier().write(0, metadata);

            ProtocolLibrary.getProtocolManager().sendServerPacket(player, metaPacket);
        } catch (Exception ignored) {}
    }

    /**
     * Update trap positions by despawning and respawning at new location
     * This is the most reliable method across all Minecraft versions
     */
    private void updateTrapPositions() {
        Player player = user.getPlayer();
        if (player == null || !player.isOnline()) return;
        if (activeTraps.isEmpty()) return;

        for (Map.Entry<Integer, TrapData> entry : activeTraps.entrySet()) {
            TrapData data = entry.getValue();

            Location newLoc = getBehindLocation(player, data.offsetX, data.offsetZ);

            // Only update if moved significantly
            if (data.currentLoc != null && data.currentLoc.distanceSquared(newLoc) < 0.5) {
                continue;
            }

            // Despawn old
            despawnEntity(data.entityId);

            // Respawn at new location with same ID
            if (data.isPlayer) {
                spawnFakePlayerAt(player, data.entityId, data.uuid, data.profile, newLoc);
            } else {
                spawnArmorStandAt(player, data.entityId, data.uuid, newLoc);
            }

            data.currentLoc = newLoc.clone();
        }
    }

    private void despawnEntity(int entityId) {
        try {
            Player player = user.getPlayer();
            if (player == null || !player.isOnline()) return;

            PacketContainer destroyPacket = new PacketContainer(PacketType.Play.Server.ENTITY_DESTROY);
            destroyPacket.getIntLists().write(0, Collections.singletonList(entityId));
            ProtocolLibrary.getProtocolManager().sendServerPacket(player, destroyPacket);
        } catch (Exception ignored) {}
    }

    private void scheduleRemoval(int entityId) {
        Bukkit.getScheduler().runTaskLater(GAC.getInstance(), () -> {
            TrapData trap = activeTraps.remove(entityId);
            if (trap != null) {
                despawnEntity(entityId);
                if (DEBUG_VISIBLE) {
                    Player p = user.getPlayer();
                    if (p != null) p.sendMessage("§7  → Trap expired (ID: " + entityId + ")");
                }
            }
        }, TRAP_LIFETIME / 50);
    }

    public void cleanup() {
        for (TrapData trap : activeTraps.values()) {
            despawnEntity(trap.entityId);
        }
        activeTraps.clear();
    }

    private static class TrapData {
        final int entityId;
        final UUID uuid;
        final WrappedGameProfile profile;
        final double offsetX;
        final double offsetZ;
        final boolean isPlayer;
        Location currentLoc;
        String fakeName;

        TrapData(int entityId, UUID uuid, WrappedGameProfile profile, double offsetX, double offsetZ, boolean isPlayer) {
            this.entityId = entityId;
            this.uuid = uuid;
            this.profile = profile;
            this.offsetX = offsetX;
            this.offsetZ = offsetZ;
            this.isPlayer = isPlayer;
        }
    }
}
