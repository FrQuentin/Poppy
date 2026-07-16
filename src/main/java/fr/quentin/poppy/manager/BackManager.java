package fr.quentin.poppy.manager;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class BackManager {

    private final Map<UUID, Location> backLocations = new HashMap<>();

    public void recordLocation(Player player) {
        backLocations.put(player.getUniqueId(), player.getLocation());
    }

    public Location getBack(UUID uuid) {
        return backLocations.get(uuid);
    }
}