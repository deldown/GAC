package de.geffeniuse.gac.check.movement;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketEvent;
import de.geffeniuse.gac.GAC;
import de.geffeniuse.gac.check.Check;
import de.geffeniuse.gac.data.GACUser;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/**
 * PhaseA - NoClip/Phase Detection
 * Detects players walking through solid blocks.
 */
public class PhaseA extends Check {

    private int suspicion = 0;
    private int insideBlockTicks = 0;
    private Location lastSafeLocation = null;
    private long lastGlidingTime = 0;
    private Block lastSolidBlock = null;

    private static final int SUSPICION_THRESHOLD = 10;
    private static final int KICK_THRESHOLD = 15;

    public PhaseA(GACUser user) {
        super(user, "Phase", "Detects walking through blocks.");
    }

    @Override
    public void onPacket(PacketEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.POSITION &&
            event.getPacketType() != PacketType.Play.Client.POSITION_LOOK) {
            return;
        }

        Player player = user.getPlayer();
        if (player == null) return;

        // Track when player was last gliding
        if (player.isGliding()) {
            lastGlidingTime = System.currentTimeMillis();
        }

        // Skip if creative, spectator, in vehicle, gliding, swimming or crawling
        if (player.getGameMode() == GameMode.CREATIVE ||
            player.getGameMode() == GameMode.SPECTATOR ||
            player.isInsideVehicle() || player.isGliding() ||
            player.isSwimming()) {          // isSwimming() = true when crawling too
            suspicion = 0;
            insideBlockTicks = 0;
            return;
        }

        // Grace period after gliding (elytra can cause weird clipping on landing)
        if (System.currentTimeMillis() - lastGlidingTime < 2000) {
            suspicion = 0;
            insideBlockTicks = 0;
            return;
        }

        Bukkit.getScheduler().runTask(GAC.getInstance(), () -> {
            if (player == null || !player.isOnline() || !isEnabled()) return;

            // Double-check spectator mode (player might have changed mode)
            if (player.getGameMode() == GameMode.SPECTATOR ||
                player.getGameMode() == GameMode.CREATIVE) {
                suspicion = 0;
                insideBlockTicks = 0;
                return;
            }

            Location loc = player.getLocation();

            // Check if player is inside a solid block
            boolean insideBlock = isInsideSolidBlock(player);
            boolean movingThroughBlock = isMovingThroughBlock(player);

            if (insideBlock) {
                insideBlockTicks++;

                // Ignore short moments (lag, respawn, server-client desync)
                if (insideBlockTicks > 12) {
                    suspicion += 2;

                    if (suspicion >= 5) {
                        String blockName = lastSolidBlock != null ? lastSolidBlock.getType().name() : "UNKNOWN";
                        fail("inside " + blockName);
                        suspicion = 0;

                        // Teleport back
                        if (lastSafeLocation != null) {
                            player.teleport(lastSafeLocation);
                        }

                        if (getViolationLevel() >= KICK_THRESHOLD) {
                            GAC.incrementKicks();
                            player.kickPlayer("§b§lGAC \n\n§7Unfair Advantage detected.\n§fCheck: §bPhase");
                        }
                    }
                }
            } else {
                insideBlockTicks = 0;
                lastSafeLocation = loc.clone();

                // Check if they moved through a block
                if (movingThroughBlock) {
                    suspicion += 2;

                    if (suspicion >= SUSPICION_THRESHOLD) {
                        fail("phased through block");
                        suspicion = 0;

                        if (lastSafeLocation != null) {
                            player.teleport(lastSafeLocation);
                        }

                        if (getViolationLevel() >= KICK_THRESHOLD) {
                            GAC.incrementKicks();
                            player.kickPlayer("§b§lGAC \n\n§7Unfair Advantage detected.\n§fCheck: §bPhase");
                        }
                    }
                } else {
                    suspicion = Math.max(0, suspicion - 1);
                }
            }
        });
    }

    private boolean isInsideSolidBlock(Player player) {
        Location loc = player.getLocation();

        // Use correct head height based on player pose:
        // Standing = 1.8, Sneaking = 1.5, Swimming/Crawling = 0.6
        double headOffset = player.isSneaking() ? 1.4 : 1.7;

        Block feetBlock = loc.getBlock();
        Block headBlock = loc.clone().add(0, headOffset, 0).getBlock();

        Block solidBlock = null;
        if (isFullSolidBlock(feetBlock)) solidBlock = feetBlock;
        else if (isFullSolidBlock(headBlock)) solidBlock = headBlock;

        if (solidBlock == null) return false;

        String name = solidBlock.getType().name();
        if (name.contains("DOOR") || name.contains("GATE") || name.contains("TRAPDOOR")) {
            return false;
        }

        // Store for fail message
        lastSolidBlock = solidBlock;
        return true;
    }

    private boolean isMovingThroughBlock(Player player) {
        double deltaX = user.getDeltaX();
        double deltaY = user.getDeltaY();
        double deltaZ = user.getDeltaZ();

        // Only check if moving significantly — 0.8 avoids sprint-jump false positives
        double distance = Math.sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ);
        if (distance < 0.8) return false;

        // Skip if distance is too large (teleport, not phase)
        // Also skip if player recently teleported
        if (distance > 5.0 || user.isTeleporting()) return false;

        Location from = player.getLocation().clone().subtract(deltaX, deltaY, deltaZ);
        Location to = player.getLocation();

        // Ray-trace between positions
        int steps = (int) Math.ceil(distance * 4);
        for (int i = 1; i < steps; i++) {
            double t = (double) i / steps;
            double x = from.getX() + (to.getX() - from.getX()) * t;
            double y = from.getY() + (to.getY() - from.getY()) * t;
            double z = from.getZ() + (to.getZ() - from.getZ()) * t;

            Location check = new Location(player.getWorld(), x, y, z);
            Block block = check.getBlock();

            if (isFullSolidBlock(block)) {
                // Check head height too
                Block headBlock = check.clone().add(0, 1.5, 0).getBlock();
                if (isFullSolidBlock(block) || isFullSolidBlock(headBlock)) {
                    return true;
                }
            }
        }

        return false;
    }

    private boolean isFullSolidBlock(Block block) {
        Material mat = block.getType();
        if (!mat.isSolid()) return false;
        if (mat.isAir()) return false;

        String name = mat.name();

        // Exclude ALL partial/passable/non-full blocks
        // This is a comprehensive list of blocks that players can partially be inside
        if (name.contains("SLAB") ||
            name.contains("STAIR") ||
            name.contains("STEP") ||
            name.contains("FENCE") ||
            name.contains("WALL") && !name.contains("WALL_") || // Cobblestone wall etc, but not WALL_SIGN
            name.contains("DOOR") ||
            name.contains("GATE") ||
            name.contains("PANE") ||
            name.contains("BAR") ||
            name.contains("SIGN") ||
            name.contains("BANNER") ||
            name.contains("HEAD") ||
            name.contains("SKULL") ||
            name.contains("_PATH") || // Dirt path, grass path - 15/16 block height
            name.contains("_LOG") || // Logs can cause issues at edges
            name.contains("FLOWER") ||
            name.contains("CARPET") ||
            name.contains("PRESSURE") ||
            name.contains("PLATE") ||
            name.contains("BUTTON") ||
            name.contains("LEVER") ||
            name.contains("TORCH") ||
            name.contains("LANTERN") ||
            name.contains("CHAIN") ||
            name.contains("ROD") ||
            name.contains("LIGHTNING") ||
            name.contains("CANDLE") ||
            name.contains("AMETHYST") ||
            name.contains("POINTED") ||
            name.contains("DRIPSTONE") ||
            name.contains("SCAFFOLDING") ||
            name.contains("LADDER") ||
            name.contains("VINE") ||
            name.contains("TRAPDOOR") ||
            name.contains("BED") ||
            name.contains("CHEST") ||
            name.contains("BARREL") ||
            name.contains("ENCHANT") ||
            name.contains("ANVIL") ||
            name.contains("BREWING") ||
            name.contains("CAULDRON") ||
            name.contains("HOPPER") ||
            name.contains("GRINDSTONE") ||
            name.contains("LECTERN") ||
            name.contains("COMPOSTER") ||
            name.contains("BELL") ||
            name.contains("CAMPFIRE") ||
            name.contains("FIRE") ||
            name.contains("SOUL") ||
            name.contains("CORAL") ||
            name.contains("PICKLE") ||
            name.contains("TURTLE") ||
            name.contains("EGG") ||
            name.contains("FROG") ||
            name.contains("DECORATED") ||
            name.contains("POT") ||
            name.contains("FLOWER") ||
            name.contains("SAPLING") ||
            name.contains("MUSHROOM") && !name.contains("BLOCK") ||
            name.contains("FUNGUS") ||
            name.contains("ROOTS") ||
            name.contains("SPROUTS") ||
            name.contains("AZALEA") && !name.contains("LEAVES") ||
            name.contains("LILY") ||
            name.contains("DRIPLEAF") ||
            name.contains("SPORE") ||
            name.contains("HANGING") ||
            name.contains("MANGROVE_ROOTS") ||
            name.contains("FERN") ||
            name.contains("GRASS") && !name.contains("BLOCK") ||
            name.contains("SEAGRASS") ||
            name.contains("KELP") ||
            name.contains("BAMBOO") ||
            name.contains("SUGAR_CANE") ||
            name.contains("CACTUS") ||
            name.contains("DEAD_BUSH") ||
            name.contains("SWEET_BERRY") ||
            name.contains("GLOW_LICHEN") ||
            name.contains("SCULK_VEIN") ||
            name.contains("COBWEB") ||
            name.contains("WEB") ||
            name.contains("STRING") ||
            name.contains("TRIPWIRE") ||
            name.contains("RAIL") ||
            name.contains("REDSTONE") && !name.contains("BLOCK") ||
            name.contains("REPEATER") ||
            name.contains("COMPARATOR") ||
            name.contains("DAYLIGHT") ||
            name.contains("OBSERVER") ||
            name.contains("PISTON") ||
            name.contains("STICKY") ||
            name.contains("SNOW") && !name.contains("BLOCK") ||
            name.contains("LAYER") ||
            name.contains("CAKE") ||
            name.contains("CANDLE") ||
            name.contains("BREWING") ||
            name.contains("FRAME") ||
            name.contains("ARMOR_STAND") ||
            name.contains("SHULKER") ||
            name.contains("CONDUIT") ||
            name.contains("BEACON") ||
            name.contains("END_PORTAL") ||
            name.contains("NETHER_PORTAL") ||
            name.contains("DRAGON") ||
            name.contains("STONECUTTER") ||
            name.contains("LOOM") ||
            name.contains("CARTOGRAPHY") ||
            name.contains("FLETCHING") ||
            name.contains("SMITHING") ||
            name.contains("RESPAWN_ANCHOR") ||
            name.contains("INFESTED") ||
            name.contains("MOSS")) {
            return false;
        }

        return true;
    }
}
