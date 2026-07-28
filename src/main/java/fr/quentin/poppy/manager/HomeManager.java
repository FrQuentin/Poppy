package fr.quentin.poppy.manager;

import fr.quentin.poppy.model.Home;
import fr.quentin.poppy.util.AtomicYamlWriter;
import fr.quentin.poppy.util.PoppyConfig;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;

/**
 * Loads, caches, and persists every player's homes as one YAML file per
 * player under {@code plugins/Poppy/homes/<uuid>.yml}.
 *
 * <p>{@code cache}, {@link #inFlight}, and {@link #inFlightTokens} are only
 * ever touched from the main thread, so none of them need synchronization.
 * All disk I/O goes through {@link #ioExecutor}, a single-thread executor,
 * so writes for a given player are strictly ordered by submission time.
 *
 * <p><b>No blocking reads, ever, on the main thread:</b> {@link #load} used
 * to block (with a bounded timeout) on any write still in flight for that
 * UUID before reading the file — meant to avoid returning stale data, but
 * even a bounded 2-second block on the main thread is already a serious
 * server-wide freeze (40 missed ticks, a watchdog trigger risk on some
 * hosts), and this was reachable by an unprivileged player: e.g. clicking
 * a {@code /sharehome} link ({@link fr.quentin.poppy.commands.PoppyGotoCommand})
 * calls {@link #getHome} for a possibly-offline player whose write was
 * just queued moments earlier. {@link #inFlight} solves the same
 * staleness problem without any disk I/O: {@link #queueWrite} keeps an
 * in-memory snapshot of exactly what's being written, and {@link #load}
 * serves that snapshot directly (no file read, no wait) whenever one
 * exists, only falling back to reading the file once nothing is in
 * flight. {@link #inFlightTokens} guards against a narrower race within
 * this fix: if two writes for the same player queue in quick succession,
 * the *first* write's completion callback must not clear
 * {@link #inFlight} out from under the *second*, still-in-flight, more
 * recent snapshot — each queued write gets its own identity token, and a
 * completion callback only clears the map if its own token is still the
 * current one.
 *
 * <p>{@link #preloadAsync} additionally warms a player's cache entry
 * asynchronously on join (see {@code HomeCacheListener#onJoin}), before
 * any command has a chance to trigger the (much cheaper, but still
 * synchronous) file read otherwise done by {@link #load} on first access.
 * This doesn't eliminate every synchronous read — cross-player access via
 * {@code /poppygoto} to a target whose homes were never cached or
 * recently written this session still reads their small YAML file
 * synchronously — but that residual cost is a quick local read, not a
 * wait on someone else's in-flight I/O, which is what made the old
 * bounded wait a real problem.
 *
 * <p>{@link #MAX_HOMES} (54, one double chest) is the hard ceiling tied to
 * the /homes GUI's inventory size — it never changes. The actual per-player
 * limit is looked up per-permission via {@link #getLimit(Player)}: the
 * highest {@code poppy.homes.<n>} tier the player has wins, falling back
 * to {@code homes-default-limit} in config.yml if they have none, and
 * always capped at {@link #MAX_HOMES}.
 *
 * <p>A player with zero homes has no file at all: {@link #save} deletes
 * the file instead of writing an empty {@code homes:} section once their
 * last home is removed.
 *
 * <p>{@link #unload(UUID)} must be called on player quit — see
 * {@link HomeCacheListener}.
 */
public class HomeManager {

    public static final int MAX_HOMES = 54;

    private final JavaPlugin plugin;
    private final PoppyConfig config;
    private final File homesFolder;
    private final Map<UUID, LinkedHashMap<String, Home>> cache = new LinkedHashMap<>();
    private final Map<UUID, LinkedHashMap<String, Home>> inFlight = new HashMap<>();
    private final Map<UUID, Object> inFlightTokens = new HashMap<>();
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Poppy-HomeManager-IO");
        thread.setDaemon(true);
        return thread;
    });

    public HomeManager(JavaPlugin plugin, PoppyConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.homesFolder = new File(plugin.getDataFolder(), "homes");
        if (!homesFolder.exists() && !homesFolder.mkdirs()) {
            plugin.getLogger().warning("Could not create the homes folder: " + homesFolder);
        }
    }

    private File fileFor(UUID uuid) {
        return new File(homesFolder, uuid.toString() + ".yml");
    }

    public LinkedHashMap<String, Home> getHomes(UUID uuid) {
        return cache.computeIfAbsent(uuid, this::load);
    }

    public boolean hasHome(UUID uuid, String name) {
        return getHomes(uuid).containsKey(name.toLowerCase());
    }

    public Home getHome(UUID uuid, String name) {
        return getHomes(uuid).get(name.toLowerCase());
    }

    /**
     * One-off read for a possibly-offline player: never populates
     * {@link #cache}. Used by {@code PoppyGotoCommand} to resolve someone
     * else's shared home without that player necessarily being online this
     * session — without this, {@link #getHomes} would cache their homes
     * forever, since no {@link org.bukkit.event.player.PlayerQuitEvent} will
     * ever fire for an offline player to trigger eviction via
     * {@code HomeCacheListener}.
     */
    public Home getHomeUncached(UUID uuid, String name) {
        LinkedHashMap<String, Home> cached = cache.get(uuid);
        if (cached != null) {
            return cached.get(name.toLowerCase());
        }
        return load(uuid).get(name.toLowerCase());
    }

    /**
     * The player's effective home limit: the highest
     * {@code poppy.homes.<n>} permission tier they have (see
     * {@code homes-limit-tiers} in config.yml), or
     * {@code homes-default-limit} if they have none of those tiers —
     * always capped at {@link #MAX_HOMES}.
     */
    public int getLimit(Player player) {
        int limit = Math.max(0, config.homesDefaultLimit());

        for (int tier : config.homesLimitTiers()) {
            if (tier > limit && player.hasPermission("poppy.homes." + tier)) {
                limit = tier;
            }
        }

        return Math.min(limit, MAX_HOMES);
    }

    public boolean isFull(Player player) {
        return getHomes(player.getUniqueId()).size() >= getLimit(player);
    }

    public void addHome(UUID uuid, Home home) {
        getHomes(uuid).put(home.name().toLowerCase(), home);
        save(uuid);
    }

    public void removeHome(UUID uuid, String name) {
        getHomes(uuid).remove(name.toLowerCase());
        save(uuid);
    }

    /**
     * Suggests home names for tab-completion: every home whose name starts with the given
     * (case-insensitive) prefix. Shared by every command that takes a home name argument.
     */
    public List<String> suggestHomeNames(UUID uuid, String prefix) {
        String partial = prefix.toLowerCase();
        List<String> suggestions = new ArrayList<>();
        for (Home home : getHomes(uuid).values()) {
            if (home.name().toLowerCase().startsWith(partial)) {
                suggestions.add(home.name());
            }
        }
        return suggestions;
    }

    /**
     * Serves the in-flight snapshot with no disk access at all if one
     * exists for this UUID — see the class-level doc. Only reads the file
     * when nothing is currently being written.
     */
    private LinkedHashMap<String, Home> load(UUID uuid) {
        LinkedHashMap<String, Home> pending = inFlight.get(uuid);
        if (pending != null) {
            return new LinkedHashMap<>(pending);
        }

        return readFromDisk(uuid);
    }

    /**
     * The actual file-reading logic, factored out so both {@link #load}
     * (main thread) and {@link #preloadAsync} (off-thread) share it — safe
     * to call from either, since it only touches the filesystem and plain
     * Java objects, never any Bukkit API.
     */
    private LinkedHashMap<String, Home> readFromDisk(UUID uuid) {
        LinkedHashMap<String, Home> homes = new LinkedHashMap<>();
        File file = fileFor(uuid);
        if (!file.exists()) {
            return homes;
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection homesSection = config.getConfigurationSection("homes");
        if (homesSection == null) {
            return homes;
        }

        for (String key : homesSection.getKeys(false)) {
            ConfigurationSection homeSection = homesSection.getConfigurationSection(key);
            if (homeSection == null) {
                continue;
            }
            String name = homeSection.getString("name", key);
            String world = homeSection.getString("world", "world");
            double x = homeSection.getDouble("x");
            double y = homeSection.getDouble("y");
            double z = homeSection.getDouble("z");
            float yaw = (float) homeSection.getDouble("yaw");
            float pitch = (float) homeSection.getDouble("pitch");
            long createdAt = homeSection.getLong("created", System.currentTimeMillis());

            homes.put(key, new Home(name, world, x, y, z, yaw, pitch, createdAt));
        }

        return homes;
    }

    /**
     * Warms {@link #cache} for a player asynchronously — meant to be
     * called on join, before any command has a chance to trigger
     * {@link #load}'s synchronous file read on the main thread. A no-op if
     * the cache is already populated by the time this runs (e.g. some
     * other code path already called {@link #getHomes} for this player).
     *
     * <p>Also a no-op if the player is no longer online by the time the
     * async read completes — without this check, a player who connects and
     * immediately disconnects (a very common pattern on a public server:
     * connection lag, IP scanners/bots) would have {@link #unload} fire and
     * find nothing to remove (the cache entry doesn't exist yet), then the
     * async read would land afterward and cache an entry for a now-offline
     * player that no future {@link org.bukkit.event.player.PlayerQuitEvent}
     * will ever evict — the exact leak {@link #getHomeUncached} exists to
     * avoid, reintroduced through this different path.
     */
    public void preloadAsync(UUID uuid) {
        if (cache.containsKey(uuid)) {
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            LinkedHashMap<String, Home> homes = readFromDisk(uuid);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (Bukkit.getPlayer(uuid) == null) {
                    return;
                }
                cache.putIfAbsent(uuid, homes);
            });
        });
    }

    /**
     * Queues an async write (or a deletion, if the player now has zero
     * homes) and returns immediately — the normal path, called after every
     * mutation ({@link #addHome}/{@link #removeHome}).
     *
     * <p>Guarded against {@link RejectedExecutionException}: after
     * {@link #saveAllSync()} shuts {@link #ioExecutor} down, any late call
     * here would otherwise throw straight into the caller instead of just
     * logging and moving on.
     */
    public void save(UUID uuid) {
        LinkedHashMap<String, Home> homes = cache.get(uuid);
        if (homes == null) {
            return;
        }

        queueWrite(uuid, homes);
    }

    /**
     * Called when a player leaves: evicts them from the in-memory cache and
     * queues their homes to be flushed to disk.
     */
    public void unload(UUID uuid) {
        LinkedHashMap<String, Home> homes = cache.remove(uuid);
        if (homes == null) {
            return;
        }

        queueWrite(uuid, homes);
    }

    /**
     * Combines what were previously two separate O(n) scans
     * ({@code countPlayersWithHomes} + {@code countTotalHomes}), each parsing
     * every homes file independently — on a server with tens of thousands of
     * players who have homes, that was tens of thousands of redundant YAML
     * parses, synchronously, on the main thread, during {@code onEnable}.
     * This does a single pass over the folder, parsing each file exactly
     * once, and runs entirely on {@link #ioExecutor} — {@code onEnable}
     * doesn't block on it at all; see {@code Poppy#logStartupBanner} for how
     * the console banner reports the result once it's ready instead.
     */
    public record HomeStats(int playersWithHomes, int totalHomes) {
    }

    public CompletableFuture<HomeStats> collectStatsAsync() {
        return CompletableFuture.supplyAsync(() -> {
            File[] files = homesFolder.listFiles((_, name) -> name.endsWith(".yml"));
            if (files == null) {
                return new HomeStats(0, 0);
            }

            int players = 0;
            int total = 0;
            for (File file : files) {
                int count = fileHomeCount(file);
                if (count > 0) {
                    players++;
                    total += count;
                }
            }
            return new HomeStats(players, total);
        }, ioExecutor);
    }

    /**
     * Snapshots {@code homes} into {@link #inFlight} under a fresh identity
     * token, submits the async write, and clears the snapshot once done —
     * but only if this write's token is still the current one for this
     * UUID (see the class-level doc for why that check matters).
     */
    private void queueWrite(UUID uuid, LinkedHashMap<String, Home> homes) {
        File file = fileFor(uuid);
        LinkedHashMap<String, Home> snapshot = new LinkedHashMap<>(homes);
        Object token = new Object();

        inFlight.put(uuid, snapshot);
        inFlightTokens.put(uuid, token);

        try {
            if (snapshot.isEmpty()) {
                ioExecutor.submit(() -> {
                    deleteFromDisk(file, uuid);
                    clearInFlightIfCurrent(uuid, token);
                });
            } else {
                YamlConfiguration yaml = buildConfig(snapshot);
                ioExecutor.submit(() -> {
                    writeToDisk(yaml, file, uuid);
                    clearInFlightIfCurrent(uuid, token);
                });
            }
        } catch (RejectedExecutionException e) {
            plugin.getLogger().log(Level.WARNING, "Could not queue homes save for " + uuid + " (I/O executor already shut down)", e);
            clearInFlightIfCurrent(uuid, token);
        }
    }

    private void clearInFlightIfCurrent(UUID uuid, Object token) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (inFlightTokens.get(uuid) == token) {
                inFlight.remove(uuid);
                inFlightTokens.remove(uuid);
            }
        });
    }

    /**
     * Called at plugin shutdown for any player still left in {@code cache}
     * (normally none, since {@link #unload} already queued a flush for
     * everyone on quit). Blocks on each write in turn — safe to block here
     * specifically because this only runs once, at shutdown, never on a
     * request from an online player.
     */
    public void saveAllSync() {
        for (Map.Entry<UUID, LinkedHashMap<String, Home>> entry : cache.entrySet()) {
            File file = fileFor(entry.getKey());
            if (entry.getValue().isEmpty()) {
                awaitDelete(file, entry.getKey());
            } else {
                YamlConfiguration config = buildConfig(entry.getValue());
                awaitWrite(config, file, entry.getKey());
            }
        }

        ioExecutor.shutdown();
        try {
            if (!ioExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("Timed out flushing homes to disk");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private int fileHomeCount(File file) {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection homesSection = config.getConfigurationSection("homes");
        return homesSection == null ? 0 : homesSection.getKeys(false).size();
    }

    private YamlConfiguration buildConfig(LinkedHashMap<String, Home> homes) {
        YamlConfiguration config = new YamlConfiguration();
        ConfigurationSection homesSection = config.createSection("homes");

        for (Map.Entry<String, Home> entry : homes.entrySet()) {
            Home home = entry.getValue();
            ConfigurationSection homeSection = homesSection.createSection(entry.getKey());
            homeSection.set("name", home.name());
            homeSection.set("world", home.worldName());
            homeSection.set("x", home.x());
            homeSection.set("y", home.y());
            homeSection.set("z", home.z());
            homeSection.set("yaw", home.yaw());
            homeSection.set("pitch", home.pitch());
            homeSection.set("created", home.createdAt());
        }

        return config;
    }

    private void writeToDisk(YamlConfiguration config, File file, UUID uuid) {
        AtomicYamlWriter.save(config, file, plugin, "homes for " + uuid);
    }

    /**
     * Removes a player's homes file entirely once they have zero homes
     * left. A missing file is not an error.
     */
    private void deleteFromDisk(File file, UUID uuid) {
        if (!file.exists()) {
            return;
        }
        if (!file.delete()) {
            plugin.getLogger().log(Level.WARNING, "Could not delete empty homes file for " + uuid);
        }
    }

    private void awaitWrite(YamlConfiguration config, File file, UUID uuid) {
        try {
            ioExecutor.submit(() -> writeToDisk(config, file, uuid)).get();
        } catch (RejectedExecutionException e) {
            plugin.getLogger().log(Level.WARNING, "Could not queue homes write for " + uuid + " (I/O executor already shut down)", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            plugin.getLogger().log(Level.SEVERE, "Interrupted while flushing homes for " + uuid, e);
        } catch (ExecutionException e) {
            plugin.getLogger().log(Level.SEVERE, "Error flushing homes for " + uuid, e);
        }
    }

    private void awaitDelete(File file, UUID uuid) {
        try {
            ioExecutor.submit(() -> deleteFromDisk(file, uuid)).get();
        } catch (RejectedExecutionException e) {
            plugin.getLogger().log(Level.WARNING, "Could not queue homes file deletion for " + uuid + " (I/O executor already shut down)", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            plugin.getLogger().log(Level.SEVERE, "Interrupted while deleting homes file for " + uuid, e);
        } catch (ExecutionException e) {
            plugin.getLogger().log(Level.SEVERE, "Error deleting homes file for " + uuid, e);
        }
    }
}