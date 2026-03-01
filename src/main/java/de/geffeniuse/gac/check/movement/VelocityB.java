package de.geffeniuse.gac.check.movement;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketEvent;
import de.geffeniuse.gac.check.Check;
import de.geffeniuse.gac.data.GACUser;

/**
 * VelocityB - Vertical Knockback Check
 * Detects when players reduce or ignore vertical knockback (Y velocity).
 */
public class VelocityB extends Check {

    private boolean tracking = false;
    private double startY;
    private double expectedY;
    private long knockbackTime = 0;
    private int ticksTracked = 0;
    private double maxYReached = 0;

    private static final double MIN_Y_VELOCITY = 0.2; // Minimum upward velocity to track
    private static final double MIN_Y_PERCENTAGE = 0.35; // Must reach at least 35% of expected height

    public VelocityB(GACUser user) {
        super(user, "Velocity (Vertical)", "Checks vertical knockback taken.");
    }

    @Override
    public void onPacket(PacketEvent event) {
        if (event.getPacketType() == PacketType.Play.Server.ENTITY_VELOCITY) {
            handleVelocityPacket(event);
            return;
        }

        if (event.getPacketType() == PacketType.Play.Client.POSITION ||
            event.getPacketType() == PacketType.Play.Client.POSITION_LOOK) {
            handlePositionPacket();
        }
    }

    private void handleVelocityPacket(PacketEvent event) {
        try {
            // Check if this velocity packet is for OUR player
            int entityId = event.getPacket().getIntegers().read(0);
            if (user.getPlayer() == null || entityId != user.getPlayer().getEntityId()) {
                return; // Not for us
            }

            double velY;

            // 1.21+ stores velocity as Vec3 in field 1
            Object vec3 = event.getPacket().getModifier().read(1);
            if (vec3 != null) {
                try {
                    var vec3Class = vec3.getClass();
                    velY = getVec3Component(vec3, vec3Class, "y", "b", "field_1351");
                } catch (Exception e) {
                    // Fallback: parse from toString()
                    String str = vec3.toString();
                    str = str.replaceAll("[^0-9.\\-,]", "");
                    String[] parts = str.split(",");
                    if (parts.length >= 2) {
                        velY = Double.parseDouble(parts[1].trim());
                    } else {
                        return;
                    }
                }
            } else {
                return;
            }

            // Only track upward knockback
            if (velY > MIN_Y_VELOCITY) {
                tracking = true;
                startY = user.getLastY();
                maxYReached = startY;
                // Expected height = initial velocity squared / (2 * gravity)
                // Simplified: velY * 4 gives approximate max height in blocks
                expectedY = velY * 4.0;
                knockbackTime = System.currentTimeMillis();
                ticksTracked = 0;
            }
        } catch (Exception e) {
            // ignore
        }
    }

    private double getVec3Component(Object vec3, Class<?> clazz, String... fieldNames) throws Exception {
        for (String name : fieldNames) {
            try {
                var field = clazz.getDeclaredField(name);
                field.setAccessible(true);
                return field.getDouble(vec3);
            } catch (NoSuchFieldException ignored) {}
        }
        // Try methods
        for (String name : fieldNames) {
            try {
                var method = clazz.getDeclaredMethod(name);
                method.setAccessible(true);
                return (double) method.invoke(vec3);
            } catch (NoSuchMethodException ignored) {}
        }
        throw new NoSuchFieldException("Could not find field for Vec3 component");
    }

    private void handlePositionPacket() {
        if (!tracking) return;

        ticksTracked++;
        long elapsed = System.currentTimeMillis() - knockbackTime;

        // Too early
        if (elapsed < 50) return;

        double currentY = user.getLastY();
        double heightReached = currentY - startY;

        // Track maximum height reached
        if (heightReached > maxYReached - startY) {
            maxYReached = currentY;
        }

        // After ~15 ticks (player should have gone up and started falling)
        if (ticksTracked >= 15 || elapsed >= 750) {
            double actualHeight = maxYReached - startY;
            double percentage = (expectedY > 0) ? (actualHeight / expectedY) * 100 : 100;

            if (actualHeight < expectedY * MIN_Y_PERCENTAGE && actualHeight < 0.5) {
                fail(String.format("%.0f%% (height=%.2f, expected=%.2f)", percentage, actualHeight, expectedY));
            }

            tracking = false;
        }
    }
}
