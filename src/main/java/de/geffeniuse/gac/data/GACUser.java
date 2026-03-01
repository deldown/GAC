package de.geffeniuse.gac.data;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketEvent;
import de.geffeniuse.gac.GAC;
import de.geffeniuse.gac.check.Check;
import de.geffeniuse.gac.cloud.BehaviorCollector;
import de.geffeniuse.gac.ml.LocalMLAnalyzer;
import de.geffeniuse.gac.mitigation.MitigationManager;
import de.geffeniuse.gac.transaction.TransactionManager;
import de.geffeniuse.gac.util.PacketLocation;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;

public class GACUser {

    @Getter private final Player player;
    @Getter private final UUID uuid;
    private final List<Check> checks;

    @Getter private final Deque<PacketLocation> locationHistory = new ConcurrentLinkedDeque<>();

    // Client brand (detected from CUSTOM_PAYLOAD)
    @Getter private String clientBrand = "Unknown";

    public void setClientBrand(String brand) {
        this.clientBrand = brand;
        String message = "§b§lGAC §8» §e" + player.getName() + " §7joined using §f" + brand;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("gac.alerts")) p.sendMessage(message);
        }
    }

    // Grace Periods
    @Getter @lombok.Setter private long lastTeleportTime = 0;
    @Getter @lombok.Setter private long lastVelocityTime = 0;
    @Getter @lombok.Setter private long lastGamemodeChange = 0;

    // Velocity Prediction
    @Getter private Vector serverVelocity = null;
    @Getter @lombok.Setter private int velocityTicks = 0;

    public boolean isTeleporting() { return System.currentTimeMillis() - lastTeleportTime < 1000; }
    public boolean isTakingVelocity() { return System.currentTimeMillis() - lastVelocityTime < 1000; }

    // Rotation Tracking
    @Getter private float lastYaw, lastPitch;
    @Getter private float deltaYaw, deltaPitch;
    @Getter private float lastDeltaYaw, lastDeltaPitch;
    @Getter private long lastFlyingTime;

    // Position Tracking
    @Getter private double lastX, lastY, lastZ;
    @Getter private double deltaX, deltaY, deltaZ;
    @Getter private double deltaXZ;

    // Mitigation & Transaction
    @Getter private final MitigationManager mitigation;
    @Getter private final TransactionManager transaction;

    // Local ML (no cloud)
    @Getter private final BehaviorCollector behaviorCollector;
    @Getter private final LocalMLAnalyzer localML;

    // Violation tracking
    private long lastViolationTime = 0;
    @Getter private int totalViolations = 0;

    // Blatant detection state
    @Getter private int airTicks = 0;
    @Getter private int groundTicks = 0;
    private int packetCount = 0;
    private int swingCount = 0;
    private int placeCount = 0;
    private long lastSecond = System.currentTimeMillis();
    @Getter private int cps = 0;
    @Getter private int pps = 0;

    // Fly / Speed violation counters (for blatant detection)
    private int flyViolations = 0;
    private int speedViolations = 0;

    public GACUser(Player player) {
        this.player = player;
        this.uuid = player.getUniqueId();
        this.checks = new ArrayList<>();

        this.lastYaw = player.getLocation().getYaw();
        this.lastPitch = player.getLocation().getPitch();
        this.lastX = player.getLocation().getX();
        this.lastY = player.getLocation().getY();
        this.lastZ = player.getLocation().getZ();
        this.lastFlyingTime = System.currentTimeMillis();

        this.mitigation = new MitigationManager(this);
        this.transaction = new TransactionManager(this);
        this.transaction.startTracking();

        this.behaviorCollector = new BehaviorCollector(this);
        this.localML = new LocalMLAnalyzer(this);

        // BehaviorCollector tick + LocalML tick (every second)
        Bukkit.getScheduler().runTaskTimer(GAC.getInstance(), () -> {
            if (player.isOnline()) {
                behaviorCollector.tick();
                localML.tick(behaviorCollector.getRecentSamples(60));
            }
        }, 20L, 20L);

        registerChecks();
    }

    private void registerChecks() {
        checks.add(new de.geffeniuse.gac.check.combat.ReachA(this));
        checks.add(new de.geffeniuse.gac.check.combat.KillauraA(this));
        checks.add(new de.geffeniuse.gac.check.combat.KillauraB(this));
        checks.add(new de.geffeniuse.gac.check.combat.KillauraC(this));
        checks.add(new de.geffeniuse.gac.check.combat.KillauraD(this));
        checks.add(new de.geffeniuse.gac.check.combat.KillauraE(this));
        checks.add(new de.geffeniuse.gac.check.combat.KillauraF(this));
        checks.add(new de.geffeniuse.gac.check.combat.KillauraG(this));
        checks.add(new de.geffeniuse.gac.check.combat.KillauraH(this));
        checks.add(new de.geffeniuse.gac.check.combat.KillauraI(this));
        checks.add(new de.geffeniuse.gac.check.combat.KillauraK(this));
        checks.add(new de.geffeniuse.gac.check.combat.KillauraL(this));
        checks.add(new de.geffeniuse.gac.check.combat.KillauraTrap(this));
        checks.add(new de.geffeniuse.gac.check.combat.AimA(this));
        checks.add(new de.geffeniuse.gac.check.combat.AimB(this));
        checks.add(new de.geffeniuse.gac.check.combat.AimC(this));
        checks.add(new de.geffeniuse.gac.check.combat.AutoClickerA(this));
        checks.add(new de.geffeniuse.gac.check.movement.BadPacketsA(this));
        checks.add(new de.geffeniuse.gac.check.movement.VelocityA(this));
        checks.add(new de.geffeniuse.gac.check.movement.VelocityB(this));
        checks.add(new de.geffeniuse.gac.check.movement.FlyA(this));
        checks.add(new de.geffeniuse.gac.check.movement.FlyB(this));
        checks.add(new de.geffeniuse.gac.check.movement.FlyC(this));
        checks.add(new de.geffeniuse.gac.check.movement.SpeedA(this));
        checks.add(new de.geffeniuse.gac.check.movement.NoFallA(this));
        checks.add(new de.geffeniuse.gac.check.movement.TimerA(this));
        checks.add(new de.geffeniuse.gac.check.world.XrayStatsA(this));
        checks.add(new de.geffeniuse.gac.check.world.XrayBaitA(this));
        checks.add(new de.geffeniuse.gac.check.world.ScaffoldA(this));
        checks.add(new de.geffeniuse.gac.check.world.ScaffoldB(this));
        checks.add(new de.geffeniuse.gac.check.world.FastBreakA(this));
        checks.add(new de.geffeniuse.gac.check.movement.PhaseA(this));
        checks.add(new de.geffeniuse.gac.check.movement.JesusA(this));
        checks.add(new de.geffeniuse.gac.check.movement.StepA(this));
        checks.add(new de.geffeniuse.gac.check.movement.SpiderA(this));
        checks.add(new de.geffeniuse.gac.check.movement.BlinkA(this));
        checks.add(new de.geffeniuse.gac.check.combat.CriticalsA(this));
        checks.add(new de.geffeniuse.gac.check.player.ChestStealerA(this));
        checks.add(new de.geffeniuse.gac.check.player.InventoryMoveA(this));
        checks.add(new de.geffeniuse.gac.check.player.ClientSpoofA(this));
        checks.add(new de.geffeniuse.gac.check.world.ExploitA(this));
        checks.add(new de.geffeniuse.gac.check.movement.CrasherA(this));
        checks.add(new de.geffeniuse.gac.check.movement.CrasherB(this));
        checks.add(new de.geffeniuse.gac.check.movement.CrasherC(this));
        checks.add(new de.geffeniuse.gac.check.movement.VehicleA(this));
        checks.add(new de.geffeniuse.gac.check.world.ExploitB(this));
        checks.add(new de.geffeniuse.gac.check.world.ExploitC(this));
        checks.add(new de.geffeniuse.gac.check.world.ExploitD(this));
        checks.add(new de.geffeniuse.gac.check.world.ExploitE(this));
        checks.add(new de.geffeniuse.gac.check.world.ExploitF(this));
        checks.add(new de.geffeniuse.gac.check.world.ExploitG(this));
        checks.add(new de.geffeniuse.gac.check.movement.CrasherD(this));
        checks.add(new de.geffeniuse.gac.check.movement.TeleportA(this));
        checks.add(new de.geffeniuse.gac.check.movement.ElytraA(this));
        checks.add(new de.geffeniuse.gac.check.movement.AntiHungerA(this));
        checks.add(new de.geffeniuse.gac.check.movement.NoSlowA(this));
        checks.add(new de.geffeniuse.gac.check.movement.NoWebA(this));
        checks.add(new de.geffeniuse.gac.check.world.ScaffoldC(this));
        checks.add(new de.geffeniuse.gac.check.world.ScaffoldD(this));
        checks.add(new de.geffeniuse.gac.check.world.ScaffoldE(this));
        checks.add(new de.geffeniuse.gac.check.movement.StrafeA(this));
        checks.add(new de.geffeniuse.gac.check.movement.SimulationA(this));
    }

    /**
     * Called when a check flags this player.
     */
    public void onViolation(String checkName, int vl) {
        lastViolationTime = System.currentTimeMillis();
        totalViolations++;
        behaviorCollector.recordViolation(checkName);
    }

    public boolean isCurrentlyLegit() {
        long timeSinceJoin = System.currentTimeMillis() - lastFlyingTime;
        long timeSinceViolation = System.currentTimeMillis() - lastViolationTime;
        return lastViolationTime == 0 || timeSinceViolation >= 180000;
    }

    public void handlePacket(PacketEvent event) {
        packetCount++;
        long now = System.currentTimeMillis();
        if (now - lastSecond >= 1000) {
            cps = swingCount;
            pps = packetCount;
            swingCount = 0;
            packetCount = 0;
            placeCount = 0;
            lastSecond = now;
        }

        // === PACKET ML: Record every inbound player packet ===
        if (!event.isServerPacket()) {
            behaviorCollector.recordPacket(false, false);
        }

        if (event.getPacketType() == PacketType.Play.Client.ARM_ANIMATION) {
            swingCount++;
            behaviorCollector.recordClick();
        }

        if (event.getPacketType() == PacketType.Play.Client.BLOCK_PLACE) {
            placeCount++;
        }

        // PONG (transaction tracking)
        if (event.getPacketType() == PacketType.Play.Client.PONG) {
            try {
                int id = event.getPacket().getIntegers().read(0);
                transaction.onTransactionReceive(id);
            } catch (Exception ignored) {}
            return;
        }

        // Server velocity
        if (event.getPacketType() == PacketType.Play.Server.ENTITY_VELOCITY) {
            double x = event.getPacket().getIntegers().read(1) / 8000.0;
            double y = event.getPacket().getIntegers().read(2) / 8000.0;
            double z = event.getPacket().getIntegers().read(3) / 8000.0;
            this.serverVelocity = new Vector(x, y, z);
            this.velocityTicks = 10;
            this.lastVelocityTime = System.currentTimeMillis();
            return;
        }

        // Position/rotation packets
        if (event.getPacketType() == PacketType.Play.Client.FLYING ||
            event.getPacketType() == PacketType.Play.Client.LOOK ||
            event.getPacketType() == PacketType.Play.Client.POSITION ||
            event.getPacketType() == PacketType.Play.Client.POSITION_LOOK) {
            try {
                handleFlying(event);
            } catch (Exception ignored) {}
        }

        // Brand tracking
        if (event.getPacketType() == PacketType.Play.Client.CUSTOM_PAYLOAD) {
            try {
                String channel = event.getPacket().getStrings().read(0);
                if (channel.equals("minecraft:brand") || channel.equals("MC|Brand")) {
                    Object modifier = event.getPacket().getModifier().read(1);
                    byte[] data = null;
                    if (modifier instanceof byte[]) {
                        data = (byte[]) modifier;
                    } else if (modifier != null && modifier.getClass().getSimpleName().contains("ByteBuf")) {
                        java.lang.reflect.Method readableBytes = modifier.getClass().getMethod("readableBytes");
                        java.lang.reflect.Method readBytes = modifier.getClass().getMethod("readBytes", byte[].class);
                        int length = (int) readableBytes.invoke(modifier);
                        data = new byte[length];
                        readBytes.invoke(modifier, data);
                    }
                    if (data != null) {
                        String brand = new String(data, java.nio.charset.StandardCharsets.UTF_8);
                        brand = brand.replaceAll("[^a-zA-Z0-9 _\\-/.]", "").trim();
                        setClientBrand(brand);
                    }
                }
            } catch (Exception ignored) {}
        }

        // Skip disabled gamemodes
        if (player.getGameMode() == org.bukkit.GameMode.SPECTATOR ||
            System.currentTimeMillis() - lastGamemodeChange < 5000) {
            return;
        }

        // === FIX: Only call onPacket() for ENABLED checks ===
        for (Check check : checks) {
            if (!check.isEnabled()) continue;
            try {
                check.onPacket(event);
            } catch (Exception ignored) {}
        }
    }

    private void handleFlying(PacketEvent event) {
        long now = System.currentTimeMillis();
        if (now - lastFlyingTime < 1) return;
        lastFlyingTime = now;

        Player player = this.player;
        if (player == null) return;

        com.comphenix.protocol.events.PacketContainer packet = event.getPacket();
        boolean packetOnGround = packet.getBooleans().read(0);

        // Position tracking
        if (event.getPacketType() == PacketType.Play.Client.POSITION ||
            event.getPacketType() == PacketType.Play.Client.POSITION_LOOK) {
            double x = packet.getDoubles().read(0);
            double y = packet.getDoubles().read(1);
            double z = packet.getDoubles().read(2);

            this.deltaX = x - lastX;
            this.deltaY = y - lastY;
            this.deltaZ = z - lastZ;
            this.deltaXZ = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);

            this.lastX = x;
            this.lastY = y;
            this.lastZ = z;

            locationHistory.addFirst(new PacketLocation(x, y, z, now));
            if (locationHistory.size() > 40) locationHistory.removeLast();

            if (packetOnGround) {
                groundTicks++;
                airTicks = 0;
            } else {
                airTicks++;
                groundTicks = 0;
            }

            // === BLATANT FLY DETECTION ===
            boolean exempt = player.isFlying() || player.getAllowFlight() ||
                player.getGameMode() == org.bukkit.GameMode.CREATIVE ||
                player.getGameMode() == org.bukkit.GameMode.SPECTATOR ||
                player.isGliding() || player.isInsideVehicle() ||
                player.isSwimming() || player.isClimbing() || player.isRiptiding() ||
                player.hasPotionEffect(org.bukkit.potion.PotionEffectType.JUMP_BOOST) ||
                player.hasPotionEffect(org.bukkit.potion.PotionEffectType.LEVITATION) ||
                player.hasPotionEffect(org.bukkit.potion.PotionEffectType.SLOW_FALLING) ||
                isTakingVelocity() || isTeleporting() ||
                GAC.getTPS() < 18.0;

            if (exempt) {
                airTicks = 0;
                // Decay flyViolations when player is doing something legitimate
                if (flyViolations > 0) flyViolations--;
            } else if (airTicks >= 35) {
                // Require the player to be actively moving (not just standing on a slab/stair
                // that doesn't register onGround=true). A legitimate fly hack moves horizontally
                // or upward while in the air. deltaY=0 + deltaXZ≈0 = likely on an undetected surface.
                boolean movingUp        = deltaY > 0.05;
                boolean horizontalFly   = Math.abs(deltaY) < 0.31 && deltaXZ > 0.2;

                if (movingUp || horizontalFly) {
                    GAC.incrementFlags();
                    flyViolations++;

                    String msg = "§b§lGAC §8» §c" + player.getName() +
                        " §7failed §bFly §8(§fairTicks=" + airTicks +
                        " deltaY=" + String.format("%.2f", deltaY) +
                        " dXZ=" + String.format("%.2f", deltaXZ) + "§8) §7VL: §c" + flyViolations;
                    GAC.getInstance().getLogger().warning(msg);
                    GAC.getInstance().getServer().getOnlinePlayers().stream()
                        .filter(p -> p.hasPermission("gac.alerts"))
                        .forEach(p -> p.sendMessage(msg));

                    if (flyViolations >= 8) {
                        Bukkit.getScheduler().runTask(GAC.getInstance(), () -> {
                            if (player.isOnline()) {
                                GAC.incrementKicks();
                                player.kickPlayer("§b§lGAC \n\n§7Unfair Advantage detected.\n§fCheck: §bFly");
                            }
                        });
                        flyViolations = 0;
                    }
                    airTicks = 0;
                } else if (deltaY < -0.3) {
                    // Player is actually falling fast — reset to avoid overflow
                    airTicks = 0;
                }
            }
        } else {
            // LOOK-only packet: update groundTicks for ground detection,
            // but do NOT increment airTicks — deltaY is stale from the last POSITION packet
            // and counting LOOK packets inflates airTicks without real movement data.
            if (packetOnGround) {
                groundTicks++;
                airTicks = 0;
            }
            // else: don't touch airTicks for LOOK-only packets
        }

        // Rotation
        if (event.getPacketType() == PacketType.Play.Client.LOOK ||
            event.getPacketType() == PacketType.Play.Client.POSITION_LOOK) {
            float currentYaw = packet.getFloat().read(0);
            float currentPitch = packet.getFloat().read(1);

            float dYaw = Math.abs(currentYaw - lastYaw);
            float dPitch = Math.abs(currentPitch - lastPitch);
            if (dYaw > 180) dYaw = 360 - dYaw;

            this.lastDeltaYaw = this.deltaYaw;
            this.lastDeltaPitch = this.deltaPitch;
            this.deltaYaw = dYaw;
            this.deltaPitch = dPitch;
            this.lastYaw = currentYaw;
            this.lastPitch = currentPitch;

            behaviorCollector.recordRotation(deltaYaw, deltaPitch);
        }

        behaviorCollector.recordMovement(deltaXZ, deltaY, packetOnGround);
    }

    private boolean isActuallyOnGround(Player player, double x, double y, double z) {
        org.bukkit.World world = player.getWorld();
        for (double dx = -0.3; dx <= 0.3; dx += 0.3) {
            for (double dz = -0.3; dz <= 0.3; dz += 0.3) {
                org.bukkit.block.Block block = world.getBlockAt(
                    (int) Math.floor(x + dx), (int) Math.floor(y - 0.1), (int) Math.floor(z + dz));
                if (block.getType().isSolid()) return true;
            }
        }
        return false;
    }

    public void resetData() {
        this.locationHistory.clear();
        this.deltaX = 0;
        this.deltaY = 0;
        this.deltaZ = 0;
        this.deltaXZ = 0;
        this.velocityTicks = 0;
        this.lastTeleportTime = System.currentTimeMillis();
        for (Check check : checks) {
            if (check instanceof de.geffeniuse.gac.check.movement.BlinkA) {
                ((de.geffeniuse.gac.check.movement.BlinkA) check).reset();
            }
        }
    }
}
