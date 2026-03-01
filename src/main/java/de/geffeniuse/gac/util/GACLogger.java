package de.geffeniuse.gac.util;

import de.geffeniuse.gac.GAC;
import de.geffeniuse.gac.data.GACUser;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * GACLogger - Detailed logging for debugging false positives.
 * Writes all flag/kick data to a file for analysis.
 */
public class GACLogger {

    private static File logFile;
    private static final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    private static final SimpleDateFormat fileFormat = new SimpleDateFormat("yyyy-MM-dd");

    public static void init() {
        try {
            File dataFolder = GAC.getInstance().getDataFolder();
            if (!dataFolder.exists()) {
                boolean created = dataFolder.mkdirs();
                GAC.getInstance().getLogger().info("Created data folder: " + created);
            }

            File logsFolder = new File(dataFolder, "logs");
            if (!logsFolder.exists()) {
                boolean created = logsFolder.mkdirs();
                GAC.getInstance().getLogger().info("Created logs folder: " + created + " at " + logsFolder.getAbsolutePath());
            }

            // Create daily log file
            String fileName = "gac-" + fileFormat.format(new Date()) + ".log";
            logFile = new File(logsFolder, fileName);

            // Write startup message to confirm logging works
            writeToFile("================== GAC LOGGER STARTED ==================\n" +
                       "Time: " + dateFormat.format(new Date()) + "\n" +
                       "Log file: " + logFile.getAbsolutePath() + "\n" +
                       "========================================================\n\n");

            GAC.getInstance().getLogger().info("GACLogger initialized: " + logFile.getAbsolutePath());
        } catch (Exception e) {
            GAC.getInstance().getLogger().severe("Failed to initialize GACLogger: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Log a flag event with full player state.
     */
    public static void logFlag(GACUser user, String checkName, String checkId, String info, int vl) {
        if (logFile == null) init();

        Player player = user.getPlayer();
        if (player == null) return;

        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append("================== FLAG ==================\n");
        sb.append("Time: ").append(dateFormat.format(new Date())).append("\n");
        sb.append("Player: ").append(player.getName()).append("\n");
        sb.append("Check: ").append(checkName).append(" (").append(checkId).append(")\n");
        sb.append("Info: ").append(info).append("\n");
        sb.append("VL: ").append(vl).append("\n");
        sb.append("\n");
        sb.append("--- Player State ---\n");

        Location loc = player.getLocation();
        sb.append("Position: ").append(String.format("%.2f, %.2f, %.2f", loc.getX(), loc.getY(), loc.getZ())).append("\n");
        sb.append("Yaw/Pitch: ").append(String.format("%.2f / %.2f", loc.getYaw(), loc.getPitch())).append("\n");
        sb.append("World: ").append(loc.getWorld().getName()).append("\n");
        sb.append("GameMode: ").append(player.getGameMode().name()).append("\n");
        sb.append("Flying: ").append(player.isFlying()).append("\n");
        sb.append("AllowFlight: ").append(player.getAllowFlight()).append("\n");
        sb.append("Gliding: ").append(player.isGliding()).append("\n");
        sb.append("Swimming: ").append(player.isSwimming()).append("\n");
        sb.append("Sprinting: ").append(player.isSprinting()).append("\n");
        sb.append("Sneaking: ").append(player.isSneaking()).append("\n");
        sb.append("InVehicle: ").append(player.isInsideVehicle()).append("\n");
        sb.append("OnGround: ").append(player.isOnGround()).append("\n");
        sb.append("Health: ").append(String.format("%.1f", player.getHealth())).append("\n");
        sb.append("Velocity: ").append(String.format("%.3f, %.3f, %.3f",
            player.getVelocity().getX(), player.getVelocity().getY(), player.getVelocity().getZ())).append("\n");

        sb.append("\n");
        sb.append("--- Movement Data ---\n");
        sb.append("DeltaX: ").append(String.format("%.4f", user.getDeltaX())).append("\n");
        sb.append("DeltaY: ").append(String.format("%.4f", user.getDeltaY())).append("\n");
        sb.append("DeltaZ: ").append(String.format("%.4f", user.getDeltaZ())).append("\n");
        sb.append("DeltaXZ: ").append(String.format("%.4f", user.getDeltaXZ())).append("\n");
        sb.append("DeltaYaw: ").append(String.format("%.4f", user.getDeltaYaw())).append("\n");
        sb.append("DeltaPitch: ").append(String.format("%.4f", user.getDeltaPitch())).append("\n");

        sb.append("\n");
        sb.append("--- Environment ---\n");
        sb.append("Block at feet: ").append(loc.getBlock().getType().name()).append("\n");
        sb.append("Block below: ").append(loc.clone().add(0, -1, 0).getBlock().getType().name()).append("\n");
        sb.append("Block at head: ").append(loc.clone().add(0, 1.5, 0).getBlock().getType().name()).append("\n");
        sb.append("TPS: ").append(String.format("%.1f", GAC.getTPS())).append("\n");
        sb.append("Ping: ").append(player.getPing()).append("ms\n");

        sb.append("==========================================\n");

        writeToFile(sb.toString());
    }

    /**
     * Log a kick event with full details.
     */
    public static void logKick(GACUser user, String checkName, String checkId, String reason) {
        if (logFile == null) init();

        Player player = user.getPlayer();
        if (player == null) return;

        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append("================== KICK ==================\n");
        sb.append("Time: ").append(dateFormat.format(new Date())).append("\n");
        sb.append("Player: ").append(player.getName()).append("\n");
        sb.append("Check: ").append(checkName).append(" (").append(checkId).append(")\n");
        sb.append("Reason: ").append(reason).append("\n");

        Location loc = player.getLocation();
        sb.append("\n");
        sb.append("--- Last Known State ---\n");
        sb.append("Position: ").append(String.format("%.2f, %.2f, %.2f", loc.getX(), loc.getY(), loc.getZ())).append("\n");
        sb.append("World: ").append(loc.getWorld().getName()).append("\n");
        sb.append("GameMode: ").append(player.getGameMode().name()).append("\n");
        sb.append("Flying: ").append(player.isFlying()).append("\n");
        sb.append("Gliding: ").append(player.isGliding()).append("\n");
        sb.append("InVehicle: ").append(player.isInsideVehicle()).append("\n");
        sb.append("TPS: ").append(String.format("%.1f", GAC.getTPS())).append("\n");
        sb.append("Ping: ").append(player.getPing()).append("ms\n");
        sb.append("==========================================\n");

        writeToFile(sb.toString());
    }

    private static synchronized void writeToFile(String content) {
        try (PrintWriter writer = new PrintWriter(new FileWriter(logFile, true))) {
            writer.print(content);
        } catch (Exception e) {
            GAC.getInstance().getLogger().warning("Failed to write to log file: " + e.getMessage());
        }
    }
}
