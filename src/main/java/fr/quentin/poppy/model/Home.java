package fr.quentin.poppy.model;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

/**
 * Immutable representation of a player's home.
 */
public record Home(String name, String worldName, double x, double y, double z, float yaw, float pitch,
                   long createdAt) {

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

    /**
     * Resolves this home into a Bukkit Location. Returns null if the world isn't loaded.
     */
    public Location toLocation() {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return null;
        }
        return new Location(world, x, y, z, yaw, pitch);
    }
}