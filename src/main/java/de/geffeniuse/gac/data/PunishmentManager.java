package de.geffeniuse.gac.data;

import de.geffeniuse.gac.GAC;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

public class PunishmentManager {

    private static File file;
    private static YamlConfiguration config;

    public static void init() {
        file = new File(GAC.getInstance().getDataFolder(), "punishments.yml");
        if (!file.exists()) {
            try {
                file.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        config = YamlConfiguration.loadConfiguration(file);
    }

    public static void save() {
        try {
            config.save(file);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // ================= BAN LOGIC =================

    public static void ban(UUID uuid, String name, String reason, String by, long duration) {
        String path = "bans." + uuid.toString();
        config.set(path + ".name", name);
        config.set(path + ".reason", reason);
        config.set(path + ".by", by);
        config.set(path + ".expiry", duration == -1 ? -1 : System.currentTimeMillis() + duration);
        save();
    }

    public static void unban(UUID uuid) {
        config.set("bans." + uuid.toString(), null);
        save();
    }

    public static boolean isBanned(UUID uuid) {
        if (!config.contains("bans." + uuid.toString())) return false;
        
        long expiry = config.getLong("bans." + uuid.toString() + ".expiry");
        if (expiry != -1 && System.currentTimeMillis() > expiry) {
            unban(uuid); // Expired
            return false;
        }
        return true;
    }

    public static boolean isBanned(UUID uuid, String name) {
        // 1. Check by UUID (Primary)
        if (isBanned(uuid)) return true;
        
        // 2. Check by Name (Fallback for UUID mismatches)
        if (config.contains("bans")) {
            for (String key : config.getConfigurationSection("bans").getKeys(false)) {
                String bannedName = config.getString("bans." + key + ".name");
                if (bannedName != null && bannedName.equalsIgnoreCase(name)) {
                    // Check expiry
                    long expiry = config.getLong("bans." + key + ".expiry");
                    if (expiry != -1 && System.currentTimeMillis() > expiry) {
                        config.set("bans." + key, null); // Remove expired
                        save();
                        continue; 
                    }
                    return true;
                }
            }
        }
        return false;
    }

    public static String getBanReason(UUID uuid, String name) {
        // Try UUID
        if (isBanned(uuid)) {
            return config.getString("bans." + uuid.toString() + ".reason");
        }
        // Try Name
        if (config.contains("bans")) {
            for (String key : config.getConfigurationSection("bans").getKeys(false)) {
                String bannedName = config.getString("bans." + key + ".name");
                if (bannedName != null && bannedName.equalsIgnoreCase(name)) {
                    return config.getString("bans." + key + ".reason");
                }
            }
        }
        return null;
    }
    
    public static long getBanExpiry(UUID uuid, String name) {
        // Try UUID
        if (isBanned(uuid)) {
            return config.getLong("bans." + uuid.toString() + ".expiry");
        }
        // Try Name
        if (config.contains("bans")) {
            for (String key : config.getConfigurationSection("bans").getKeys(false)) {
                String bannedName = config.getString("bans." + key + ".name");
                if (bannedName != null && bannedName.equalsIgnoreCase(name)) {
                    return config.getLong("bans." + key + ".expiry");
                }
            }
        }
        return 0;
    }
    
    // Legacy support
    public static String getBanReason(UUID uuid) {
        return getBanReason(uuid, null);
    }
    
    public static long getBanExpiry(UUID uuid) {
        return getBanExpiry(uuid, null);
    }

    // ================= MUTE LOGIC =================

    public static void mute(UUID uuid, String name, String reason, String by, long duration) {
        String path = "mutes." + uuid.toString();
        config.set(path + ".name", name);
        config.set(path + ".reason", reason);
        config.set(path + ".by", by);
        config.set(path + ".expiry", duration == -1 ? -1 : System.currentTimeMillis() + duration);
        save();
    }

    public static void unmute(UUID uuid) {
        config.set("mutes." + uuid.toString(), null);
        save();
    }

    public static boolean isMuted(UUID uuid) {
        if (!config.contains("mutes." + uuid.toString())) return false;

        long expiry = config.getLong("mutes." + uuid.toString() + ".expiry");
        if (expiry != -1 && System.currentTimeMillis() > expiry) {
            unmute(uuid); // Expired
            return false;
        }
        return true;
    }

    public static String getMuteReason(UUID uuid) {
        if (!isMuted(uuid)) return null;
        return config.getString("mutes." + uuid.toString() + ".reason");
    }
    
    public static long getMuteExpiry(UUID uuid) {
        if (!isMuted(uuid)) return 0;
        return config.getLong("mutes." + uuid.toString() + ".expiry");
    }
    
    // ================= TIME UTILS =================
    
    public static long parseTime(String input) {
        if (input.equalsIgnoreCase("perm") || input.equalsIgnoreCase("permanent")) return -1;
        
        try {
            long value = Long.parseLong(input.substring(0, input.length() - 1));
            char unit = input.charAt(input.length() - 1);
            
            switch (Character.toLowerCase(unit)) {
                case 's': return value * 1000;
                case 'm': return value * 60 * 1000;
                case 'h': return value * 60 * 60 * 1000;
                case 'd': return value * 24 * 60 * 60 * 1000;
                case 'w': return value * 7 * 24 * 60 * 60 * 1000;
                case 'y': return value * 365 * 24 * 60 * 60 * 1000;
                default: return 0;
            }
        } catch (Exception e) {
            return 0;
        }
    }
    
    public static String formatTime(long expiry) {
        if (expiry == -1) return "Permanent";
        
        long remaining = expiry - System.currentTimeMillis();
        if (remaining <= 0) return "Expired";
        
        long seconds = remaining / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;
        
        if (days > 0) return days + "d " + (hours % 24) + "h";
        if (hours > 0) return hours + "h " + (minutes % 60) + "m";
        if (minutes > 0) return minutes + "m " + (seconds % 60) + "s";
        return seconds + "s";
    }
}
