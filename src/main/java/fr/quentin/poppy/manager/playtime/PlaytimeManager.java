package fr.quentin.poppy.manager.playtime;

import fr.quentin.poppy.util.io.AtomicYamlWriter;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

import java.io.File;
import java.util.HashMap;
import java.util.Locale;
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
 * Minecraft versions. Instead, {@link #sessionStartMillis} records when
 * each online player's current session began; that elapsed time is
 * folded into {@link #totalMillis} both on quit and periodically while
 * still online (see {@link #foldInOngoingSessions}).
 *
 * <p>{@link #getTotalPlaytimeMillis} adds any still-in-progress session
 * time on top of the persisted total, so a currently-online player's
 * playtime is always accurate up to the second.
 *
 * <p>Persisted to {@code playtime.yml} on a 1-minute debounce (see
 * {@link #dirty}/{@link #flushIfDirty}) via a dedicated single-thread
 * {@link #ioExecutor}, with a final blocking flush at {@link #shutdown()}.
 *
 * <p><b>{@link #foldInOngoingSessions()} is what makes the debounce
 * actually bounded:</b> {@link #dirty} used to only ever be set in
 * {@link #onQuit}, meaning a currently-online player's elapsed session
 * time was never reflected in {@link #totalMillis} until they actually
 * disconnected — a crash (not a clean shutdown) lost that player's
 * entire session, not just "up to a minute" as the debounce name
 * implies. {@link #flushIfDirty} now folds every online player's elapsed
 * time into their total on every tick it runs, so at most one flush
 * interval's worth of playtime is ever at risk, for anyone.
 *
 * <p><b>{@link #nameCache} is persisted, not just in-memory:</b> without
 * this, {@link #resolveByName} could only ever resolve a player who had
 * reconnected since the last restart — a player's own {@code /playtime}
 * data on disk was fine, but an admin's {@code /playtime <offline player>}
 * silently stopped working for anyone who hadn't logged in yet this
 * session, contradicting what this feature promises. {@link #onJoin}
 * also purges any stale mapping pointing at this UUID under a different
 * name before adding the current one — without that, a player who
 * reclaims a name previously used by someone else would have the old
 * entry still resolving that name to the WRONG UUID.
 */
public class PlaytimeManager implements Listener {

    private static final long SAVE_INTERVAL_TICKS = 20L * 60; // 1 minute

    private final JavaPlugin plugin;
    private final File file;

    private final Map<UUID, Long> totalMillis = new HashMap<>();
    private final Map<UUID, Long> sessionStartMillis = new HashMap<>();
    private final Map<String, UUID> nameCache = new HashMap<>();

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
        Player player = event.getPlayer();

        // Purge any stale mapping first — without this, a player who reclaims
        // a name previously used by someone else would resolve to the WRONG
        // UUID via the old entry, since removal only ever happened by
        // overwriting the same key, never by value.
        nameCache.values().removeIf(cached -> cached.equals(player.getUniqueId()));
        nameCache.put(player.getName().toLowerCase(Locale.ROOT), player.getUniqueId());

        sessionStartMillis.put(player.getUniqueId(), System.currentTimeMillis());
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

    /**
     * Resolves a player name to their UUID via {@link #nameCache} — works
     * for a currently-offline player as long as they've joined this
     * server at least once before, even across a restart (the cache is
     * persisted, see {@link #loadAll()}/{@link #persist(boolean)}).
     */
    public UUID resolveByName(String name) {
        return nameCache.get(name.toLowerCase(Locale.ROOT));
    }

    private void loadAll() {
        if (!file.exists()) {
            return;
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);

        ConfigurationSection section = yaml.getConfigurationSection("playtime");
        if (section != null) {
            for (String uuidString : section.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(uuidString);
                    totalMillis.put(uuid, section.getLong(uuidString));
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().log(Level.WARNING, "Skipping an invalid UUID in playtime.yml: " + uuidString);
                }
            }
        }

        ConfigurationSection names = yaml.getConfigurationSection("names");
        if (names != null) {
            for (String name : names.getKeys(false)) {
                String raw = names.getString(name);
                if (raw == null) {
                    continue;
                }
                try {
                    nameCache.put(name, UUID.fromString(raw));
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Skipping an invalid UUID in playtime.yml names: " + name);
                }
            }
        }
    }

    private void flushIfDirty() {
        foldInOngoingSessions();
        if (!dirty) {
            return;
        }
        dirty = false;
        persist(false);
    }

    /**
     * Advances every online player's session start to now, folding the
     * elapsed time into their total — see the class-level doc for why
     * this is what bounds the debounce's real data-loss risk.
     * {@link #getTotalPlaytimeMillis} stays accurate either way, since it
     * always adds whatever's elapsed since the last fold-in on top of the
     * persisted total.
     */
    private void foldInOngoingSessions() {
        if (sessionStartMillis.isEmpty()) {
            return;
        }

        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Long> entry : sessionStartMillis.entrySet()) {
            long elapsed = Math.max(0L, now - entry.getValue());
            if (elapsed <= 0) {
                continue;
            }
            totalMillis.merge(entry.getKey(), elapsed, Long::sum);
            entry.setValue(now);
            dirty = true;
        }
    }

    private void persist(boolean blocking) {
        YamlConfiguration yaml = new YamlConfiguration();

        for (Map.Entry<UUID, Long> entry : new HashMap<>(totalMillis).entrySet()) {
            yaml.set("playtime." + entry.getKey(), entry.getValue());
        }
        for (Map.Entry<String, UUID> entry : new HashMap<>(nameCache).entrySet()) {
            yaml.set("names." + entry.getKey(), entry.getValue().toString());
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
     * total before the final blocking save, then persists — must be
     * called from {@code Poppy#onDisable}.
     */
    public void shutdown() {
        foldInOngoingSessions();
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