package de.geffeniuse.gac.check.player;

import de.geffeniuse.gac.GAC;
import de.geffeniuse.gac.check.Check;
import de.geffeniuse.gac.data.GACUser;
import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;

/**
 * InventoryMoveA - Inventory Move Detection
 * Detects players moving while they have an inventory open.
 */
public class InventoryMoveA extends Check implements Listener {

    private boolean inventoryOpen = false;
    private long inventoryOpenTime = 0;
    private int suspicion = 0;

    private static final double MAX_MOVE_SPEED = 0.15; // Allow slight momentum when opening inventory
    private static final int SUSPICION_THRESHOLD = 6;
    private static final int KICK_THRESHOLD = 8;

    public InventoryMoveA(GACUser user) {
        super(user, "InventoryMove", "Detects moving with inventory open.");
        Bukkit.getPluginManager().registerEvents(this, GAC.getInstance());
    }

    @Override
    public void onPacket(PacketEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.POSITION &&
            event.getPacketType() != PacketType.Play.Client.POSITION_LOOK) {
            return;
        }

        if (!inventoryOpen) return;

        Player player = user.getPlayer();
        if (player == null) return;

        if (player.getGameMode() == GameMode.CREATIVE ||
            player.getGameMode() == GameMode.SPECTATOR ||
            player.isGliding() || player.isRiptiding() || player.isFlying()) {
            return;
        }

        // Allow some time for inventory to fully open
        long now = System.currentTimeMillis();
        if (now - inventoryOpenTime < 200) return;

        double deltaXZ = user.getDeltaXZ();

        // Moving while inventory is open
        if (deltaXZ > MAX_MOVE_SPEED) {
            suspicion++;

            if (suspicion >= SUSPICION_THRESHOLD) {
                Bukkit.getScheduler().runTask(GAC.getInstance(), () -> {
                    if (player != null && player.isOnline() && isEnabled()) {
                        fail(String.format("move=%.2f", deltaXZ));
                        suspicion = 0;

                        // Close their inventory
                        player.closeInventory();

                        if (getViolationLevel() >= KICK_THRESHOLD) {
                            GAC.incrementKicks();
                            player.kickPlayer("§b§lGAC \n\n§7Unfair Advantage detected.\n§fCheck: §bInventoryMove");
                        }
                    }
                });
            }
        } else {
            // Small movement - decay
            suspicion = Math.max(0, suspicion - 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player)) return;
        Player player = (Player) event.getPlayer();

        if (!player.getUniqueId().equals(user.getUuid())) return;

        inventoryOpen = true;
        inventoryOpenTime = System.currentTimeMillis();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) return;
        Player player = (Player) event.getPlayer();

        if (!player.getUniqueId().equals(user.getUuid())) return;

        inventoryOpen = false;
        suspicion = 0;
    }
}
