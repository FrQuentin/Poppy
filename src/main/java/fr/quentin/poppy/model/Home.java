package fr.quentin.poppy.model;

import org.bukkit.Location;
import org.bukkit.World;

/**
 * Immutable representation of a player's home.
 */
public final class Home {

    private final String name;
    private final String worldName;
    private final double x;
    private final double y;
    private final double z;
    private final float yaw;
    private final float pitch;
    private final long createdAt;

    public Home(String name, String worldName, double x, double y, double z, float yaw, float pitch, long createdAt) {
        this.name = name;
        this.worldName = worldName;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.createdAt = createdAt;
    }

    /**
     * Builds a Home from a player's current location, stamped with the current time.
     */
    public static Home fromLocation(String name, Location location) {
        return new Home(
                name,
                location.getWorld().getName(),
                location.getX(),
                location.getY(),
                location.getZ(),
                location.getYaw(),
                location.getPitch(),
                System.currentTimeMillis()
        );
    }

    public String getName() {
        return name;
    }

    public String getWorldName() {
        return worldName;
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    public double getZ() {
        return z;
    }

    public float getYaw() {
        return yaw;
    }

    public float getPitch() {
        return pitch;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    /**
     * Resolves this home into a Bukkit Location. Returns null if the world isn't loaded.
     */
    public Location toLocation() {
        World world = org.bukkit.Bukkit.getWorld(worldName);
        if (world == null) {
            return null;
        }
        return new Location(world, x, y, z, yaw, pitch);
    }
}
