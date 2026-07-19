package fr.quentin.poppy.manager;

import fr.quentin.poppy.model.Home;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.logging.Level;

public class HomeManager {

    public static final int MAX_HOMES = 54;

    private final JavaPlugin plugin;
    private final File homesFolder;
    private final Map<UUID, LinkedHashMap<String, Home>> cache = new LinkedHashMap<>();

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

    public void save(UUID uuid) {
        LinkedHashMap<String, Home> homes = cache.get(uuid);
        if (homes == null) {
            return;
        }

        YamlConfiguration config = buildConfig(homes);
        File file = fileFor(uuid);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> writeToDisk(config, file, uuid));
    }

    public void saveAllSync() {
        for (Map.Entry<UUID, LinkedHashMap<String, Home>> entry : cache.entrySet()) {
            YamlConfiguration config = buildConfig(entry.getValue());
            writeToDisk(config, fileFor(entry.getKey()), entry.getKey());
        }
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

    /**
     * Called when a player leaves: flushes their homes to disk synchronously
     * (so nothing is lost if the server stops right after) and evicts them
     * from the in-memory cache to avoid an unbounded memory leak over time.
     */
    public void unload(UUID uuid) {
        LinkedHashMap<String, Home> homes = cache.get(uuid);
        if (homes == null) {
            return;
        }
        writeToDisk(buildConfig(homes), fileFor(uuid), uuid);
        cache.remove(uuid);
    }
}