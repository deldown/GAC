package de.geffeniuse.gac;

import de.geffeniuse.gac.config.CheckConfig;
import de.geffeniuse.gac.gui.GACGUI;
import de.geffeniuse.gac.packet.PacketManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Logger;

public class GAC extends JavaPlugin {

    public static GAC INSTANCE;
    private Logger logger;
    private GACGUI gui;

    // User Management
    private final java.util.Map<java.util.UUID, de.geffeniuse.gac.data.GACUser> users = new java.util.HashMap<>();

    // Statistics
    private static int totalKicks = 0;
    private static int totalFlags = 0;

    // TPS Tracking
    private static double tps = 20.0;
    private long lastTickTime = 0;

    @Override
    public void onEnable() {
        INSTANCE = this;
        logger = getLogger();

        if (getServer().getPluginManager().getPlugin("ProtocolLib") == null) {
            logger.severe("ProtocolLib not found! GAC requires ProtocolLib.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        logger.info("GAC (Geffeniuse Anticheat) has been enabled!");

        de.geffeniuse.gac.util.GACLogger.init();
        de.geffeniuse.gac.data.TrustedPlayers.init();
        de.geffeniuse.gac.data.PunishmentManager.init();
        new de.geffeniuse.gac.listener.PunishmentListener();

        // TPS Tracker
        lastTickTime = System.currentTimeMillis();
        getServer().getScheduler().runTaskTimer(this, () -> {
            long now = System.currentTimeMillis();
            long diff = now - lastTickTime;
            if (diff > 0) {
                double currentTps = 1000.0 / (diff / 20.0);
                tps = (tps * 0.95) + (Math.min(20.0, currentTps) * 0.05);
            }
            lastTickTime = now;
        }, 1L, 20L);

        // Global Listeners
        getServer().getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
            public void onTeleport(org.bukkit.event.player.PlayerTeleportEvent e) {
                de.geffeniuse.gac.data.GACUser user = getUser(e.getPlayer().getUniqueId());
                if (user != null) user.setLastTeleportTime(System.currentTimeMillis());
            }

            @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
            public void onRespawn(org.bukkit.event.player.PlayerRespawnEvent e) {
                de.geffeniuse.gac.data.GACUser user = getUser(e.getPlayer().getUniqueId());
                if (user != null) user.setLastTeleportTime(System.currentTimeMillis());
            }

            @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
            public void onVelocity(org.bukkit.event.player.PlayerVelocityEvent e) {
                de.geffeniuse.gac.data.GACUser user = getUser(e.getPlayer().getUniqueId());
                if (user != null) user.setLastVelocityTime(System.currentTimeMillis());
            }

            @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
            public void onGamemodeChange(org.bukkit.event.player.PlayerGameModeChangeEvent e) {
                de.geffeniuse.gac.data.GACUser user = getUser(e.getPlayer().getUniqueId());
                if (user != null) {
                    user.setLastGamemodeChange(System.currentTimeMillis());
                    user.resetData();
                }
            }

            @org.bukkit.event.EventHandler
            public void onJoin(org.bukkit.event.player.PlayerJoinEvent e) {
                users.put(e.getPlayer().getUniqueId(), new de.geffeniuse.gac.data.GACUser(e.getPlayer()));
            }

            @org.bukkit.event.EventHandler
            public void onQuit(org.bukkit.event.player.PlayerQuitEvent e) {
                users.remove(e.getPlayer().getUniqueId());
            }
        }, this);

        // Merge missing config keys from defaults (keeps existing values intact)
        getConfig().options().copyDefaults(true);
        saveConfig();

        CheckConfig.init(this);
        gui = new GACGUI();
        new PacketManager(this);
        getServer().getPluginManager().registerEvents(new de.geffeniuse.gac.listener.MitigationListener(), this);

        // Commands
        de.geffeniuse.gac.command.GACCommand cmdExecutor = new de.geffeniuse.gac.command.GACCommand();
        getCommand("gac").setExecutor(cmdExecutor);
        getCommand("ban").setExecutor(cmdExecutor);
        getCommand("tempban").setExecutor(cmdExecutor);
        getCommand("unban").setExecutor(cmdExecutor);
        getCommand("kick").setExecutor(cmdExecutor);
        getCommand("mute").setExecutor(cmdExecutor);
        getCommand("tempmute").setExecutor(cmdExecutor);
        getCommand("unmute").setExecutor(cmdExecutor);
        getCommand("gaccloud").setExecutor((sender, cmd, label, args) -> cmdExecutor.handleGacCloud(sender, args));

        logger.info("[GAC] Local ML enabled. No cloud required.");
    }

    public de.geffeniuse.gac.data.GACUser getUser(java.util.UUID uuid) {
        return users.get(uuid);
    }

    public static double getTPS() { return tps; }
    public GACGUI getGUI() { return gui; }

    @Override
    public void onDisable() {
        logger.info("GAC has been disabled.");
    }

    public static GAC getInstance() { return INSTANCE; }
    public static void incrementKicks() { totalKicks++; }
    public static void incrementFlags() { totalFlags++; }
    public static int getTotalKicks() { return totalKicks; }
    public static int getTotalFlags() { return totalFlags; }
}
