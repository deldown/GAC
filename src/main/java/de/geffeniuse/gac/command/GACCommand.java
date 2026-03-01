package de.geffeniuse.gac.command;

import de.geffeniuse.gac.GAC;
import de.geffeniuse.gac.data.GACUser;
import de.geffeniuse.gac.data.PunishmentManager;
import de.geffeniuse.gac.data.TrustedPlayers;
import de.geffeniuse.gac.util.MLDebug;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack; // Added for /test command

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class GACCommand implements CommandExecutor {

    private final Set<UUID> alertsEnabled = new HashSet<>();

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Public info for all players
        if (!sender.hasPermission("gac.admin")) {
            sendPublicInfo(sender);
            return true;
        }

        // Admin commands
        if (args.length == 0 && label.equalsIgnoreCase("gac")) {
            // Open GUI if player
            if (sender instanceof Player) {
                GAC.getInstance().getGUI().open((Player) sender);
            } else {
                sendAdminInfo(sender);
            }
            return true;
        }

        String sub;
        String[] effectiveArgs;

        if (label.equalsIgnoreCase("gac")) {
            sub = args[0].toLowerCase();
            effectiveArgs = args;
        } else {
            // Alias command used (e.g. /ban player)
            // Normalize to: "ban" "player"
            sub = label.toLowerCase();
            effectiveArgs = new String[args.length + 1];
            effectiveArgs[0] = sub;
            System.arraycopy(args, 0, effectiveArgs, 1, args.length);
        }

        switch (sub) {
            case "alerts":
                handleAlerts(sender);
                break;
            case "ban":
                handlePunishment(sender, effectiveArgs, true, -1);
                break;
            case "tempban":
                handlePunishment(sender, effectiveArgs, true, 0); // 0 indicates needs parsing
                break;
            case "unban":
                handleUnban(sender, effectiveArgs);
                break;
            case "kick":
                handleKick(sender, effectiveArgs);
                break;
            case "mute":
                handlePunishment(sender, effectiveArgs, false, -1);
                break;
            case "tempmute":
                handlePunishment(sender, effectiveArgs, false, 0); // 0 indicates needs parsing
                break;
            case "unmute":
                handleUnmute(sender, effectiveArgs);
                break;
            case "gui":
                if (sender instanceof Player) GAC.getInstance().getGUI().open((Player) sender); 
                else sender.sendMessage("Only players can open GUI.");
                break;
            case "info":
                sendAdminInfo(sender);
                break;
            case "debug":
                handleDebug(sender);
                break;
            case "ml":
            case "status":
                handleMLStatus(sender);
                break;
            case "trust":
                handleTrust(sender, args, true);
                break;
            case "untrust":
                handleTrust(sender, args, false);
                break;
            case "trusted":
                handleTrustedList(sender);
                break;
            case "cloud":
                handleCloudStatus(sender);
                break;
            case "update":
                handleUpdate(sender);
                break;
            case "test": // New command for testing update
                handleTestCommand(sender);
                break;
            default:
                sender.sendMessage("§c§lGAC §8» §cUnknown command.");
                break;
        }
        return true;
    }

    /**
     * Handle /gaccloud command separately
     */
    public boolean handleGacCloud(CommandSender sender, String[] args) {
        if (!sender.hasPermission("gac.admin")) {
            sender.sendMessage("§c§lGAC §8» §cNo permission.");
            return true;
        }

        if (args.length == 0) {
            handleCloudStatus(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "status":
                handleCloudStatus(sender);
                break;
            case "player":
                if (args.length < 2) {
                    sender.sendMessage("§cUsage: /gaccloud player <name>");
                } else {
                    handleCloudPlayer(sender, args[1]);
                }
                break;
            default:
                sender.sendMessage("§b§lGAC Cloud §8» §7Commands:");
                sender.sendMessage("§8  » §7/gaccloud status §8- §fCheck cloud status");
                sender.sendMessage("§8  » §7/gaccloud player <name> §8- §fGet player cloud data");
                break;
        }
        return true;
    }

    // ================= HANDLERS =================

    private void handlePunishment(CommandSender sender, String[] args, boolean isBan, long forcedDuration) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /gac " + (isBan ? "ban" : "mute") + " <player> [time] [reason]");
            return;
        }

        String targetName = args[1];
        long duration = forcedDuration;
        int reasonStart = 2;

        // Parse time if needed (tempban/tempmute)
        if (duration == 0) {
            if (args.length < 3) {
                sender.sendMessage("§cUsage: /gac " + (isBan ? "tempban" : "tempmute") + " <player> <time> [reason]");
                return;
            }
            duration = PunishmentManager.parseTime(args[2]);
            if (duration <= 0) {
                sender.sendMessage("§cInvalid time format. Use: 10m, 1h, 1d, etc.");
                return;
            }
            reasonStart = 3;
        }

        // Parse reason
        StringBuilder reasonBuilder = new StringBuilder();
        for (int i = reasonStart; i < args.length; i++) {
            reasonBuilder.append(args[i]).append(" ");
        }
        String reason = reasonBuilder.length() > 0 ? reasonBuilder.toString().trim() : "Misconduct";

        // Get UUID
        org.bukkit.OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
        UUID uuid = target.getUniqueId();

        // Execute
        if (isBan) {
            PunishmentManager.ban(uuid, targetName, reason, sender.getName(), duration);
            if (target.isOnline()) {
                String timeStr = PunishmentManager.formatTime(duration == -1 ? -1 : System.currentTimeMillis() + duration);
                ((Player) target).kickPlayer("§b§lGAC\n\n§cYou have been banned.\n§fReason: §b" + reason + "\n§fDuration: §e" + timeStr);
            }
            Bukkit.broadcastMessage("§b§lGAC §8» §c" + targetName + " §7was banned by §c" + sender.getName() + "§7.");
        } else {
            PunishmentManager.mute(uuid, targetName, reason, sender.getName(), duration);
            if (target.isOnline()) {
                ((Player) target).sendMessage("§c§lGAC §8» §cYou have been muted by " + sender.getName() + ".");
                ((Player) target).sendMessage("§8  » §fReason: §7" + reason);
            }
            sender.sendMessage("§a§lGAC §8» §aMuted §e" + targetName + " §afor: §7" + reason);
        }
    }

    private void handleUnban(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /gac unban <player>");
            return;
        }
        String targetName = args[1];
        org.bukkit.OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
        
        PunishmentManager.unban(target.getUniqueId());
        sender.sendMessage("§a§lGAC §8» §aUnbanned §e" + targetName + "§a.");
    }

    private void handleKick(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /gac kick <player> [reason]");
            return;
        }
        
        String targetName = args[1];
        Player target = Bukkit.getPlayer(targetName);
        
        if (target == null) {
            sender.sendMessage("§c§lGAC §8» §cPlayer not found.");
            return;
        }
        
        StringBuilder reason = new StringBuilder();
        for (int i = 2; i < args.length; i++) {
            reason.append(args[i]).append(" ");
        }
        String kickReason = reason.length() > 0 ? reason.toString().trim() : "You have been kicked.";
        
        target.kickPlayer("§b§lGAC\n\n§cYou have been kicked.\n§fReason: §b" + kickReason);
        Bukkit.broadcastMessage("§b§lGAC §8» §c" + target.getName() + " §7was kicked by §c" + sender.getName() + "§7.");
    }

    private void handleUnmute(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /gac unmute <player>");
            return;
        }
        String targetName = args[1];
        org.bukkit.OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);

        PunishmentManager.unmute(target.getUniqueId());
        sender.sendMessage("§a§lGAC §8» §aUnmuted §e" + targetName + "§a.");
        if (target.isOnline()) {
            ((Player) target).sendMessage("§a§lGAC §8» §aYou have been unmuted.");
        }
    }

    private void handleAlerts(CommandSender sender) {
        if (!(sender instanceof Player)) return;
        Player player = (Player) sender;
        if (alertsEnabled.contains(player.getUniqueId())) {
            alertsEnabled.remove(player.getUniqueId());
            player.sendMessage("§c§lGAC §8» §cAlerts disabled.");
        } else {
            alertsEnabled.add(player.getUniqueId());
            player.sendMessage("§a§lGAC §8» §aAlerts enabled.");
        }
    }

    private void handleDebug(CommandSender sender) {
        if (!(sender instanceof Player)) return;
        Player player = (Player) sender;
        boolean enabled = MLDebug.toggle(player.getUniqueId());
        if (enabled) {
            player.sendMessage("§a§lGAC §8» §aML Debug Mode §2ENABLED");
        } else {
            player.sendMessage("§c§lGAC §8» §cML Debug Mode §4DISABLED");
        }
    }

    private void handleMLStatus(CommandSender sender) {
        sender.sendMessage("");
        sender.sendMessage("§b§l  GAC §8- §7Machine Learning Status");
        sender.sendMessage("");
        sender.sendMessage("  " + de.geffeniuse.gac.check.movement.SpiderA.getStatus());
        sender.sendMessage("  " + de.geffeniuse.gac.check.player.ChestStealerA.getStatus());
        sender.sendMessage("");
        sender.sendMessage("§8  » §7Trusted players: §b" + TrustedPlayers.getCount());
    }

    private void handleTrust(CommandSender sender, String[] args, boolean trust) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /gac " + (trust ? "trust" : "untrust") + " <player>");
            return;
        }
        String targetName = args[1];
        org.bukkit.OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
        
        if (trust) {
            if (TrustedPlayers.trust(target.getUniqueId(), targetName)) {
                sender.sendMessage("§a§lGAC §8» §aTrusted §e" + targetName);
            } else {
                sender.sendMessage("§e§lGAC §8» §e" + targetName + " is already trusted.");
            }
        } else {
            if (TrustedPlayers.untrust(target.getUniqueId(), targetName)) {
                sender.sendMessage("§a§lGAC §8» §aUntrusted §e" + targetName);
            } else {
                sender.sendMessage("§c§lGAC §8» §c" + targetName + " was not trusted.");
            }
        }
    }

    private void handleTrustedList(CommandSender sender) {
        Set<String> trusted = TrustedPlayers.getTrustedNames();
        sender.sendMessage("§b§lGAC §8» §7Trusted Players (" + trusted.size() + "): §f" + String.join(", ", trusted));
    }

    private void handleCloudStatus(CommandSender sender) {
        int onlinePlayers = Bukkit.getOnlinePlayers().size();
        int totalSamples = Bukkit.getOnlinePlayers().stream()
            .mapToInt(p -> {
                GACUser u = GAC.getInstance().getUser(p.getUniqueId());
                return u != null ? u.getBehaviorCollector().getSampleCount() : 0;
            }).sum();

        sender.sendMessage("");
        sender.sendMessage("§b§l  GAC Local ML §8- §a§lACTIVE");
        sender.sendMessage("");
        sender.sendMessage("§8  » §7Mode: §aFully Local §7(no cloud required)");
        sender.sendMessage("§8  » §7Online Players: §e" + onlinePlayers);
        sender.sendMessage("§8  » §7Behavior Samples: §d" + totalSamples);
        sender.sendMessage("§8  » §7Total Flags: §c" + GAC.getTotalFlags());
        sender.sendMessage("§8  » §7Total Kicks: §c" + GAC.getTotalKicks());
        sender.sendMessage("");
        sender.sendMessage("§8  » §7Active Modules:");
        sender.sendMessage("§8    - §fStatistical Behavior Analysis (LocalML)");
        sender.sendMessage("§8    - §fPhysics-based Fly Detection");
        sender.sendMessage("§8    - §fPacket Sequence Analysis (Timer/Blink)");
        sender.sendMessage("§8    - §fClient Spoof Detection");
        sender.sendMessage("§8    - §fCombat Pattern Analysis (Aimbot/KA)");
        sender.sendMessage("");
    }

    private void handleCloudPlayer(CommandSender sender, String playerName) {
        Player onlineTarget = Bukkit.getPlayer(playerName);
        if (onlineTarget == null) {
            sender.sendMessage("§c§lGAC §8» §c" + playerName + " is not online.");
            return;
        }

        GACUser user = GAC.getInstance().getUser(onlineTarget.getUniqueId());
        if (user == null) {
            sender.sendMessage("§c§lGAC §8» §cNo data for " + playerName + ".");
            return;
        }

        sender.sendMessage("");
        sender.sendMessage("§b§l  GAC Local ML §8- §f" + playerName);
        sender.sendMessage("");
        sender.sendMessage("§8  » §7Samples: §e" + user.getBehaviorCollector().getSampleCount() + "/180");
        sender.sendMessage("§8  » §7Session: §e" + formatPlaytime(user.getBehaviorCollector().getSessionDuration() / 60000));
        sender.sendMessage("§8  » §7Total VL: §c" + user.getTotalViolations());
        sender.sendMessage("§8  » §7Client: §f" + user.getClientBrand());
        sender.sendMessage("§8  » §7CPS: §e" + user.getCps() + " §7PPS: §e" + user.getPps());
        sender.sendMessage("§8  » §7AirTicks: §e" + user.getAirTicks());
        sender.sendMessage("");
    }

    private String formatAnomalyScore(double score) {
        if (score >= 0.8) return "§c" + String.format("%.2f", score) + " §7(Very Anomalous)";
        if (score >= 0.6) return "§6" + String.format("%.2f", score) + " §7(Anomalous)";
        if (score >= 0.4) return "§e" + String.format("%.2f", score) + " §7(Slightly Unusual)";
        return "§a" + String.format("%.2f", score) + " §7(Normal)";
    }

    private String formatConfidence(double score) {
        if (score >= 0.8) return "§c" + String.format("%.2f", score) + " §7(High)";
        if (score >= 0.5) return "§6" + String.format("%.2f", score) + " §7(Medium)";
        if (score >= 0.2) return "§e" + String.format("%.2f", score) + " §7(Low)";
        return "§7" + String.format("%.2f", score) + " §7(Minimal)";
    }

    private String formatTrustScore(double score) {
        if (score >= 80) return "§a" + String.format("%.1f", score) + " §7(Trusted)";
        if (score >= 60) return "§e" + String.format("%.1f", score) + " §7(Normal)";
        if (score >= 40) return "§6" + String.format("%.1f", score) + " §7(Suspicious)";
        return "§c" + String.format("%.1f", score) + " §7(High Risk)";
    }

    private String formatPlaytime(long minutes) {
        if (minutes < 60) return minutes + " min";
        if (minutes < 1440) return (minutes / 60) + "h " + (minutes % 60) + "m";
        return (minutes / 1440) + "d " + ((minutes % 1440) / 60) + "h";
    }

    private String formatNumber(long num) {
        if (num < 1000) return String.valueOf(num);
        if (num < 1000000) return String.format("%.1fK", num / 1000.0);
        return String.format("%.1fM", num / 1000000.0);
    }

    private void handleUpdate(CommandSender sender) {
        String url = GAC.getInstance().getConfig().getString("update.url", "").trim();
        if (url.isEmpty()) {
            sender.sendMessage("§c§lGAC §8» §cKein Update-URL konfiguriert.");
            sender.sendMessage("§8  » §7Trage die URL in §fconfig.yml §7unter §fupdate.url §7ein.");
            return;
        }

        sender.sendMessage("§b§lGAC §8» §7Downloading update...");

        Bukkit.getScheduler().runTaskAsynchronously(GAC.getInstance(), () -> {
            try {
                java.net.URL downloadUrl = new java.net.URL(url);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) downloadUrl.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(60000);
                conn.setRequestProperty("User-Agent", "GAC-Updater/1.0");

                int code = conn.getResponseCode();
                if (code != 200) {
                    Bukkit.getScheduler().runTask(GAC.getInstance(), () ->
                        sender.sendMessage("§c§lGAC §8» §cDownload fehlgeschlagen: HTTP " + code));
                    return;
                }

                java.io.File pluginsFolder = GAC.getInstance().getDataFolder().getParentFile();
                java.io.File updateFile = new java.io.File(pluginsFolder, "GAC-update.jar");

                // Download to temp file
                try (java.io.InputStream in = conn.getInputStream();
                     java.io.FileOutputStream out = new java.io.FileOutputStream(updateFile)) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    long totalBytes = 0;
                    while ((bytesRead = in.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                        totalBytes += bytesRead;
                    }
                }

                // Find the currently running GAC jar
                java.io.File currentJar = null;
                java.io.File[] files = pluginsFolder.listFiles();
                if (files != null) {
                    for (java.io.File f : files) {
                        String name = f.getName().toLowerCase();
                        if (name.startsWith("gac") && name.endsWith(".jar") && !name.equals("gac-update.jar")) {
                            currentJar = f;
                            break;
                        }
                    }
                }

                java.io.File finalCurrentJar = currentJar;
                Bukkit.getScheduler().runTask(GAC.getInstance(), () -> {
                    // Remove old jar
                    if (finalCurrentJar != null && finalCurrentJar.exists()) {
                        finalCurrentJar.delete();
                    }

                    // Place update as GAC.jar
                    java.io.File newJar = new java.io.File(pluginsFolder, "GAC.jar");
                    if (updateFile.renameTo(newJar)) {
                        sender.sendMessage("§a§lGAC §8» §a§lUpdate abgeschlossen!");
                        sender.sendMessage("§8  » §7Server neu starten um die Änderungen zu übernehmen.");
                    } else {
                        sender.sendMessage("§e§lGAC §8» §eUpdate als GAC-update.jar gespeichert.");
                        sender.sendMessage("§8  » §7Manuell umbenennen und Server neu starten.");
                    }
                });

            } catch (Exception e) {
                Bukkit.getScheduler().runTask(GAC.getInstance(), () ->
                    sender.sendMessage("§c§lGAC §8» §cUpdate fehlgeschlagen: " + e.getMessage()));
            }
        });
    }

    private void handleTestCommand(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§c§lGAC §8» §cThis command can only be used by players.");
            return;
        }
        Player player = (Player) sender;
        
        // Give 5 Diamonds
        player.getInventory().addItem(new ItemStack(org.bukkit.Material.DIAMOND, 5));
        player.sendMessage("§a§lGAC §8» §aYou received 5 Diamonds!");
    }

    private void sendPublicInfo(CommandSender sender) {
        int kicks = GAC.getTotalKicks();
        int flags = GAC.getTotalFlags();
        sender.sendMessage("");
        sender.sendMessage("§b§l  GAC §8- §7Geffeniuse AntiCheat");
        sender.sendMessage("");
        sender.sendMessage("§8  » §fThis server is protected by §bGAC§f.");
        sender.sendMessage("§8  » §7Version: §b1.0");
        sender.sendMessage("");
        sender.sendMessage("§8  » §7Players kicked: §c" + kicks);
        sender.sendMessage("§8  » §7Cheats detected: §e" + flags);
        sender.sendMessage("");
    }

    private void sendAdminInfo(CommandSender sender) {
        sender.sendMessage("");
        sender.sendMessage("§b§l  GAC §8- §7Admin Panel");
        sender.sendMessage("");
        sender.sendMessage("§8  » §7/gac alerts §8- §fToggle alerts");
        sender.sendMessage("§8  » §7/gac gui §8- §fOpen settings GUI");
        sender.sendMessage("§8  » §7/gac ban/tempban <player> ... §8- §fBan player");
        sender.sendMessage("§8  » §7/gac unban <player> §8- §fUnban player");
        sender.sendMessage("§8  » §7/gac kick <player> [reason] §8- §fKick player");
        sender.sendMessage("§8  » §7/gac mute/tempmute <player> ... §8- §fMute player");
        sender.sendMessage("§8  » §7/gac unmute <player> §8- §fUnmute player");
        sender.sendMessage("§8  » §7/gac ml §8- §fShow local ML status");
        sender.sendMessage("§8  » §7/gac trust/untrust <player> §8- §fManage trusted players");
        sender.sendMessage("§8  » §7/gac update §8- §fDownload & install update from cloud");
        sender.sendMessage("");
        sender.sendMessage("§b§l  GAC Cloud");
        sender.sendMessage("§8  » §7/gaccloud status §8- §fCloud ML status & ping");
        sender.sendMessage("§8  » §7/gaccloud player <name> §8- §fPlayer cloud data");
        sender.sendMessage("");
    }
}