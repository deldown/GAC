package de.geffeniuse.gac.listener;

import de.geffeniuse.gac.GAC;
import de.geffeniuse.gac.data.PunishmentManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;

public class PunishmentListener implements Listener {

    public PunishmentListener() {
        GAC.getInstance().getServer().getPluginManager().registerEvents(this, GAC.getInstance());
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST)
    public void onLogin(AsyncPlayerPreLoginEvent event) {
        if (PunishmentManager.isBanned(event.getUniqueId(), event.getName())) {
            String reason = PunishmentManager.getBanReason(event.getUniqueId(), event.getName());
            long expiry = PunishmentManager.getBanExpiry(event.getUniqueId(), event.getName());
            String time = PunishmentManager.formatTime(expiry);
            
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, 
                "§b§lGAC\n\n" +
                "§cYou are banned from this server.\n" +
                "§fReason: §b" + reason + "\n" +
                "§fDuration: §e" + time
            );
        }
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        if (PunishmentManager.isMuted(event.getPlayer().getUniqueId())) {
            String reason = PunishmentManager.getMuteReason(event.getPlayer().getUniqueId());
            long expiry = PunishmentManager.getMuteExpiry(event.getPlayer().getUniqueId());
            String time = PunishmentManager.formatTime(expiry);
            
            event.setCancelled(true);
            event.getPlayer().sendMessage("§c§lGAC §8» §cYou are muted!");
            event.getPlayer().sendMessage("§8  » §fReason: §7" + reason);
            event.getPlayer().sendMessage("§8  » §fDuration: §7" + time);
        }
    }
}