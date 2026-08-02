package fr.quentin.poppy.manager.death;

import fr.quentin.poppy.listeners.death.DeathCoordsListener;
import fr.quentin.poppy.manager.back.BackManager;
import fr.quentin.poppy.manager.share.ShareManager;
import org.bukkit.Location;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks each player's most recent death location, so the clickable
 * teleport link sent by {@link DeathCoordsListener}
 * can be resolved server-side via {@code /deathback} rather than trusting
 * coordinates supplied by the client — the same reasoning as
 * {@link ShareManager}'s tokens.
 *
 * <p>A new death overwrites the previous entry (only the latest death
 * location matters). {@link #remove(UUID)} must be called on quit — see
 * {@link DeathCoordsListener}'s registration in
 * {@code Poppy#onEnable} — to avoid retaining a Location forever for
 * players who log off, same pattern as {@link BackManager}.
 */
public class DeathLocationManager {

    private final Map<UUID, Location> deathLocations = new HashMap<>();

    public void recordDeath(UUID uuid, Location location) {
        deathLocations.put(uuid, location);
    }

    public Location getLastDeath(UUID uuid) {
        return deathLocations.get(uuid);
    }

    public void remove(UUID uuid) {
        deathLocations.remove(uuid);
    }
}