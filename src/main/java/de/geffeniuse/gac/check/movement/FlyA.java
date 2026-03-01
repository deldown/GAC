package de.geffeniuse.gac.check.movement;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketEvent;
import de.geffeniuse.gac.GAC;
import de.geffeniuse.gac.check.Check;
import de.geffeniuse.gac.data.GACUser;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

public class FlyA extends Check implements Listener {

    private int airTicks = 0;
    private int groundTicks = 0;
    private int suspicion = 0;
    private double lastY = 0;
    private boolean wasOnGround = true;
    private double highestY = 0;
    private double startY = 0;
    private long lastMaceHit = 0;

    private static final int MAX_AIR_TICKS = 20;
    private static final double MIN_FALL_SPEED = -0.08;
    private static final int KICK_THRESHOLD = 6;
    private static final int SUSPICION_THRESHOLD = 6; // Needs multiple bad ticks before flagging

    public FlyA(GACUser user) {
        super(user, "Fly", "Detects flight hacks.");
        Bukkit.getPluginManager().registerEvents(this, GAC.getInstance());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCombat(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player)) return;
        Player player = (Player) event.getDamager();
        if (!player.getUniqueId().equals(user.getUuid())) return;

        String item = player.getInventory().getItemInMainHand().getType().name();
        if (item.contains("MACE") && !event.isCancelled() && event.getDamage() > 0) {
            lastMaceHit = System.currentTimeMillis();
            airTicks = 0;
            suspicion = 0;
        }
    }

    @Override
    public void onPacket(PacketEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.POSITION &&
            event.getPacketType() != PacketType.Play.Client.POSITION_LOOK) {
            return;
        }

        if (GAC.getTPS() < 18.0) return;
        if (user.isTeleporting() || user.isTakingVelocity()) return;

        Player player = user.getPlayer();
        if (player == null) return;

        if (player.getAllowFlight() || player.isFlying() ||
            player.getGameMode() == GameMode.CREATIVE ||
            player.getGameMode() == GameMode.SPECTATOR ||
            player.isInsideVehicle() || player.isSwimming() ||
            player.isClimbing() || player.isGliding() || player.isRiptiding() ||
            player.hasPotionEffect(org.bukkit.potion.PotionEffectType.JUMP_BOOST) ||
            player.hasPotionEffect(org.bukkit.potion.PotionEffectType.LEVITATION) ||
            player.hasPotionEffect(org.bukkit.potion.PotionEffectType.SLOW_FALLING) ||
            isInSpecialBlock(player)) {
            airTicks = 0;
            return;
        }

        long timeSinceMaceHit = System.currentTimeMillis() - lastMaceHit;
        if (timeSinceMaceHit < 2000) {
            airTicks = 0;
            return;
        }

        double currentY = user.getLastY();
        double deltaY = user.getDeltaY();

        Bukkit.getScheduler().runTask(GAC.getInstance(), () -> {
            if (player == null || !player.isOnline()) return;

            boolean onGround = isOnGround(player);
            boolean inLiquid = isInLiquid(player);
            boolean nearClimbable = isNearClimbable(player);

            if (inLiquid || nearClimbable) {
                airTicks = 0;
                wasOnGround = true;
                return;
            }

            // === BLATANT FLY INSTANT FLAG ===
            // 40 ticks in air (2 seconds) without falling properly = 100% fly
            if (airTicks > 40 && deltaY > -0.5) {
                flag("BLATANT FLY (airTicks=" + airTicks + " deltaY=" + String.format("%.2f", deltaY) + ")");
                airTicks = 0;
                return;
            }

            // 20 ticks with near-zero deltaY = hovering
            if (airTicks > 20 && Math.abs(deltaY) < 0.1) {
                flag("HOVER (airTicks=" + airTicks + " deltaY=" + String.format("%.3f", deltaY) + ")");
                airTicks = 0;
                return;
            }

            // Rising in air after initial jump = fly
            if (airTicks > 15 && deltaY > 0.1) {
                flag("RISING (airTicks=" + airTicks + " deltaY=" + String.format("%.2f", deltaY) + ")");
                airTicks = 0;
                return;
            }

            if (onGround) {
                if (airTicks > 0) {
                    double totalDrop = startY - currentY;
                    if (airTicks > 15 && totalDrop < 1.0 && highestY > startY + 0.5) {
                        suspicion += 2;
                        if (suspicion >= SUSPICION_THRESHOLD) {
                            flag("glide (airTicks=" + airTicks + " drop=" + String.format("%.1f", totalDrop) + ")");
                        }
                    }
                }
                airTicks = 0;
                groundTicks++;
                wasOnGround = true;
                highestY = currentY;
                startY = currentY;
            } else {
                groundTicks = 0;
                if (airTicks == 0) {
                    startY = currentY;
                    highestY = currentY;
                }
                airTicks++;

                if (currentY > highestY) {
                    highestY = currentY;
                }

                // Only flag truly blatant hover: 25+ ticks in air, nearly no Y movement
                // Normal jump peaks at ~tick 6 with deltaY ≈ 0, so we need much higher threshold
                if (airTicks > 25 && Math.abs(deltaY) < 0.02) {
                    suspicion += 2;
                    if (suspicion >= SUSPICION_THRESHOLD) {
                        flag("hover airTicks=" + airTicks + " deltaY=" + String.format("%.3f", deltaY));
                    }
                }

                wasOnGround = false;
            }

            // Don't decay suspicion on ground - let it accumulate
            // Only decay slowly over time
            if (onGround && suspicion > 0 && groundTicks > 100) {
                suspicion--;  // Only after 5 seconds on ground
            }

            if (onGround) groundTicks++;
            else groundTicks = 0;

            lastY = currentY;
        });
    }

    private void flag(String info) {
        fail(info);
        suspicion = 0;

        if (getViolationLevel() >= KICK_THRESHOLD) {
            GAC.incrementKicks();
            Bukkit.getScheduler().runTask(GAC.getInstance(), () -> {
                if (user.getPlayer() != null && user.getPlayer().isOnline()) {
                    user.getPlayer().kickPlayer("§b§lGAC \n\n§7Unfair Advantage detected.\n§fCheck: §bFly");
                }
            });
        }
    }

    private boolean isOnGround(Player player) {
        Location loc = player.getLocation();
        for (double x = -0.3; x <= 0.3; x += 0.3) {
            for (double z = -0.3; z <= 0.3; z += 0.3) {
                if (loc.clone().add(x, -0.5, z).getBlock().getType().isSolid()) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isInLiquid(Player player) {
        Location loc = player.getLocation();
        Material feet = loc.getBlock().getType();
        Material body = loc.clone().add(0, 1, 0).getBlock().getType();
        return feet == Material.WATER || feet == Material.LAVA ||
               body == Material.WATER || body == Material.LAVA ||
               feet.name().contains("WATER") || feet.name().contains("LAVA");
    }

    private boolean isInSpecialBlock(Player player) {
        org.bukkit.Location loc = player.getLocation();
        org.bukkit.Location[] positions = {loc, loc.clone().add(0, 0.5, 0), loc.clone().add(0, 1.0, 0)};
        for (org.bukkit.Location pos : positions) {
            Material m = pos.getBlock().getType();
            if (m == Material.COBWEB || m == Material.POWDER_SNOW ||
                m == Material.HONEY_BLOCK || m == Material.SLIME_BLOCK ||
                m == Material.WATER || m == Material.LAVA ||
                m.name().contains("BERRY")) return true;
        }
        return false;
    }

    private boolean isNearClimbable(Player player) {
        Location loc = player.getLocation();
        for (double x = -0.5; x <= 0.5; x += 0.5) {
            for (double z = -0.5; z <= 0.5; z += 0.5) {
                String name = loc.clone().add(x, 0, z).getBlock().getType().name();
                if (name.contains("LADDER") || name.contains("VINE") ||
                    name.contains("SCAFFOLDING") || name.contains("WEEPING") ||
                    name.contains("TWISTING") || name.contains("CAVE_VINES")) {
                    return true;
                }
            }
        }
        return false;
    }
}
