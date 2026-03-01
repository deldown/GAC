package de.geffeniuse.gac.util;

import de.geffeniuse.gac.GAC;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MLDebug - Debug mode for ML checks
 * Shows live suspicion percentages for each player in chat
 */
public class MLDebug {

    // Players who have debug mode enabled
    private static final Set<UUID> debugEnabled = new HashSet<>();

    // Track suspicion levels per player per check
    // Map<PlayerUUID, Map<CheckName, SuspicionPercent>>
    private static final Map<UUID, Map<String, Integer>> playerSuspicion = new ConcurrentHashMap<>();

    // Last broadcast time per player (to avoid spam)
    private static final Map<UUID, Long> lastBroadcast = new ConcurrentHashMap<>();
    private static final long BROADCAST_INTERVAL = 1000; // 1 second

    /**
     * Toggle debug mode for a player
     */
    public static boolean toggle(UUID uuid) {
        if (debugEnabled.contains(uuid)) {
            debugEnabled.remove(uuid);
            return false;
        } else {
            debugEnabled.add(uuid);
            return true;
        }
    }

    /**
     * Check if debug is enabled for a player
     */
    public static boolean isEnabled(UUID uuid) {
        return debugEnabled.contains(uuid);
    }

    /**
     * Update suspicion level for a player's check
     * @param targetUUID The player being checked
     * @param checkName The check name (e.g., "Killaura", "Velocity")
     * @param suspicionPercent 0-100 percent confidence they are cheating
     */
    public static void updateSuspicion(UUID targetUUID, String checkName, int suspicionPercent) {
        playerSuspicion.computeIfAbsent(targetUUID, k -> new ConcurrentHashMap<>())
                      .put(checkName, Math.min(100, Math.max(0, suspicionPercent)));

        // Broadcast to debug-enabled players
        broadcastIfNeeded(targetUUID);
    }

    /**
     * Get suspicion level for a specific check
     */
    public static int getSuspicion(UUID targetUUID, String checkName) {
        Map<String, Integer> checks = playerSuspicion.get(targetUUID);
        if (checks == null) return 0;
        return checks.getOrDefault(checkName, 0);
    }

    /**
     * Broadcast suspicion levels to players with debug enabled
     */
    private static void broadcastIfNeeded(UUID targetUUID) {
        if (debugEnabled.isEmpty()) return;

        long now = System.currentTimeMillis();
        Long last = lastBroadcast.get(targetUUID);
        if (last != null && now - last < BROADCAST_INTERVAL) {
            return; // Too soon, skip
        }
        lastBroadcast.put(targetUUID, now);

        // Build message
        Player target = Bukkit.getPlayer(targetUUID);
        if (target == null) return;

        Map<String, Integer> checks = playerSuspicion.get(targetUUID);
        if (checks == null || checks.isEmpty()) return;

        StringBuilder msg = new StringBuilder();
        msg.append("§8[§bML§8] §f").append(target.getName()).append("§7: ");

        // Add each check with color based on suspicion
        boolean first = true;
        for (Map.Entry<String, Integer> entry : checks.entrySet()) {
            if (!first) msg.append(" §8| ");
            first = false;

            String checkName = entry.getKey();
            int percent = entry.getValue();

            // Short names
            String shortName = getShortName(checkName);

            // Color based on percentage
            String color = getColor(percent);

            msg.append("§7").append(shortName).append(" ").append(color).append(percent).append("%");
        }

        // Send to all debug-enabled players
        String message = msg.toString();
        for (UUID uuid : debugEnabled) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) {
                p.sendMessage(message);
            }
        }
    }

    private static String getShortName(String checkName) {
        switch (checkName.toLowerCase()) {
            case "killaura": return "KA";
            case "velocity": return "Vel";
            case "fly": return "Fly";
            case "speed": return "Spd";
            case "timer": return "Tmr";
            case "autoclicker": return "AC";
            case "aimbot": return "Aim";
            case "reach": return "Rch";
            default: return checkName.substring(0, Math.min(3, checkName.length()));
        }
    }

    private static String getColor(int percent) {
        if (percent <= 10) return "§a"; // Green - safe
        if (percent <= 30) return "§e"; // Yellow - suspicious
        if (percent <= 60) return "§6"; // Orange - likely
        if (percent <= 80) return "§c"; // Red - very likely
        return "§4"; // Dark red - definitely
    }

    /**
     * Clear data for a player (on quit)
     */
    public static void clearPlayer(UUID uuid) {
        playerSuspicion.remove(uuid);
        lastBroadcast.remove(uuid);
    }
}
