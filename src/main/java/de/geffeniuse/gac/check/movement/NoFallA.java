package de.geffeniuse.gac.check.movement;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketEvent;
import de.geffeniuse.gac.GAC;
import de.geffeniuse.gac.check.Check;
import de.geffeniuse.gac.data.GACUser;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

/**
 * NoFallA - NoFall Hack Detection
 * Multiple detection methods:
 * 1. Packet onGround spoofing
 * 2. Missing fall damage events
 * 3. Ground claim while in air
 */
public class NoFallA extends Check implements Listener {

    // Tracking
    private double fallStartY = 0;
    private double highestY = 0;
    private boolean tracking = false;
    private int fallingTicks = 0;
    private long lastDamageTime = 0;
    private long lastMaceHit = 0;        // When mace actually HIT something
    private long fallStartTime = 0;       // When fall started
    private int groundSpoofCount = 0;
    private int violations = 0;

    // Last known states
    private boolean lastServerGround = true;
    private double lastY = 0;
    private int consecutiveFallTicks = 0;

    private static final double MIN_FALL_DAMAGE = 5.0; // Only check falls > 5 blocks (more reliable)
    private static final int KICK_THRESHOLD = 8;

    public NoFallA(GACUser user) {
        super(user, "NoFall", "Detects nofall hacks.");
        Bukkit.getPluginManager().registerEvents(this, GAC.getInstance());
    }

    @Override
    public void onPacket(PacketEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.POSITION &&
            event.getPacketType() != PacketType.Play.Client.POSITION_LOOK) {
            return;
        }

        Player player = user.getPlayer();
        if (player == null) return;
        
        // Disable entirely when fall damage gamerule is off
        Boolean fallDamage = player.getWorld().getGameRuleValue(org.bukkit.GameRule.FALL_DAMAGE);
        if (fallDamage != null && !fallDamage) {
            reset();
            return;
        }

        // Skip checks
        if (player.isFlying() || player.getAllowFlight() ||
            player.getGameMode() == GameMode.CREATIVE ||
            player.getGameMode() == GameMode.SPECTATOR ||
            player.isInsideVehicle() || player.isGliding() ||
            player.isSwimming()) {
            reset();
            return;
        }

        double currentY = user.getLastY();
        double deltaY = user.getDeltaY();

        // Read client's ground claim from packet
        boolean clientGround;
        try {
            clientGround = event.getPacket().getBooleans().read(0);
        } catch (Exception e) {
            return;
        }

        // Schedule server-side check
        Bukkit.getScheduler().runTask(GAC.getInstance(), () -> {
            if (player == null || !player.isOnline() || !isEnabled()) return;

            boolean serverGround = isOnGround(player);
            boolean inLiquid = isInLiquid(player);
            boolean onClimbable = isOnClimbable(player);
            boolean onSafeBlock = isOnSafeBlock(player); // Slime, Hay, Bed... 

            if (inLiquid || onClimbable || onSafeBlock) {
                reset();
                return;
            }

            // ============ CHECK 1: Ground Spoofing ============ 
            // Client claims ground but server says no
            if (clientGround && !serverGround && deltaY < -0.1) {
                groundSpoofCount++;

                if (groundSpoofCount >= 3) {
                    violations++;
                    if (violations >= 2) {
                        fail("groundSpoof x" + groundSpoofCount);
                        groundSpoofCount = 0;

                        if (getViolationLevel() >= KICK_THRESHOLD) {
                            kick(player);
                        }
                    }
                }
            } else if (serverGround) {
                groundSpoofCount = Math.max(0, groundSpoofCount - 1);
            }

            // ============ CHECK 2: Fall Tracking ============ 
            // Track when falling
            if (deltaY < -0.1 && !serverGround) {
                consecutiveFallTicks++;

                if (!tracking) {
                    tracking = true;
                    fallStartY = currentY;
                    highestY = currentY;
                    fallingTicks = 0;
                    fallStartTime = System.currentTimeMillis(); // Track when fall started
                }

                fallingTicks++;

                if (currentY > highestY) {
                    highestY = currentY;
                }
            }

            // ============ CHECK 3: Landing Without Damage ============ 
            if (tracking && serverGround) {
                double fallDistance = highestY - currentY;
                final long myFallStartTime = fallStartTime;

                if (fallDistance >= MIN_FALL_DAMAGE) {
                    long now = System.currentTimeMillis();

                    // Wait a bit for damage event
                    Bukkit.getScheduler().runTaskLater(GAC.getInstance(), () -> {
                        // Check if mace attack happened DURING THIS FALL
                        boolean maceHitDuringFall = lastMaceHit > myFallStartTime &&
                                                    (System.currentTimeMillis() - lastMaceHit) < 1000;

                        if (maceHitDuringFall) {
                            violations = Math.max(0, violations - 1);
                            return;
                        }

                        long timeSinceDamage = System.currentTimeMillis() - lastDamageTime;

                        // If no damage was taken within 200ms of landing
                        if (timeSinceDamage > 200) {
                            // EXTRA SAFETY CHECK: Check if they are standing on a block NOW (MLG clutch)
                            // or if they were near a safe block recently.
                            if (isOnSafeBlock(player)) {
                                return; // Landed on slime/hay
                            }
                            
                            violations++;

                            if (violations >= 2) {
                                fail(String.format("noDamage fall=%.1f", fallDistance));
                                violations = 0;

                                if (getViolationLevel() >= KICK_THRESHOLD) {
                                    kick(player);
                                }
                            }
                        } else {
                            // Took damage correctly - decay
                            violations = Math.max(0, violations - 1);
                        }
                    }, 5L); // 5 ticks = 250ms delay
                }

                reset();
            }

            // ============ CHECK 4: Impossible Ground While Falling Fast ============ 
            if (clientGround && consecutiveFallTicks > 10 && deltaY < -0.5) {
                violations++;
                if (violations >= 2) {
                    fail("fastFallGround deltaY=" + String.format("%.2f", deltaY));

                    if (getViolationLevel() >= KICK_THRESHOLD) {
                        kick(player);
                    }
                }
            }

            // Reset consecutive fall if on ground
            if (serverGround) {
                consecutiveFallTicks = 0;
            }

            lastServerGround = serverGround;
            lastY = currentY;
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCombat(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player)) return;
        Player player = (Player) event.getDamager();
        if (!player.getUniqueId().equals(user.getUuid())) return;

        // Check for Mace HIT (damage actually dealt)
        String item = player.getInventory().getItemInMainHand().getType().name();
        if (item.contains("MACE") && !event.isCancelled() && event.getDamage() > 0) {
            // Mace actually hit and dealt damage - this is a legitimate mace smash
            lastMaceHit = System.currentTimeMillis();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player player = (Player) event.getEntity();

        if (!player.getUniqueId().equals(user.getUuid())) return;

        if (event.getCause() == EntityDamageEvent.DamageCause.FALL) {
            lastDamageTime = System.currentTimeMillis();
            violations = Math.max(0, violations - 1);
            groundSpoofCount = 0;
        }
    }

    private void reset() {
        tracking = false;
        fallStartY = 0;
        highestY = 0;
        fallingTicks = 0;
        consecutiveFallTicks = 0;
    }

    private void kick(Player player) {
        GAC.incrementKicks();
        player.kickPlayer("§b§lGAC \n\n§7Unfair Advantage detected.\n§fCheck: §bNoFall");
    }

    private boolean isOnGround(Player player) {
        // Check wider area
        for (double x = -0.4; x <= 0.4; x += 0.4) {
            for (double z = -0.4; z <= 0.4; z += 0.4) {
                if (player.getLocation().clone().add(x, -0.1, z).getBlock().getType().isSolid()) {
                    return true;
                }
            }
        }
        
        // Trust client if close
        if (player.isOnGround()) {
             for (double x = -0.5; x <= 0.5; x += 0.5) {
                for (double z = -0.5; z <= 0.5; z += 0.5) {
                    if (player.getLocation().clone().add(x, -0.5, z).getBlock().getType().isSolid()) {
                        return true;
                    }
                }
            }
        }
        
        return false;
    }
    
    // Check for blocks that reduce/negate fall damage
    private boolean isOnSafeBlock(Player player) {
        for (double x = -0.3; x <= 0.3; x += 0.3) {
            for (double z = -0.3; z <= 0.3; z += 0.3) {
                Block block = player.getLocation().clone().add(x, -0.5, z).getBlock(); // Check slightly below feet
                String name = block.getType().name();
                
                if (name.contains("SLIME") || 
                    name.contains("HAY") || 
                    name.contains("BED") || 
                    name.contains("HONEY") ||
                    name.contains("WEB") ||
                    name.contains("BERRY") ||
                    name.contains("POWDER_SNOW") ||
                    name.contains("SCAFFOLDING") ||
                    name.contains("VINE") ||
                    name.contains("LADDER") ||
                    name.contains("WATER") ||
                    name.contains("LAVA")) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isInLiquid(Player player) {
        Material block = player.getLocation().getBlock().getType();
        Material below = player.getLocation().clone().add(0, -0.5, 0).getBlock().getType();
        return block == Material.WATER || block == Material.LAVA ||
               below == Material.WATER || below == Material.LAVA ||
               block.name().contains("WATER");
    }

    private boolean isOnClimbable(Player player) {
        String name = player.getLocation().getBlock().getType().name();
        return name.contains("LADDER") || name.contains("VINE") ||
               name.contains("SCAFFOLDING") || name.contains("WEEPING") ||
               name.contains("TWISTING");
    }
}
