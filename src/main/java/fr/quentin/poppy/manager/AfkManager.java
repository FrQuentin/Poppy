package fr.quentin.poppy.manager;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Tracks AFK status and last-activity timestamps for online players.
 * Used both by {@link fr.quentin.poppy.commands.AfkCommand} (manual toggle)
 * and {@link AutoAfkTask} (automatic AFK after inactivity).
 *
 * <p>Entries are removed on {@link #remove} (called from
 * {@link AfkListener#onQuit}) — this class holds no state for offline
 * players, so it doesn't grow unbounded like {@link HomeManager}'s cache did
 * before that was fixed.
 */
public class AfkManager {

    private final Set<UUID> afkPlayers = new HashSet<>();
    private final Map<UUID, Long> lastActivity = new HashMap<>();

    public boolean toggle(UUID uuid) {
        if (afkPlayers.contains(uuid)) {
            afkPlayers.remove(uuid);
            return false;
        }
        afkPlayers.add(uuid);
        return true;
    }

    public boolean isAfk(UUID uuid) {
        return afkPlayers.contains(uuid);
    }

    public void clearAfk(UUID uuid) {
        afkPlayers.remove(uuid);
    }

    public void recordActivity(UUID uuid) {
        lastActivity.put(uuid, System.currentTimeMillis());
    }

    /**
     * Returns 0 (rather than a huge or undefined value) when no activity has
     * ever been recorded for this player — a safe default that never causes
     * {@link AutoAfkTask} to mark someone AFK based on missing data.
     */
    public long millisSinceActivity(UUID uuid) {
        Long last = lastActivity.get(uuid);
        if (last == null) {
            return 0;
        }
        return System.currentTimeMillis() - last;
    }

    public void remove(UUID uuid) {
        afkPlayers.remove(uuid);
        lastActivity.remove(uuid);
    }
}