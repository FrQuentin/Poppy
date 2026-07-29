package fr.quentin.poppy.manager;

import fr.quentin.poppy.model.Home;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks each player's last recorded location for /back — populated
 * before a teleport ({@link TeleportManager#onTeleportComplete}, only
 * once that teleport is confirmed successful) and on death (via
 * {@code BackListener#onDeath}, if {@code back-on-death} is enabled).
 *
 * <p>Only holds entries for online (or very recently online) players —
 * evicted on quit via {@code BackListener#onQuit} — so it never grows
 * unbounded.
 */
public class BackManager {

    private final Map<UUID, Location> backLocations = new HashMap<>();

    /**
     * Records the player's current location.
     */
    public void recordLocation(Player player) {
        backLocations.put(player.getUniqueId(), player.getLocation());
    }

    /**
     * Same as {@link #recordLocation(Player)}, but with the location
     * supplied explicitly rather than read from the player's current
     * position at call time — used by
     * {@code TeleportManager#onTeleportComplete} to commit a location
     * captured earlier (before an async teleport), only once that
     * teleport is confirmed to have actually succeeded. Without this
     * distinction, recording the origin unconditionally before the
     * teleport result was known meant a failed {@code teleportAsync} call
     * still silently overwrote a player's real previous /back location
     * with their own current position.
     */
    public void recordLocation(Player player, Location location) {
        backLocations.put(player.getUniqueId(), location);
    }

    /**
     * Returns the raw recorded {@link Location} for this player, or null
     * if none is on record — used by {@code BackCommand}, which needs the
     * raw location to check whether its world is still loaded before
     * building a {@link Home} from it.
     */
    public Location getBack(UUID uuid) {
        return backLocations.get(uuid);
    }

    public void remove(UUID uuid) {
        backLocations.remove(uuid);
    }
}