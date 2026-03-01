package de.geffeniuse.gac.check.world;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.BlockPosition;
import de.geffeniuse.gac.GAC;
import de.geffeniuse.gac.check.Check;
import de.geffeniuse.gac.data.GACUser;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.potion.PotionEffectType;

import java.util.LinkedList;

/**
 * FastBreakA - Instant Break / Instant Rebreak Detection
 *
 * Tracks:
 * 1. START_DESTROY_BLOCK → record dig start time + position
 * 2. BlockBreakEvent → compare actual vs expected break time
 * 3. Same block broken again too quickly → instant rebreak
 */
public class FastBreakA extends Check implements Listener {

    // Dig start tracking
    private long digStartTime = 0;
    private int digStartX, digStartY, digStartZ;

    // Rebreak tracking
    private long lastBreakTime = 0;
    private int lastBreakX, lastBreakY, lastBreakZ;

    // Rate tracking (nuker / fast break without timing)
    private final LinkedList<Long> breakTimes = new LinkedList<>();

    private int suspicion = 0;

    private static final int MAX_BPS = 20;
    private static final int SUSPICION_THRESHOLD = 3;
    private static final int KICK_THRESHOLD = 5;
    // How fast is "instant rebreak" (ms) - same block position broken twice within this window
    private static final long REBREAK_WINDOW = 400;

    public FastBreakA(GACUser user) {
        super(user, "FastBreak", "Detects instant break and instant rebreak.");
        Bukkit.getPluginManager().registerEvents(this, GAC.getInstance());
    }

    @Override
    public void onPacket(PacketEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.BLOCK_DIG) return;

        Player player = event.getPlayer();
        if (!player.getUniqueId().equals(user.getUuid())) return;
        if (player.getGameMode() == GameMode.CREATIVE) return;

        try {
            int action = event.getPacket().getIntegers().read(0); // DiggingAction ordinal
            // action 0 = START_DESTROY_BLOCK, action 2 = ABORT_DESTROY_BLOCK

            BlockPosition pos = event.getPacket().getBlockPositionModifier().read(0);

            if (action == 0) {
                // START digging
                digStartTime = System.currentTimeMillis();
                digStartX = pos.getX();
                digStartY = pos.getY();
                digStartZ = pos.getZ();
            }
        } catch (Exception ignored) {}
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (!player.getUniqueId().equals(user.getUuid())) return;
        if (player.getGameMode() == GameMode.CREATIVE) return;

        Block block = event.getBlock();
        long now = System.currentTimeMillis();

        int bx = block.getX(), by = block.getY(), bz = block.getZ();

        // ========== CHECK 1: Nuker / Too many BPS ==========
        breakTimes.add(now);
        breakTimes.removeIf(t -> now - t > 1000);
        int bps = breakTimes.size();

        if (bps > MAX_BPS) {
            suspicion += 2;
            if (suspicion >= SUSPICION_THRESHOLD) {
                fail("nuker bps=" + bps);
                suspicion = 0;
                event.setCancelled(true);
                punishIfNeeded(player);
            }
        }

        // ========== CHECK 2: Instant Break (compared to dig start) ==========
        float hardness = block.getType().getHardness();
        if (hardness > 1.5f && digStartTime > 0
                && digStartX == bx && digStartY == by && digStartZ == bz) {

            long actualTime = now - digStartTime;
            float expectedTime = calculateBreakTime(player, block.getType());

            // Player broke block in less than 40% of expected time = instant break
            if (actualTime < expectedTime * 0.4f) {
                suspicion += 2;
                if (suspicion >= SUSPICION_THRESHOLD) {
                    fail(String.format("instant break %s actual=%dms expected=%.0fms",
                            block.getType().name(), actualTime, expectedTime));
                    suspicion = 0;
                    event.setCancelled(true);
                    punishIfNeeded(player);
                }
            }
        }

        // ========== CHECK 3: Instant Rebreak (same position broken again too fast) ==========
        if (lastBreakTime > 0 && now - lastBreakTime < REBREAK_WINDOW
                && lastBreakX == bx && lastBreakY == by && lastBreakZ == bz) {

            long rebreakInterval = now - lastBreakTime;
            // If it's the same block AND it was broken again in <400ms, that's suspicious
            // (Block would need to re-place itself server-side first)
            suspicion += 3;
            if (suspicion >= SUSPICION_THRESHOLD) {
                fail(String.format("instant rebreak %s interval=%dms",
                        block.getType().name(), rebreakInterval));
                suspicion = 0;
                event.setCancelled(true);
                punishIfNeeded(player);
            }
        }

        // Update last break position
        lastBreakTime = now;
        lastBreakX = bx;
        lastBreakY = by;
        lastBreakZ = bz;

        // Reset dig tracking after break
        digStartTime = 0;

        // Decay
        if (bps <= 3) {
            suspicion = Math.max(0, suspicion - 1);
        }
    }

    private void punishIfNeeded(Player player) {
        if (getViolationLevel() >= KICK_THRESHOLD) {
            GAC.incrementKicks();
            player.kickPlayer("§b§lGAC \n\n§7Unfair Advantage detected.\n§fCheck: §bFastBreak");
        }
    }

    /**
     * Calculate expected break time in ms based on tool, enchantments, and effects.
     * Based on Minecraft's block breaking formula.
     */
    private float calculateBreakTime(Player player, Material material) {
        float hardness = material.getHardness();
        if (hardness < 0) return Float.MAX_VALUE; // Unbreakable

        // Base time in ms (hardness * 1500ms is approximate for no tool)
        float baseTime = hardness * 1500f;

        Material tool = player.getInventory().getItemInMainHand().getType();
        boolean correctTool = isCorrectTool(tool, material);

        if (correctTool) {
            float toolMultiplier = getToolMultiplier(tool);
            baseTime /= toolMultiplier;

            int efficiency = player.getInventory().getItemInMainHand()
                    .getEnchantmentLevel(Enchantment.EFFICIENCY);
            if (efficiency > 0) {
                baseTime /= (1 + efficiency * efficiency);
            }
        }

        if (player.hasPotionEffect(PotionEffectType.HASTE)) {
            int level = player.getPotionEffect(PotionEffectType.HASTE).getAmplifier() + 1;
            baseTime /= (1 + 0.2f * level);
        }

        if (player.hasPotionEffect(PotionEffectType.MINING_FATIGUE)) {
            int level = player.getPotionEffect(PotionEffectType.MINING_FATIGUE).getAmplifier() + 1;
            baseTime *= Math.pow(0.3, level);
        }

        return Math.max(50f, baseTime);
    }

    private boolean isCorrectTool(Material tool, Material block) {
        String toolName = tool.name();
        String blockName = block.name();

        if (blockName.contains("STONE") || blockName.contains("ORE") ||
                blockName.contains("BRICK") || blockName.contains("DEEPSLATE")) {
            return toolName.contains("PICKAXE");
        }
        if (blockName.contains("DIRT") || blockName.contains("SAND") ||
                blockName.contains("GRAVEL") || blockName.contains("CLAY")) {
            return toolName.contains("SHOVEL");
        }
        if (blockName.contains("LOG") || blockName.contains("WOOD") ||
                blockName.contains("PLANK") || blockName.contains("STEM")) {
            return toolName.contains("AXE");
        }
        return false;
    }

    private float getToolMultiplier(Material tool) {
        String name = tool.name();
        if (name.contains("NETHERITE")) return 9f;
        if (name.contains("DIAMOND")) return 8f;
        if (name.contains("IRON")) return 6f;
        if (name.contains("STONE")) return 4f;
        if (name.contains("GOLD")) return 12f;
        if (name.contains("WOOD")) return 2f;
        return 1f;
    }
}
