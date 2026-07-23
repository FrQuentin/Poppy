package fr.quentin.poppy.manager;

import fr.quentin.poppy.model.Home;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;

/**
 * Loads, caches, and persists every player's homes as one YAML file per
 * player under {@code plugins/Poppy/homes/<uuid>.yml}.
 *
 * <p>{@code cache} is only ever touched from the main thread (commands and
 * events in Bukkit run sync; the one exception, {@link #save}, only queues
 * the disk write, it doesn't touch {@code cache} from the async task), so
 * the map itself needs no synchronization.
 *
 * <p>All disk I/O goes through {@link #ioExecutor}, a single-thread
 * executor, rather than {@code Bukkit.getScheduler().runTaskAsynchronously}
 * directly — every write for every player is strictly ordered by
 * submission time, so an older queued write can never land after a newer
 * one (e.g. a slow {@link #save} completing after {@link #unload} already
 * ran on quit, resurrecting a deleted home).
 *
 * <p>When a player's home list becomes empty, {@link #save} and
 * {@link #unload} delete the file entirely instead of writing an empty
 * {@code homes: {}} — without this, a player who deletes every home keeps
 * an empty {@code .yml} forever, which used to make
 * {@link #countPlayersWithHomes()} overcount (it just listed files,
 * regardless of whether they actually contained any homes).
 * {@link #countPlayersWithHomes()} and {@link #countTotalHomes()} also
 * check each file's actual contents rather than just its existence, so any
 * empty files left over from before this fix don't skew the count either.
 *
 * <p>{@link #unload(UUID)} must be called on player quit — see
 * {@link HomeCacheListener} — both to flush unsaved changes and to evict
 * the entry, since nothing else removes a player from {@code cache} once
 * they've logged in. It blocks until its write actually completes (see
 * {@link #awaitWrite}) specifically so that removing the player from
 * {@code cache} is guaranteed to happen after their data is safely on
 * disk, not merely queued.
 */
public class HomeManager {

    public static final int MAX_HOMES = 54;

    private final JavaPlugin plugin;
    private final File homesFolder;
    private final Map<UUID, LinkedHashMap<String, Home>> cache = new LinkedHashMap<>();
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Poppy-HomeManager-IO");
        thread.setDaemon(true);
        return thread;
    });

    public HomeManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.homesFolder = new File(plugin.getDataFolder(), "homes");
        if (!homesFolder.exists()) {
            homesFolder.mkdirs();
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

    public boolean isFull(UUID uuid) {
        return getHomes(uuid).size() >= MAX_HOMES;
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

    private LinkedHashMap<String, Home> load(UUID uuid) {
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
     * Queues an async write (or an async delete if the player has no homes
     * left) and returns immediately — the normal path, called after every
     * mutation ({@link #addHome}/{@link #removeHome}).
     */
    public void save(UUID uuid) {
        LinkedHashMap<String, Home> homes = cache.get(uuid);
        if (homes == null) {
            return;
        }

        File file = fileFor(uuid);

        if (homes.isEmpty()) {
            ioExecutor.submit(() -> deleteFromDisk(file, uuid));
            return;
        }

        YamlConfiguration config = buildConfig(homes);
        ioExecutor.submit(() -> writeToDisk(config, file, uuid));
    }

    /**
     * Called at plugin shutdown for any player still left in {@code cache}
     * (normally none, since {@link #unload} already flushed everyone on
     * quit — see the class-level doc). Blocks on each write in turn, then
     * shuts {@link #ioExecutor} down once every write has actually
     * completed.
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
    }

    /**
     * Counts players who actually have at least one home — checks each
     * file's {@code homes} section rather than just listing files, since a
     * leftover empty file (e.g. from before this class deleted them on
     * last-home-removed) would otherwise be counted too.
     */
    public int countPlayersWithHomes() {
        File[] files = homesFolder.listFiles((dir, name) -> name.endsWith(".yml"));
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
        File[] files = homesFolder.listFiles((dir, name) -> name.endsWith(".yml"));
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
     */
    private void writeToDisk(YamlConfiguration config, File file, UUID uuid) {
        try {
            config.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save homes for " + uuid, e);
        }
    }

    private void deleteFromDisk(File file, UUID uuid) {
        if (file.exists() && !file.delete()) {
            plugin.getLogger().log(Level.WARNING, "Could not delete empty homes file for " + uuid);
        }
    }

    /**
     * Submits a write and blocks the calling (main) thread until it has
     * actually completed — used by {@link #unload} and {@link #saveAllSync},
     * where the caller needs a guarantee the data is safely on disk before
     * moving on (evicting the cache entry, or shutting the executor down).
     */
    private void awaitWrite(YamlConfiguration config, File file, UUID uuid) {
        try {
            ioExecutor.submit(() -> writeToDisk(config, file, uuid)).get();
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
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            plugin.getLogger().log(Level.SEVERE, "Interrupted while deleting homes file for " + uuid, e);
        } catch (ExecutionException e) {
            plugin.getLogger().log(Level.SEVERE, "Error deleting homes file for " + uuid, e);
        }
    }

    /**
     * Called when a player leaves: flushes their homes to disk (or deletes
     * the file if they have none left) — blocking until it actually
     * completes, not just queuing it — and evicts them from the in-memory
     * cache to avoid an unbounded memory leak over time.
     */
    public void unload(UUID uuid) {
        LinkedHashMap<String, Home> homes = cache.get(uuid);
        if (homes != null) {
            File file = fileFor(uuid);
            if (homes.isEmpty()) {
                awaitDelete(file, uuid);
            } else {
                YamlConfiguration config = buildConfig(homes);
                awaitWrite(config, file, uuid);
            }
            cache.remove(uuid);
        }
    }
}