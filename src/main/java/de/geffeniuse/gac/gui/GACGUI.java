package de.geffeniuse.gac.gui;

import de.geffeniuse.gac.GAC;
import de.geffeniuse.gac.config.CheckConfig;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * GACGUI - Admin GUI for managing GAC checks.
 * Allows enabling/disabling individual check modules.
 */
public class GACGUI implements Listener {

    private static final String GUI_TITLE = "§b§lGAC §7- Check Manager";
    private static final String GUI_TITLE_PAGE = "§b§lGAC §7- Page ";

    // All available checks organized by category
    private static final String[][] COMBAT_CHECKS = {
        {"KillauraA", "Rotation Analysis"},
        {"KillauraB", "Click Pattern"},
        {"KillauraC", "Raytrace"},
        {"KillauraD", "Hit Timing"},
        {"KillauraE", "Packet Analysis"},
        {"KillauraF", "Timing Consistency"},
        {"KillauraG", "Post-Hit Rotation"},
        {"KillauraH", "Rotation Speed"},
        {"KillauraI", "Experimental"},
        {"KillauraJ", "Reach B"},
        {"KillauraK", "Aimbot"},
        {"KillauraL", "Multi-Aura"},
        {"KillauraTrap", "Trap Check"},
        {"AimA", "Aim Smoothness"},
        {"AimB", "Aim Consistency"},
        {"AimC", "Aim Acceleration"},
        {"ReachA", "Reach"},
        {"AutoClickerA", "Auto Clicker"},
        {"CriticalsA", "Criticals"},
    };

    private static final String[][] MOVEMENT_CHECKS = {
        {"FlyA", "Basic Fly"},
        {"FlyB", "Hover"},
        {"SpeedA", "Speed"},
        {"NoFallA", "NoFall"},
        {"TimerA", "Timer"},
        {"VelocityA", "Velocity"},
        {"VelocityB", "Vertical KB"},
        {"PhaseA", "Phase/NoClip"},
        {"JesusA", "Water Walk"},
        {"StepA", "Step"},
        {"SpiderA", "Spider/Climb"},
        {"BlinkA", "Blink"},
        {"BadPacketsA", "Bad Packets"},
        {"CrasherA", "Anti-Crasher"},
        {"CrasherB", "Packet Spam"},
        {"CrasherC", "Block/Item Crash"},
        {"CrasherD", "Protocol Anomaly"},
        {"VehicleA", "Vehicle Fly"},
        {"TeleportA", "ClickTP/Teleport"},
        {"ElytraA", "Elytra Fly"},
        {"AntiHungerA", "Anti-Hunger"},
        {"NoSlowA", "NoSlow"},
        {"NoWebA", "NoWeb"},
        {"StrafeA", "Strafe"},
        {"SimulationA", "Movement Simulation"},
    };

    private static final String[][] WORLD_CHECKS = {
        {"ScaffoldA", "Scaffold"},
        {"ScaffoldB", "Scaffold Vector"},
        {"ScaffoldC", "Scaffold Rotation"},
        {"ScaffoldD", "Scaffold Placement"},
        {"ScaffoldE", "Scaffold False Item"},
        {"FastBreakA", "Fast Break"},
        {"XrayStatsA", "Xray Stats"},
        {"XrayBaitA", "Xray Bait"},
        {"ExploitA", "Dupe/Exploit"},
        {"ExploitB", "NBT/Attributes"},
        {"ExploitC", "Universal Dupe"},
        {"ExploitD", "Advanced Dupe"},
    };

    private static final String[][] PLAYER_CHECKS = {
        {"ChestStealerA", "Chest Stealer"},
        {"InventoryMoveA", "Inventory Move"},
    };

    private int currentPage = 0;
    private static final int CHECKS_PER_PAGE = 45; // 5 rows of items

    public GACGUI() {
        Bukkit.getPluginManager().registerEvents(this, GAC.getInstance());
    }

    public void open(Player player) {
        open(player, 0);
    }

    public void open(Player player, int page) {
        currentPage = page;

        // Create all checks list
        List<String[]> allChecks = new ArrayList<>();
        allChecks.addAll(Arrays.asList(COMBAT_CHECKS));
        allChecks.addAll(Arrays.asList(MOVEMENT_CHECKS));
        allChecks.addAll(Arrays.asList(WORLD_CHECKS));
        allChecks.addAll(Arrays.asList(PLAYER_CHECKS));

        int totalPages = (int) Math.ceil((double) allChecks.size() / CHECKS_PER_PAGE);
        if (page >= totalPages) page = 0;

        String title = totalPages > 1 ? GUI_TITLE_PAGE + (page + 1) : GUI_TITLE;
        Inventory gui = Bukkit.createInventory(null, 54, title);

        // Calculate start and end index for this page
        int startIndex = page * CHECKS_PER_PAGE;
        int endIndex = Math.min(startIndex + CHECKS_PER_PAGE, allChecks.size());

        // Add check items
        int slot = 0;
        for (int i = startIndex; i < endIndex && slot < 45; i++) {
            String[] check = allChecks.get(i);
            String checkId = check[0];
            String checkName = check[1];

            boolean enabled = CheckConfig.isEnabled(checkId);
            ItemStack item = createCheckItem(checkId, checkName, enabled);
            gui.setItem(slot++, item);
        }

        // Bottom row: navigation and info
        // Fill bottom row with glass
        ItemStack glass = createItem(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 45; i < 54; i++) {
            gui.setItem(i, glass);
        }

        // Previous page button (slot 45)
        if (page > 0) {
            ItemStack prev = createItem(Material.ARROW, "§a← Previous Page");
            gui.setItem(45, prev);
        }

        // Info item (slot 49)
        ItemStack info = createItem(Material.BOOK, "§b§lGAC Info",
            "§7Total Checks: §f" + allChecks.size(),
            "§7Page: §f" + (page + 1) + "/" + totalPages,
            "",
            "§7Click checks to toggle");
        gui.setItem(49, info);

        // Next page button (slot 53)
        if (page < totalPages - 1) {
            ItemStack next = createItem(Material.ARROW, "§aNext Page →");
            gui.setItem(53, next);
        }

        // Enable/Disable all buttons
        ItemStack enableAll = createItem(Material.LIME_DYE, "§a§lEnable All");
        gui.setItem(46, enableAll);

        ItemStack disableAll = createItem(Material.RED_DYE, "§c§lDisable All");
        gui.setItem(52, disableAll);

        // Cloud Only Mode (Slot 48)
        ItemStack cloudMode = createItem(Material.LIGHT_BLUE_DYE, "§b§lCloud Only Mode",
            "§7Disables all local checks.",
            "§7Enables ONLY Cloud ML monitoring.",
            "§7(Mitigations based on Trust Score)");
        gui.setItem(48, cloudMode);

        player.openInventory(gui);
    }

    private ItemStack createCheckItem(String checkId, String checkName, boolean enabled) {
        Material mat = enabled ? Material.LIME_CONCRETE : Material.RED_CONCRETE;
        String status = enabled ? "§a§lENABLED" : "§c§lDISABLED";

        return createItem(mat, "§f" + checkId,
            "§7" + checkName,
            "",
            "§7Status: " + status,
            "",
            "§eClick to toggle");
    }

    private ItemStack createItem(Material mat, String name, String... lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (lore.length > 0) {
                meta.setLore(Arrays.asList(lore));
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;

        String title = event.getView().getTitle();
        if (!title.startsWith("§b§lGAC")) return;

        event.setCancelled(true);

        Player player = (Player) event.getWhoClicked();
        ItemStack clicked = event.getCurrentItem();

        if (clicked == null || !clicked.hasItemMeta()) return;

        String name = clicked.getItemMeta().getDisplayName();
        int slot = event.getRawSlot();

        // Navigation buttons
        if (slot == 45 && name.contains("Previous")) {
            open(player, currentPage - 1);
            return;
        }

        if (slot == 53 && name.contains("Next")) {
            open(player, currentPage + 1);
            return;
        }

        // Enable all
        if (slot == 46 && clicked.getType() == Material.LIME_DYE) {
            enableAll();
            player.sendMessage("§b§lGAC §7» §aAll checks enabled!");
            open(player, currentPage);
            return;
        }

        // Disable all
        if (slot == 52 && clicked.getType() == Material.RED_DYE) {
            disableAll();
            player.sendMessage("§b§lGAC §7» §cAll checks disabled!");
            open(player, currentPage);
            return;
        }

        // Cloud Only Mode
        if (slot == 48 && clicked.getType() == Material.LIGHT_BLUE_DYE) {
            disableAll();
            player.sendMessage("§b§lGAC §7» §bCloud Only Mode enabled!");
            player.sendMessage("§8  » §7All local checks disabled.");
            player.sendMessage("§8  » §7Cloud ML monitoring remains active.");
            open(player, currentPage);
            return;
        }

        // Toggle check
        if (slot < 45 && (clicked.getType() == Material.LIME_CONCRETE ||
                         clicked.getType() == Material.RED_CONCRETE)) {
            // Extract check ID from item name
            String checkId = name.replace("§f", "").trim();
            boolean newState = CheckConfig.toggle(checkId);

            String status = newState ? "§aenabled" : "§cdisabled";
            player.sendMessage("§b§lGAC §7» §f" + checkId + " " + status);

            // Refresh GUI
            open(player, currentPage);
        }
    }

    private void enableAll() {
        for (String[] check : COMBAT_CHECKS) CheckConfig.setEnabled(check[0], true);
        for (String[] check : MOVEMENT_CHECKS) CheckConfig.setEnabled(check[0], true);
        for (String[] check : WORLD_CHECKS) CheckConfig.setEnabled(check[0], true);
        for (String[] check : PLAYER_CHECKS) CheckConfig.setEnabled(check[0], true);
    }

    private void disableAll() {
        for (String[] check : COMBAT_CHECKS) CheckConfig.setEnabled(check[0], false);
        for (String[] check : MOVEMENT_CHECKS) CheckConfig.setEnabled(check[0], false);
        for (String[] check : WORLD_CHECKS) CheckConfig.setEnabled(check[0], false);
        for (String[] check : PLAYER_CHECKS) CheckConfig.setEnabled(check[0], false);
    }
}
