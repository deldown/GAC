package de.geffeniuse.gac.config;

import de.geffeniuse.gac.GAC;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * CheckConfig - Manages enabled/disabled state of individual checks.
 * Persists to checks.yml so settings survive server restarts.
 */
public class CheckConfig {

    private static final Map<String, Boolean> checkStates = new HashMap<>();
    private static File configFile;
    private static FileConfiguration config;

    public static void init(GAC plugin) {
        configFile = new File(plugin.getDataFolder(), "checks.yml");

        if (!configFile.exists()) {
            try {
                plugin.getDataFolder().mkdirs();
                configFile.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        config = YamlConfiguration.loadConfiguration(configFile);
        loadConfig();
    }

    private static void loadConfig() {
        // Load all saved check states
        if (config.contains("checks")) {
            for (String key : config.getConfigurationSection("checks").getKeys(false)) {
                checkStates.put(key, config.getBoolean("checks." + key, true));
            }
        }
    }

    public static void save() {
        // Save all check states
        for (Map.Entry<String, Boolean> entry : checkStates.entrySet()) {
            config.set("checks." + entry.getKey(), entry.getValue());
        }

        try {
            config.save(configFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Check if a specific check module is enabled.
     * @param checkId The check identifier (e.g., "KillauraA", "FlyB")
     * @return true if enabled (default), false if disabled
     */
    public static boolean isEnabled(String checkId) {
        return checkStates.getOrDefault(checkId, true);
    }

    /**
     * Enable or disable a check module.
     * @param checkId The check identifier
     * @param enabled true to enable, false to disable
     */
    public static void setEnabled(String checkId, boolean enabled) {
        checkStates.put(checkId, enabled);
        save();
    }

    /**
     * Toggle a check's enabled state.
     * @param checkId The check identifier
     * @return The new enabled state
     */
    public static boolean toggle(String checkId) {
        boolean newState = !isEnabled(checkId);
        setEnabled(checkId, newState);
        return newState;
    }

    /**
     * Get all check states.
     */
    public static Map<String, Boolean> getAllStates() {
        return new HashMap<>(checkStates);
    }
}
