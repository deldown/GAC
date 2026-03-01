package de.geffeniuse.gac.data;

import de.geffeniuse.gac.GAC;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * TrustedPlayers - Manages players marked as "legit" for ML training.
 * Trusted players' movement data is used to train the ML models
 * on what normal/legit behavior looks like.
 */
public class TrustedPlayers {

    private static final Set<UUID> trustedPlayers = new HashSet<>();
    private static final Set<String> trustedNames = new HashSet<>(); // For display
    private static File configFile;

    public static void init() {
        configFile = new File(GAC.getInstance().getDataFolder(), "trusted-players.yml");
        load();
    }

    public static void load() {
        if (!configFile.exists()) {
            return;
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(configFile);
        trustedPlayers.clear();
        trustedNames.clear();

        for (String uuidStr : config.getStringList("trusted")) {
            try {
                UUID uuid = UUID.fromString(uuidStr);
                trustedPlayers.add(uuid);
            } catch (Exception e) {
                // Invalid UUID, skip
            }
        }

        for (String name : config.getStringList("names")) {
            trustedNames.add(name.toLowerCase());
        }

        GAC.getInstance().getLogger().info("Loaded " + trustedPlayers.size() + " trusted players for ML training.");
    }

    public static void save() {
        YamlConfiguration config = new YamlConfiguration();

        java.util.List<String> uuids = new java.util.ArrayList<>();
        for (UUID uuid : trustedPlayers) {
            uuids.add(uuid.toString());
        }
        config.set("trusted", uuids);
        config.set("names", new java.util.ArrayList<>(trustedNames));

        try {
            config.save(configFile);
        } catch (IOException e) {
            GAC.getInstance().getLogger().warning("Failed to save trusted players: " + e.getMessage());
        }
    }

    /**
     * Add a player to the trusted list.
     */
    public static boolean trust(UUID uuid, String name) {
        if (trustedPlayers.contains(uuid)) {
            return false; // Already trusted
        }
        trustedPlayers.add(uuid);
        trustedNames.add(name.toLowerCase());
        save();
        return true;
    }

    /**
     * Remove a player from the trusted list.
     */
    public static boolean untrust(UUID uuid, String name) {
        if (!trustedPlayers.contains(uuid)) {
            return false; // Not trusted
        }
        trustedPlayers.remove(uuid);
        trustedNames.remove(name.toLowerCase());
        save();
        return true;
    }

    /**
     * Check if a player is trusted.
     */
    public static boolean isTrusted(UUID uuid) {
        return trustedPlayers.contains(uuid);
    }

    /**
     * Check if a player is trusted by name (for offline lookup).
     */
    public static boolean isTrustedByName(String name) {
        return trustedNames.contains(name.toLowerCase());
    }

    /**
     * Get all trusted player names.
     */
    public static Set<String> getTrustedNames() {
        return new HashSet<>(trustedNames);
    }

    /**
     * Get count of trusted players.
     */
    public static int getCount() {
        return trustedPlayers.size();
    }
}
