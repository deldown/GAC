package de.geffeniuse.gac.simulation;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * MovementSimulator - Simulates Minecraft player movement physics
 * High-fidelity prediction engine inspired by GrimAC.
 */
public class MovementSimulator {

    // Minecraft Physics Constants (Modern)
    public static final double GRAVITY = 0.08;
    public static final double DRAG = 0.98;
    public static final double DRAG_FLYING = 0.91; // Air friction
    public static final double DRAG_LIQUID = 0.8;
    public static final double LAVA_DRAG = 0.5;
    
    // Friction
    public static final double GROUND_FRICTION = 0.6;
    public static final double SLIPPERY_FRICTION = 0.98; // Ice
    public static final double SOUL_SAND_FRICTION = 0.4;
    public static final double SLIME_FRICTION = 0.8;

    // Movement Speeds
    public static final double WALK_SPEED = 0.1; // Base movement speed on ground (approx 4.3 m/s)
    public static final double SPRINT_MULTIPLIER = 1.3;
    public static final double SNEAK_MULTIPLIER = 0.3;
    public static final double JUMP_VELOCITY = 0.42;
    public static final double JUMP_VELOCITY_SPRINT = 0.2; // Horizontal boost
    
    // Player Dimensions
    private static final double PLAYER_WIDTH = 0.6;
    private static final double PLAYER_HEIGHT = 1.8;

    // State
    private double x, y, z;
    private double velX, velY, velZ;
    private boolean onGround;
    private boolean inWater;
    private boolean inLava;
    private boolean isClimbing;
    private float yaw, pitch;
    
    private World world;
    private BoundingBox playerBox;

    public MovementSimulator() {
        reset();
    }

    public void reset() {
        this.x = 0; this.y = 0; this.z = 0;
        this.velX = 0; this.velY = 0; this.velZ = 0;
        this.onGround = false;
        this.inWater = false;
        this.inLava = false;
        this.isClimbing = false;
        this.playerBox = new BoundingBox(0, 0, 0, 0, 0, 0);
    }

    /**
     * Sync simulator state with actual player
     */
    public void syncWithPlayer(Player player) {
        Location loc = player.getLocation();
        this.x = loc.getX();
        this.y = loc.getY();
        this.z = loc.getZ();
        this.yaw = loc.getYaw();
        this.pitch = loc.getPitch();
        this.world = player.getWorld();

        Vector vel = player.getVelocity();
        this.velX = vel.getX();
        this.velY = vel.getY();
        this.velZ = vel.getZ();

        updateBoundingBox();
        checkEnvironment();
    }

    /**
     * Simulate one tick of movement with high precision
     */
    public SimulatedPosition simulateTick(float forward, float strafe, boolean jump,
                                          boolean sprint, boolean sneak, Player player) {
        
        // 1. Calculate Base Attributes
        double friction = getFriction();
        double speed = getAttributeSpeed(player, sprint, sneak);
        
        // 2. Handle Jumping (Pre-move)
        if (jump && onGround) {
            handleJump(player, sprint, yaw);
        }

        // 3. Apply Movement Input
        applyMovementInput(forward, strafe, speed, friction, sprint);

        // 4. Apply Physics (Gravity, Drag, Effects)
        applyPhysics(player, jump);

        // 5. Collision & Movement
        moveEntity();

        // 6. Post-Move Updates (Environment checks)
        checkEnvironment();
        
        return new SimulatedPosition(x, y, z, velX, velY, velZ, onGround);
    }
    
    private void handleJump(Player player, boolean sprint, float yaw) {
        double jumpVel = JUMP_VELOCITY;
        
        // Jump Boost
        if (player != null) {
            PotionEffect jump = player.getPotionEffect(PotionEffectType.JUMP_BOOST);
            if (jump != null) {
                jumpVel += (jump.getAmplifier() + 1) * 0.1;
            }
        }
        
        velY = jumpVel;
        
        if (sprint) {
            double yawRad = Math.toRadians(yaw);
            velX -= Math.sin(yawRad) * JUMP_VELOCITY_SPRINT;
            velZ += Math.cos(yawRad) * JUMP_VELOCITY_SPRINT;
        }
        
        onGround = false;
    }

    private void applyMovementInput(float forward, float strafe, double speed, double friction, boolean sprint) {
        // Minecraft movement formula:
        // dist = input * speed * (0.91 or friction factor)
        // This is a simplification; precise formula depends on state (air vs ground)
        
        double dist = strafe * strafe + forward * forward;
        if (dist >= 1.0E-4F) {
            dist = Math.sqrt(dist);
            if (dist < 1.0F) dist = 1.0F;
            
            dist = speed / dist;
            strafe *= dist;
            forward *= dist;
            
            double sin = Math.sin(Math.toRadians(yaw));
            double cos = Math.cos(Math.toRadians(yaw));
            
            double moveX = strafe * cos - forward * sin;
            double moveZ = forward * cos + strafe * sin;
            
            if (onGround) {
                // Ground movement
                // MC uses: velocity += input * (speed * (0.6 / friction^3))
                double frictionFactor = friction * friction * friction;
                double acceleration = speed * (0.16277136 / frictionFactor);
                
                velX += moveX * acceleration;
                velZ += moveZ * acceleration;
            } else {
                // Air control
                double airSpeed = 0.02; // SPRINTING IS 0.026
                if (sprint) airSpeed = 0.026; // Fix: Sprinting in air is faster
                
                if (inWater || inLava) {
                    // Fluid movement logic
                    airSpeed = 0.02; 
                    if (playerBox != null) { 
                        // ...
                    }
                }
                
                velX += moveX * (airSpeed / speed); 
                velZ += moveZ * (airSpeed / speed);
            }
        }
    }

    private void applyPhysics(Player player, boolean jumpInput) {
        // Fluid Physics (Priority over normal gravity)
        if (inWater || inLava) {
            handleFluidPhysics(player, jumpInput);
            return;
        }

        // Climbing Physics
        if (isClimbing) {
            handleClimbingPhysics(player);
            return;
        }

        // Standard Drag (Air vs Ground)
        // MC Logic:
        // 1. Apply drag (0.91 in air, friction * 0.91 on ground)
        // 2. Apply gravity (if not on ground)
        // 3. Move (handled in moveEntity)
        // 4. Apply drag again (0.98) - wait, MC does drag differently
        
        // Correct Modern MC Physics Order (EntityLiving.g -> travel):
        // 1. Apply Input (done in applyMovementInput)
        // 2. Move (moveEntity)
        // 3. Apply Gravity
        // 4. Apply Drag
        
        // BUT for prediction loop (simulateTick), we usually do:
        // 1. Input -> Velocity
        // 2. Physics (Drag/Gravity)
        // 3. Move
        
        // Let's refine the drag logic to match exact MC behavior
        double drag = 0.91; 
        if (onGround) {
            drag = getFriction() * 0.91;
        }
        
        // Vertical Physics (Gravity)
        if (!onGround) {
             double gravity = GRAVITY;
             
             if (player != null) {
                 if (player.hasPotionEffect(PotionEffectType.SLOW_FALLING)) gravity = 0.01;
                 if (player.hasPotionEffect(PotionEffectType.LEVITATION)) {
                     int amp = player.getPotionEffect(PotionEffectType.LEVITATION).getAmplifier();
                     velY += (0.05 * (amp + 1) - velY) * 0.2;
                     gravity = 0; 
                 }
             }
             velY -= gravity;
             velY *= 0.9800000190734863; // Air drag for Y is specific
        } else {
            velY = 0;
        }
        
        // Horizontal Drag
        velX *= drag;
        velZ *= drag;
    }

    private void handleClimbingPhysics(Player player) {
        // Climbing physics: No gravity, fixed speed mostly
        velX *= 0.15; // Slow horizontal movement on ladders
        velZ *= 0.15;
        
        // Vertical
        // If sneaking, stop. If jumping, go up. If nothing, go down slowly.
        // Gravity is applied but capped
        velY -= GRAVITY;
        if (velY < -0.15) velY = -0.15; // Max fall speed on ladder
    }
    
    private void handleFluidPhysics(Player player, boolean jumpInput) {
        // 1. Drag
        double drag = inWater ? DRAG_LIQUID : LAVA_DRAG;
        
        // 2. Vertical Movement
        velY -= GRAVITY; // Apply gravity first
        
        // 3. Fluid "Buoyancy" / Resistance
        // In water, gravity is effectively reduced or countered by drag significantly
        
        if (jumpInput) {
             // Treading water / Swimming up
             // If completely submerged vs surface?
             // Simple approximation: Jump in water adds velocity
             velY += 0.04; 
             
             if (velY > 0.2) velY = 0.2; // Cap upward swim speed (approx)
        }
        
        // Apply Drag
        velX *= drag;
        velY *= drag;
        velZ *= drag;
        
        // 4. Sink stabilization (don't fall infinitely fast)
        // Terminal velocity in water is much lower
        if (velY < -0.15) velY = -0.15; // Cap sinking speed
        
        // 5. Modern Swimming (1.13+)
        if (player != null && player.isSwimming()) {
            // Dolphin swimming - velocity follows look direction
            // This is complex to predict perfectly without exact look vector
            // But we can relax the drag or boost velocity towards look
            
            // For now, if swimming, we trust the client's Y velocity more (relax check)
            // or we simulate the "look boost"
            double pitchRad = Math.toRadians(pitch);
            double swimBoost = 0.02; // Small boost in look dir
            if (Math.abs(pitch) > 10) {
                 velY -= Math.sin(pitchRad) * swimBoost;
            }
        }
    }

    private void moveEntity() {
        if (Math.abs(velX) < 0.005) velX = 0;
        if (Math.abs(velY) < 0.005) velY = 0;
        if (Math.abs(velZ) < 0.005) velZ = 0;

        // Try to move
        double dx = velX;
        double dy = velY;
        double dz = velZ;

        // Broadphase check: Get all potential collision blocks
        BoundingBox potentialBox = playerBox.clone().expand(dx, dy, dz).expand(1, 1, 1);
        List<BoundingBox> collisions = getCollisions(potentialBox);

        // Y Collision (Step 1)
        double origDy = dy;
        for (BoundingBox bb : collisions) {
            dy = calculateOffset(bb, playerBox, dy, 1);
        }
        playerBox.shift(0, dy, 0);
        
        // X Collision (Step 2)
        for (BoundingBox bb : collisions) {
            dx = calculateOffset(bb, playerBox, dx, 0);
        }
        playerBox.shift(dx, 0, 0);

        // Z Collision (Step 3)
        for (BoundingBox bb : collisions) {
            dz = calculateOffset(bb, playerBox, dz, 2);
        }
        playerBox.shift(0, 0, dz);

        // Update Position
        x = playerBox.getCenter().getX();
        y = playerBox.getMinY();
        z = playerBox.getCenter().getZ();
        
        // Update Ground State
        boolean landed = origDy != dy && origDy < 0;
        if (landed) {
            velY = 0;
            onGround = true;
        } else {
            onGround = false; // Will be rechecked in checkEnvironment
        }
        
        // Collision stop
        if (dx != velX) velX = 0;
        if (dz != velZ) velZ = 0;
    }
    
    private double calculateOffset(BoundingBox obstacle, BoundingBox player, double displacement, int axis) {
        if (displacement == 0) return 0;
        double epsilon = 1.0E-7; // Small buffer

        if (axis == 0) { // X
            if (!overlapsY(obstacle, player) || !overlapsZ(obstacle, player)) return displacement;
            
            if (displacement > 0 && player.getMaxX() <= obstacle.getMinX() + epsilon) {
                double d = obstacle.getMinX() - player.getMaxX();
                if (d < displacement) displacement = d;
            } else if (displacement < 0 && player.getMinX() >= obstacle.getMaxX() - epsilon) {
                double d = obstacle.getMaxX() - player.getMinX();
                if (d > displacement) displacement = d;
            }
        } else if (axis == 1) { // Y
            if (!overlapsX(obstacle, player) || !overlapsZ(obstacle, player)) return displacement;
            
            if (displacement > 0 && player.getMaxY() <= obstacle.getMinY() + epsilon) {
                double d = obstacle.getMinY() - player.getMaxY();
                if (d < displacement) displacement = d;
            } else if (displacement < 0 && player.getMinY() >= obstacle.getMaxY() - epsilon) {
                double d = obstacle.getMaxY() - player.getMinY();
                if (d > displacement) displacement = d;
            }
        } else if (axis == 2) { // Z
            if (!overlapsX(obstacle, player) || !overlapsY(obstacle, player)) return displacement;
            
            if (displacement > 0 && player.getMaxZ() <= obstacle.getMinZ() + epsilon) {
                double d = obstacle.getMinZ() - player.getMaxZ();
                if (d < displacement) displacement = d;
            } else if (displacement < 0 && player.getMinZ() >= obstacle.getMaxZ() - epsilon) {
                double d = obstacle.getMaxZ() - player.getMinZ();
                if (d > displacement) displacement = d;
            }
        }
        return displacement;
    }

    private boolean overlapsX(BoundingBox a, BoundingBox b) {
        return a.getMaxX() > b.getMinX() && a.getMinX() < b.getMaxX();
    }
    
    private boolean overlapsY(BoundingBox a, BoundingBox b) {
        return a.getMaxY() > b.getMinY() && a.getMinY() < b.getMaxY();
    }
    
    private boolean overlapsZ(BoundingBox a, BoundingBox b) {
        return a.getMaxZ() > b.getMinZ() && a.getMinZ() < b.getMaxZ();
    }

    private void checkEnvironment() {
        if (world == null) return;
        
        // Update Bounding Box to current pos
        updateBoundingBox();
        
        // Check Ground (More precise than collision result)
        // Expand slightly more to catch thin blocks like Lily Pads (0.015625 height) or Carpets (0.0625)
        BoundingBox groundCheck = playerBox.clone().shift(0, -0.1, 0); 
        List<BoundingBox> groundCollisions = getCollisions(groundCheck);
        
        // Remove collisions that are actually inside the player (prevent stuck)
        groundCollisions.removeIf(bb -> bb.getMaxY() > playerBox.getMinY() + 0.1); 
        
        boolean blockBelowSolid = false;
        try {
             blockBelowSolid = world.getBlockAt((int)x, (int)Math.floor(y-0.2), (int)z).getType().isSolid();
        } catch (Exception ignored) {}

        onGround = !groundCollisions.isEmpty() || (y % 1.0 == 0 && blockBelowSolid);
        
        // Special case: Lily Pads
        // If we are just above a lily pad, treat as ground
        if (!onGround) {
             BoundingBox lilyCheck = playerBox.clone().shift(0, -0.2, 0);
             List<BoundingBox> lilyCollisions = getCollisions(lilyCheck);
             for (BoundingBox bb : lilyCollisions) {
                 if (bb.getHeight() < 0.2 && bb.getMaxY() <= playerBox.getMinY() + 0.05) {
                     onGround = true;
                     break;
                 }
             }
        }

        // Check Fluids
        Block b = world.getBlockAt((int)Math.floor(x), (int)Math.floor(y), (int)Math.floor(z));
        inWater = b.getType() == Material.WATER;
        inLava = b.getType() == Material.LAVA;
        
        // Check Climbing
        String bName = b.getType().name();
        isClimbing = bName.contains("LADDER") || bName.contains("VINE") || bName.contains("SCAFFOLDING");
    }

    private List<BoundingBox> getCollisions(BoundingBox checkArea) {
        List<BoundingBox> boxes = new ArrayList<>();
        if (world == null) return boxes;

        int minX = (int) Math.floor(checkArea.getMinX());
        int maxX = (int) Math.floor(checkArea.getMaxX());
        int minY = (int) Math.floor(checkArea.getMinY());
        int maxY = (int) Math.floor(checkArea.getMaxY());
        int minZ = (int) Math.floor(checkArea.getMinZ());
        int maxZ = (int) Math.floor(checkArea.getMaxZ());

        for (int bx = minX; bx <= maxX; bx++) {
            for (int by = minY; by <= maxY; by++) {
                for (int bz = minZ; bz <= maxZ; bz++) {
                    Block block = world.getBlockAt(bx, by, bz);
                    if (!block.getType().isSolid()) continue;

                    // Modern Bukkit Collision API
                    Collection<BoundingBox> blockBoxes = block.getCollisionShape().getBoundingBoxes();
                    for (BoundingBox bb : blockBoxes) {
                        BoundingBox shifted = bb.clone().shift(bx, by, bz);
                        if (checkArea.overlaps(shifted)) {
                            boxes.add(shifted);
                        }
                    }
                }
            }
        }
        return boxes;
    }

    private void updateBoundingBox() {
        // Re-center bounding box on x/y/z
        double halfW = PLAYER_WIDTH / 2.0;
        this.playerBox = new BoundingBox(x - halfW, y, z - halfW, x + halfW, y + PLAYER_HEIGHT, z + halfW);
    }
    
    private double getFriction() {
        if (!onGround) return 0.91; // Air friction default
        
        Block blockUnder = world.getBlockAt((int)Math.floor(x), (int)Math.floor(y) - 1, (int)Math.floor(z));
        String type = blockUnder.getType().name();
        
        if (type.contains("ICE")) return SLIPPERY_FRICTION;
        if (type.contains("SLIME")) return SLIME_FRICTION;
        if (type.contains("SOUL_SAND")) return SOUL_SAND_FRICTION;
        
        return GROUND_FRICTION;
    }

    private double getAttributeSpeed(Player player, boolean sprint, boolean sneak) {
        double base = WALK_SPEED; // 0.1
        // Apply attributes
        if (player != null) {
            // Potion Effects
            PotionEffect speed = player.getPotionEffect(PotionEffectType.SPEED);
            if (speed != null) {
                base *= 1.0 + (0.2 * (speed.getAmplifier() + 1));
            }
            PotionEffect slow = player.getPotionEffect(PotionEffectType.SLOWNESS);
            if (slow != null) {
                base *= 1.0 - (0.15 * (slow.getAmplifier() + 1));
            }
        }
        if (sprint) base *= SPRINT_MULTIPLIER;
        // Sneak handled usually by inputs in modern versions, but here for safety
        // if (sneak) base *= SNEAK_MULTIPLIER; 
        
        return base;
    }

    // Getters / Setters
    public double getX() { return x; }
    public double getY() { return y; }
    public double getZ() { return z; }
    public double getVelX() { return velX; }
    public double getVelY() { return velY; }
    public double getVelZ() { return velZ; }
    public boolean isOnGround() { return onGround; }
    public void setVelocity(double vx, double vy, double vz) { this.velX = vx; this.velY = vy; this.velZ = vz; }
    public void setPosition(double x, double y, double z) { this.x = x; this.y = y; this.z = z; updateBoundingBox(); }
        public void setYaw(float yaw) { this.yaw = yaw; }
    
            public MovementSimulator copy() {
                MovementSimulator copy = new MovementSimulator();
                copy.x = this.x;
                copy.y = this.y;
                copy.z = this.z;
                copy.velX = this.velX;
                copy.velY = this.velY;
                copy.velZ = this.velZ;
                copy.onGround = this.onGround;
                copy.inWater = this.inWater;
                copy.inLava = this.inLava;
                copy.isClimbing = this.isClimbing;
                copy.yaw = this.yaw;
                copy.pitch = this.pitch;
                copy.world = this.world;
                copy.playerBox = this.playerBox.clone();
                return copy;
            }    }
