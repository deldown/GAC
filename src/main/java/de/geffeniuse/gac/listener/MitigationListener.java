package de.geffeniuse.gac.listener;

import de.geffeniuse.gac.GAC;
import de.geffeniuse.gac.data.GACUser;
import de.geffeniuse.gac.mitigation.MitigationManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

/**
 * MitigationListener - Cancels cheater actions instead of kicking
 *
 * Philosophy: Make cheats useless without kicking innocent players
 * - Cancel illegal hits (reach, killaura)
 * - Cancel illegal block breaks (fast break)
 * - TROLL scaffold users (cancel, ghost blocks, delayed break)
 */
public class MitigationListener implements Listener {

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player)) return;

        Player attacker = (Player) event.getDamager();
        GACUser user = GAC.getInstance().getUser(attacker.getUniqueId());

        if (user == null) return;

        // Check if mitigation manager wants to cancel this hit
        if (user.getMitigation().shouldCancelHit()) {
            event.setCancelled(true);

            // Optional: Silent cancel or notify player
            // attacker.sendMessage("§c§lGAC §8» §7Hit cancelled (reach mitigation)");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        GACUser user = GAC.getInstance().getUser(player.getUniqueId());

        if (user == null) return;

        MitigationManager mitigation = user.getMitigation();

        // Check if scaffold mitigation is active
        if (mitigation.isScaffoldMitigation()) {
            // Get the troll action based on current VL (doesn't increment VL)
            MitigationManager.ScaffoldAction action = mitigation.getCurrentScaffoldAction();

            switch (action) {
                case CANCEL:
                    // Just cancel the block placement
                    event.setCancelled(true);
                    break;

                case GHOST_BLOCK:
                    // Allow placement but block will disappear after 0.5-2 seconds
                    // Let the event go through, then schedule removal
                    mitigation.applyGhostBlock(event.getBlock());
                    break;

                case DELAYED_BREAK:
                    // Allow placement but block will break after 1-3 seconds
                    // Perfect timing to make them fall!
                    mitigation.applyDelayedBreak(event.getBlock());
                    break;

                case KICK:
                    // Too many violations, kick the player
                    event.setCancelled(true);
                    GAC.incrementKicks();
                    player.kickPlayer("§b§lGAC \n\n§7Unfair Advantage detected.\n§fCheck: §bScaffold");
                    break;

                case ALLOW:
                default:
                    // Let it through
                    break;
            }
        } else {
            // Simple cancel check for non-scaffold mitigation
            if (mitigation.shouldCancelPlace()) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        GACUser user = GAC.getInstance().getUser(player.getUniqueId());

        if (user == null) return;

        // Could add fast break mitigation here
        // For now, just log
    }
}
