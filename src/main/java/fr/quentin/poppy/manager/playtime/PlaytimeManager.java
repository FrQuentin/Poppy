package fr.quentin.poppy.manager.playtime;

import fr.quentin.poppy.util.io.AtomicYamlWriter;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Tracks each player's total accumulated playtime, backing /playtime.
 * Deliberately doesn't rely on the vanilla {@code PLAY_ONE_MINUTE}
 * statistic — its exact semantics and even its name have shifted across
 * Minecraft versions, and it's not something this plugin controls the
 * accuracy of. Instead, {@link #sessionStartMillis} records when each
 * online player's current session began; on quit, the elapsed session
 * time is folded into {@link #totalMillis} and persisted.
 *
 * <p>{@link #getTotalPlaytimeMillis} adds any still-in-progress session
 * time on top of the persisted total, so a currently-online player's
 * playtime is always accurate up to the second, not just as of their
 * last quit.
 *
 * <p>Persisted to {@code playtime.yml} on a 1-minute debounce (see
 * {@link #dirty}/{@link #flushIfDirty}) via a dedicated single-thread
 * {@link #ioExecutor}, with a final blocking flush at {@link #shutdown()}
 * — same pattern as {@code FlyManager}'s budget persistence.
 *
 * <p>A hard crash (not a clean shutdown) loses at most the current
 * session's time since the last debounce flush — the same tradeoff
 * {@code BackManager} and similar in-memory-until-quit state in this
 * plugin already accept; acceptable here since this is a cosmetic stat,
 * not gameplay-critical data.
 */
public class PlaytimeManager implements Listener {

    private static final long SAVE_INTERVAL_TICKS = 20L * 60; // 1 minute

    private final JavaPlugin plugin;
    private final File file;

    private final Map<UUID, Long> totalMillis = new HashMap<>();
    private final Map<UUID, Long> sessionStartMillis = new HashMap<>();

    private volatile boolean dirty;
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Poppy-Playtime-IO");
        thread.setDaemon(true);
        return thread;
    });

    public PlaytimeManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "playtime.yml");

        loadAll();
        Bukkit.getScheduler().runTaskTimer(plugin, this::flushIfDirty, SAVE_INTERVAL_TICKS, SAVE_INTERVAL_TICKS);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(@NonNull PlayerJoinEvent event) {
        sessionStartMillis.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(@NonNull PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        Long start = sessionStartMillis.remove(uuid);
        if (start == null) {
            return;
        }
        long elapsed = Math.max(0L, System.currentTimeMillis() - start);
        totalMillis.merge(uuid, elapsed, Long::sum);
        dirty = true;
    }

    /**
     * Persisted total plus any still-in-progress session, so this is
     * always accurate to the second for a currently-online player.
     */
    public long getTotalPlaytimeMillis(UUID uuid) {
        long stored = totalMillis.getOrDefault(uuid, 0L);
        Long start = sessionStartMillis.get(uuid);
        if (start != null) {
            stored += Math.max(0L, System.currentTimeMillis() - start);
        }
        return stored;
    }

    private void loadAll() {
        if (!file.exists()) {
            return;
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection("playtime");
        if (section == null) {
            return;
        }

        for (String uuidString : section.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(uuidString);
                totalMillis.put(uuid, section.getLong(uuidString));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().log(Level.WARNING, "Skipping an invalid UUID in playtime.yml: " + uuidString);
            }
        }
    }

    private void flushIfDirty() {
        if (!dirty) {
            return;
        }
        dirty = false;
        persist(false);
    }

    private void persist(boolean blocking) {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, Long> entry : new HashMap<>(totalMillis).entrySet()) {
            yaml.set("playtime." + entry.getKey(), entry.getValue());
        }

        try {
            if (blocking) {
                Future<?> future = ioExecutor.submit(() -> AtomicYamlWriter.save(yaml, file, plugin, "playtime.yml"));
                future.get();
            } else {
                ioExecutor.execute(() -> AtomicYamlWriter.save(yaml, file, plugin, "playtime.yml"));
            }
        } catch (RejectedExecutionException e) {
            plugin.getLogger().log(Level.WARNING, "Could not queue playtime save (I/O executor already shut down)", e);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error flushing playtime.yml", e);
        }
    }

    /**
     * Folds every still-online player's in-progress session into the
     * total before the final blocking save — without this, whoever was
     * online at shutdown would lose their current session's time
     * entirely (the normal {@link #onQuit} fold-in never runs for a
     * server stop, only a real disconnect).
     */
    public void shutdown() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Long> entry : new HashMap<>(sessionStartMillis).entrySet()) {
            long elapsed = Math.max(0L, now - entry.getValue());
            totalMillis.merge(entry.getKey(), elapsed, Long::sum);
        }
        sessionStartMillis.clear();

        persist(true);
        ioExecutor.shutdown();
        try {
            if (!ioExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("Timed out flushing playtime to disk");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}