package fr.quentin.poppy.model;

import fr.quentin.poppy.manager.spawn.SpawnManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

/**
 * Immutable representation of a player's home (or the server spawn, which
 * reuses this same record with a fixed {@code "spawn"} name — see
 * {@link SpawnManager}).
 */
public record Home(String name, String worldName, double x, double y, double z, float yaw, float pitch,
                   long createdAt) {

    /**
     * Builds a Home from a player's current location, stamped with the current time.
     *
     * @param location must have a non-null {@link Location#getWorld()} — every
     *                  call site is expected to guarantee this beforehand (a live
     *                  player's location always has one; callers building a
     *                  {@code Location} from a possibly-unloaded world, like
     *                  {@code BackCommand}, must check for {@code null} first).
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