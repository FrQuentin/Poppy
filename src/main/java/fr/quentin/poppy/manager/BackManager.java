package fr.quentin.poppy.manager;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks each player's most recent pre-teleport location for /back. The
 * entry is overwritten on every Poppy teleport (see
 * {@link TeleportManager#teleportNow}) and on death (see
 * {@link BackListener#onDeath}), so /back always points to "where you were
 * right before your last jump", which can chain (back → back → back...).
 *
 * <p>{@link #remove(UUID)} must be called on player quit to avoid retaining
 * a Location forever for players who log off — wired up via
 * {@link BackListener#onQuit}.
 */
public class BackManager {

    private final Map<UUID, Location> backLocations = new HashMap<>();

    public void recordLocation(Player player) {
        backLocations.put(player.getUniqueId(), player.getLocation());
    }

    /**
     * Same as {@link #recordLocation(Player)}, but with the location supplied
     * explicitly rather than read from the player's current position at call
     * time — used by {@code TeleportManager#onTeleportComplete} to commit a
     * location captured earlier (before an async teleport), only once that
     * teleport is confirmed to have actually succeeded.
     */
    public void recordLocation(Player player, Location location) {
        backLocations.put(player.getUniqueId(), location);
    }

    public Location getBack(UUID uuid) {
        return backLocations.get(uuid);
    }

    public void remove(UUID uuid) {
        backLocations.remove(uuid);
    }
}