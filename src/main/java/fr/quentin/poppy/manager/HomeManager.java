package fr.quentin.poppy.manager;

import fr.quentin.poppy.model.Home;
import fr.quentin.poppy.util.PoppyConfig;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
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
 * <p>{@code cache} is only ever touched from the main thread, so the map
 * itself needs no synchronization. All disk I/O goes through
 * {@link #ioExecutor}, a single-thread executor — see {@link #writeToDisk}
 * for why (a per-player lock used to be here, but had a real ordering bug
 * where a slow async {@code save()} could complete after {@code unload()}
 * and resurrect a deleted home).
 *
 * <p>{@link #MAX_HOMES} (54, one double chest) is the hard ceiling tied to
 * the /homes GUI's inventory size — it never changes. The actual per-player
 * limit is looked up per-permission via {@link #getLimit(Player)}: the
 * highest {@code poppy.homes.<n>} tier the player has wins, falling back
 * to {@code homes-default-limit} in config.yml if they have none, and
 * always capped at {@link #MAX_HOMES}.
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
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Poppy-HomeManager-IO");
        thread.setDaemon(true);
        return thread;
    });

    public HomeManager(JavaPlugin plugin, PoppyConfig config) {
        this.plugin = plugin;
        this.config = config;
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

    public void save(UUID uuid) {
        LinkedHashMap<String, Home> homes = cache.get(uuid);
        if (homes == null) {
            return;
        }

        YamlConfiguration config = buildConfig(homes);
        File file = fileFor(uuid);
        ioExecutor.submit(() -> writeToDisk(config, file, uuid));
    }

    public void saveAllSync() {
        for (Map.Entry<UUID, LinkedHashMap<String, Home>> entry : cache.entrySet()) {
            YamlConfiguration config = buildConfig(entry.getValue());
            File file = fileFor(entry.getKey());
            awaitWrite(config, file, entry.getKey());
        }
        ioExecutor.shutdown();
    }

    public int countPlayersWithHomes() {
        File[] files = homesFolder.listFiles((dir, name) -> name.endsWith(".yml"));
        return files == null ? 0 : files.length;
    }

    public int countTotalHomes() {
        File[] files = homesFolder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) {
            return 0;
        }
        int total = 0;
        for (File file : files) {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
            ConfigurationSection homesSection = config.getConfigurationSection("homes");
            if (homesSection != null) {
                total += homesSection.getKeys(false).size();
            }
        }
        return total;
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
        try {
            config.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save homes for " + uuid, e);
        }
    }

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

    public void unload(UUID uuid) {
        LinkedHashMap<String, Home> homes = cache.get(uuid);
        if (homes != null) {
            YamlConfiguration config = buildConfig(homes);
            File file = fileFor(uuid);
            awaitWrite(config, file, uuid);
            cache.remove(uuid);
        }
    }
}