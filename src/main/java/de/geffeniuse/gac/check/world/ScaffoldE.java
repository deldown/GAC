package de.geffeniuse.gac.check.world;

import com.comphenix.protocol.events.PacketEvent;
import de.geffeniuse.gac.GAC;
import de.geffeniuse.gac.check.Check;
import de.geffeniuse.gac.data.GACUser;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;

/**
 * ScaffoldE - False Item Detection
 * Detects when a player places a block but holds a different item.
 * This is impossible in vanilla Minecraft.
 */
public class ScaffoldE extends Check implements Listener {

    private int violations = 0;
    private static final int KICK_THRESHOLD = 5;

    public ScaffoldE(GACUser user) {
        super(user, "Scaffold", "");
        Bukkit.getPluginManager().registerEvents(this, GAC.getInstance());
    }

    @Override
    public void onPacket(PacketEvent event) {
        // Not packet-based, uses BlockPlaceEvent
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (!player.getUniqueId().equals(user.getUuid())) return;
        if (!isEnabled()) return;

        // Skip creative mode
        if (player.getGameMode() == GameMode.CREATIVE) return;

        Block placed = event.getBlockPlaced();
        ItemStack handItem = event.getItemInHand();

        if (handItem == null || handItem.getType() == Material.AIR) {
            // Placed block with empty hand - impossible
            event.setCancelled(true);
            violations++;
            fail("Scaffold");

            if (violations >= KICK_THRESHOLD) {
                Bukkit.getScheduler().runTask(GAC.getInstance(), () -> {
                    GAC.incrementKicks();
                    player.kickPlayer("§cKicked by GAC");
                });
            }
            return;
        }

        Material handType = handItem.getType();
        Material placedType = placed.getType();

        // Check if hand item matches placed block
        if (!materialsMatch(handType, placedType)) {
            // Different item in hand than block placed
            event.setCancelled(true);
            violations++;
            fail("Scaffold");

            if (violations >= KICK_THRESHOLD) {
                Bukkit.getScheduler().runTask(GAC.getInstance(), () -> {
                    GAC.incrementKicks();
                    player.kickPlayer("§cKicked by GAC");
                });
            }
        } else {
            // Valid placement, decay violations
            violations = Math.max(0, violations - 1);
        }
    }

    /**
     * Check if hand material matches placed block material
     * Handles special cases like slabs, stairs, etc.
     */
    private boolean materialsMatch(Material hand, Material placed) {
        // Direct match
        if (hand == placed) return true;

        String handName = hand.name();
        String placedName = placed.name();

        // Some blocks have different item/block materials
        // Seeds -> Crops
        if (handName.contains("SEEDS") && placedName.contains("CROPS")) return true;
        if (hand == Material.WHEAT_SEEDS && placed == Material.WHEAT) return true;
        if (hand == Material.BEETROOT_SEEDS && placed == Material.BEETROOTS) return true;
        if (hand == Material.POTATO && placed == Material.POTATOES) return true;
        if (hand == Material.CARROT && placed == Material.CARROTS) return true;
        if (hand == Material.MELON_SEEDS && placed == Material.MELON_STEM) return true;
        if (hand == Material.PUMPKIN_SEEDS && placed == Material.PUMPKIN_STEM) return true;

        // Cocoa beans
        if (hand == Material.COCOA_BEANS && placed == Material.COCOA) return true;

        // Nether wart
        if (hand == Material.NETHER_WART && placed == Material.NETHER_WART) return true;

        // Sweet berries
        if (hand == Material.SWEET_BERRIES && placed == Material.SWEET_BERRY_BUSH) return true;

        // Glow berries
        if (hand == Material.GLOW_BERRIES && placed == Material.CAVE_VINES) return true;

        // Kelp
        if (hand == Material.KELP && placed == Material.KELP_PLANT) return true;

        // Bamboo
        if (hand == Material.BAMBOO && (placed == Material.BAMBOO || placed == Material.BAMBOO_SAPLING)) return true;

        // Sugar cane
        if (hand == Material.SUGAR_CANE && placed == Material.SUGAR_CANE) return true;

        // Cactus
        if (hand == Material.CACTUS && placed == Material.CACTUS) return true;

        // Redstone
        if (hand == Material.REDSTONE && placed == Material.REDSTONE_WIRE) return true;

        // String -> Tripwire
        if (hand == Material.STRING && placed == Material.TRIPWIRE) return true;

        // Flower pot contents
        if (placed == Material.FLOWER_POT) return true; // Can place many things

        // Banners - wall vs standing
        if (handName.contains("BANNER") && placedName.contains("BANNER")) return true;

        // Signs - wall vs standing
        if (handName.contains("SIGN") && placedName.contains("SIGN")) return true;

        // Torches - wall vs standing
        if (handName.contains("TORCH") && placedName.contains("TORCH")) return true;

        // Heads/Skulls - wall vs standing
        if ((handName.contains("HEAD") || handName.contains("SKULL")) &&
            (placedName.contains("HEAD") || placedName.contains("SKULL"))) return true;

        // Buttons
        if (handName.contains("BUTTON") && placedName.contains("BUTTON")) return true;

        // Pressure plates
        if (handName.contains("PRESSURE_PLATE") && placedName.contains("PRESSURE_PLATE")) return true;

        // Doors - placing gives double door
        if (handName.contains("DOOR") && placedName.contains("DOOR")) return true;

        // Beds - placing gives double bed
        if (handName.contains("BED") && placedName.contains("BED")) return true;

        // Tall flowers/plants
        if ((hand == Material.SUNFLOWER || hand == Material.LILAC ||
             hand == Material.ROSE_BUSH || hand == Material.PEONY ||
             hand == Material.TALL_GRASS || hand == Material.LARGE_FERN) &&
            (placedName.contains("TALL") || placedName.equals(handName))) return true;

        // Waterlogging doesn't change item
        if (handName.equals(placedName.replace("_WATERLOGGED", ""))) return true;

        return false;
    }
}
