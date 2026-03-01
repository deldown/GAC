package de.geffeniuse.gac.check.player;

import de.geffeniuse.gac.GAC;
import de.geffeniuse.gac.check.Check;
import de.geffeniuse.gac.data.GACUser;
import com.comphenix.protocol.events.PacketEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * ChestStealerA - detects auto-loot bots by CPS in inventory.
 *
 * Humans can do ~5-8 CPS in a chest at most.
 * ChestStealer bots run at 20-100 CPS with 0ms delay.
 * Threshold of 16 CPS leaves a large safety margin.
 */
public class ChestStealerA extends Check implements Listener {

    // Per-player click timestamps (last 1 second)
    private final Deque<Long> clicks = new ArrayDeque<>();

    // How many ticks (1s windows) over threshold before flagging
    private int overThresholdCount = 0;

    // CPS threshold — humans cannot sustain this in a chest
    private static final int CPS_THRESHOLD = 16;
    // How many consecutive over-threshold windows before flag
    private static final int WINDOWS_TO_FLAG = 2;
    private static final int KICK_VL = 10;

    public ChestStealerA(GACUser user) {
        super(user, "ChestStealer", "Detects auto-loot bots.");
        Bukkit.getPluginManager().registerEvents(this, GAC.getInstance());
    }

    @Override
    public void onPacket(PacketEvent event) {}

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!isEnabled()) return;
        if (!(event.getWhoClicked() instanceof Player)) return;
        if (!event.getWhoClicked().getUniqueId().equals(user.getUuid())) return;

        InventoryType type = event.getInventory().getType();
        // Only check external inventories (chests, hoppers, etc.)
        if (type == InventoryType.PLAYER || type == InventoryType.CRAFTING ||
                type == InventoryType.CREATIVE) return;
        if (event.getSlot() < 0) return;

        long now = System.currentTimeMillis();

        // Deduplicate: Minecraft fires multiple events per shift-click in the same ms.
        // Treat anything within 50ms as the same physical click.
        if (!clicks.isEmpty() && now - clicks.peekLast() < 50) return;

        clicks.addLast(now);
        // Remove clicks older than 1 second
        while (!clicks.isEmpty() && now - clicks.peekFirst() > 1000) {
            clicks.pollFirst();
        }

        int cps = clicks.size();

        if (cps > CPS_THRESHOLD) {
            overThresholdCount++;
            event.setCancelled(true);

            if (overThresholdCount >= WINDOWS_TO_FLAG) {
                fail("cps=" + cps);
                overThresholdCount = 0;
                Player player = (Player) event.getWhoClicked();
                Bukkit.getScheduler().runTask(GAC.getInstance(), player::closeInventory);

                if (getViolationLevel() >= KICK_VL) {
                    GAC.incrementKicks();
                    player.kickPlayer("§b§lGAC \n\n§7Unfair Advantage detected.\n§fCheck: §bChestStealer");
                }
            }
        } else {
            overThresholdCount = Math.max(0, overThresholdCount - 1);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!event.getPlayer().getUniqueId().equals(user.getUuid())) return;
        clicks.clear();
        overThresholdCount = 0;
    }

    public static String getStatus() {
        return "§7ChestStealer: §aACTIVE §8(threshold: " + CPS_THRESHOLD + " CPS)";
    }
}
