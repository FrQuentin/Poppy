package fr.quentin.poppy.manager;

import fr.quentin.poppy.model.Home;
import fr.quentin.poppy.util.PoppyConfig;
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
 * <p>{@code cache} and {@link #pendingWrites} are only ever touched from
 * the main thread, so neither needs synchronization. All disk I/O goes
 * through {@link #ioExecutor}, a single-thread executor, so writes for a
 * given player are strictly ordered by submission time.
 *
 * <p>{@link #pendingWrites} closes a real race between {@link #unload}
 * (queues a write asynchronously on quit) and {@link #load} (reads the
 * file synchronously on the main thread, e.g. on a fast reconnect, or via
 * {@code /poppygoto} teleporting into someone else's shared home which
 * calls {@link #getHomes}): without it, a reconnect quick enough could
 * read the file before the queued write from the previous session landed,
 * see stale homes, and then have the old write overwrite the freshly
 * reloaded cache moments later — a real, if narrow, data-loss window.
 * {@link #load} now waits on the most recently queued write for that UUID
 * (if any) before touching the file; because {@link #ioExecutor} is
 * single-threaded and FIFO, the most recent write having completed
 * implies every earlier one for that UUID has too.
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
    private static final long PENDING_WRITE_TIMEOUT_MILLIS = 2000L;

    private final JavaPlugin plugin;
    private final PoppyConfig config;
    private final File homesFolder;
    private final Map<UUID, LinkedHashMap<String, Home>> cache = new LinkedHashMap<>();
    private final Map<UUID, Future<?>> pendingWrites = new HashMap<>();
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
     * Waits for the most recently queued write for this UUID (if any) to
     * finish before reading the file — see the class-level doc for why.
     */
    private LinkedHashMap<String, Home> load(UUID uuid) {
        awaitPendingWrite(uuid);

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
     * Waits for the most recently queued write for this UUID (if any) to
     * finish before reading the file — see the class-level doc for why.
     *
     * <p>Bounded by {@link #PENDING_WRITE_TIMEOUT_MILLIS}: {@code load} runs
     * on the main thread (called from {@link #getHomes} via
     * {@code computeIfAbsent}, including for an offline player — e.g. when
     * {@code /poppygoto} resolves a shared home), so waiting unboundedly on
     * disk I/O here would freeze the whole server on a slow disk or a large
     * file. On timeout, this gives up waiting and proceeds to read whatever
     * is currently on disk rather than blocking further — in the rare case
     * the write genuinely hasn't landed yet, the read could be a few
     * milliseconds stale, which is an acceptable tradeoff against blocking
     * the entire server. The pending write itself isn't cancelled; it keeps
     * running on {@link #ioExecutor} and will still land eventually.
     */
    private void awaitPendingWrite(UUID uuid) {
        Future<?> future = pendingWrites.remove(uuid);
        if (future == null) {
            return;
        }

        try {
            future.get(PENDING_WRITE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (CancellationException ignored) {
            // Nothing left to wait for.
        } catch (TimeoutException e) {
            plugin.getLogger().log(Level.WARNING,
                    "Timed out waiting for a pending homes write for " + uuid + " — reading the file anyway, it may be briefly stale");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            plugin.getLogger().log(Level.SEVERE, "Interrupted while waiting for a pending homes write for " + uuid, e);
        } catch (ExecutionException e) {
            plugin.getLogger().log(Level.SEVERE, "Error waiting for a pending homes write for " + uuid, e);
        }
    }

    /**
     * Queues an async write (or a deletion, if the player now has zero
     * homes) and returns immediately — the normal path, called after every
     * mutation ({@link #addHome}/{@link #removeHome}). The resulting
     * {@link Future} is tracked in {@link #pendingWrites} so a later
     * {@link #load} for the same player can wait on it.
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
     * queues their homes to be flushed to disk — without blocking the
     * calling thread on the actual write (see {@link #pendingWrites} for
     * how a later reload waits on it instead).
     */
    public void unload(UUID uuid) {
        LinkedHashMap<String, Home> homes = cache.remove(uuid);
        if (homes == null) {
            return;
        }

        queueWrite(uuid, homes);
    }

    private void queueWrite(UUID uuid, LinkedHashMap<String, Home> homes) {
        File file = fileFor(uuid);

        try {
            Future<?> future;
            if (homes.isEmpty()) {
                future = ioExecutor.submit(() -> deleteFromDisk(file, uuid));
            } else {
                YamlConfiguration config = buildConfig(homes);
                future = ioExecutor.submit(() -> writeToDisk(config, file, uuid));
            }
            pendingWrites.put(uuid, future);
        } catch (RejectedExecutionException e) {
            plugin.getLogger().log(Level.WARNING, "Could not queue homes save for " + uuid + " (I/O executor already shut down)", e);
        }
    }

    /**
     * Called at plugin shutdown for any player still left in {@code cache}
     * (normally none, since {@link #unload} already queued a flush for
     * everyone on quit). Blocks on each write in turn, then shuts
     * {@link #ioExecutor} down and waits for it to fully drain — see the
     * class-level doc on why blocking matters here specifically.
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

    public int countPlayersWithHomes() {
        File[] files = homesFolder.listFiles((_, name) -> name.endsWith(".yml"));
        if (files == null) {
            return 0;
        }

        int count = 0;
        for (File file : files) {
            if (fileHomeCount(file) > 0) {
                count++;
            }
        }
        return count;
    }

    public int countTotalHomes() {
        File[] files = homesFolder.listFiles((_, name) -> name.endsWith(".yml"));
        if (files == null) {
            return 0;
        }
        int total = 0;
        for (File file : files) {
            total += fileHomeCount(file);
        }
        return total;
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

    /**
     * Only ever runs on {@link #ioExecutor}'s single thread, so no
     * synchronization is needed here — the executor itself is what
     * prevents concurrent writes.
     *
     * <p>Writes to a temporary file first, then atomically renames it over
     * the real file. If the filesystem doesn't support atomic moves (some
     * Docker overlay filesystems, some network mounts),
     * {@link AtomicMoveNotSupportedException} falls back to a plain move.
     */
    protected void writeToDisk(YamlConfiguration config, File file, UUID uuid) {
        File tempFile = new File(file.getParentFile(), file.getName() + ".tmp");

        try {
            config.save(tempFile);
            try {
                Files.move(tempFile.toPath(), file.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tempFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save homes for " + uuid, e);
            if (!tempFile.delete()) {
                plugin.getLogger().log(Level.WARNING, "Could not delete leftover temp file: " + tempFile);
            }
        }
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