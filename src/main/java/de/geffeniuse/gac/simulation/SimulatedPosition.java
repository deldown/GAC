package de.geffeniuse.gac.simulation;

/**
 * Represents a predicted/simulated player position
 */
public class SimulatedPosition {
    public final double x, y, z;
    public final double velX, velY, velZ;
    public final boolean onGround;

    public SimulatedPosition(double x, double y, double z, double velX, double velY, double velZ, boolean onGround) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.velX = velX;
        this.velY = velY;
        this.velZ = velZ;
        this.onGround = onGround;
    }

    /**
     * Calculate distance to another position
     */
    public double distanceTo(double otherX, double otherY, double otherZ) {
        double dx = x - otherX;
        double dy = y - otherY;
        double dz = z - otherZ;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Calculate horizontal distance to another position
     */
    public double horizontalDistanceTo(double otherX, double otherZ) {
        double dx = x - otherX;
        double dz = z - otherZ;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Calculate vertical distance to another position
     */
    public double verticalDistanceTo(double otherY) {
        return Math.abs(y - otherY);
    }

    @Override
    public String toString() {
        return String.format("SimPos[%.2f, %.2f, %.2f | vel: %.3f, %.3f, %.3f | ground: %s]",
                x, y, z, velX, velY, velZ, onGround);
    }
}
