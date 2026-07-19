package fr.quentin.poppy.manager;

import fr.quentin.poppy.model.Home;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.logging.Level;

/**
 * Loads, caches, and persists the single server-wide spawn point to
 * spawn.yml. Modeled after {@link HomeManager} but simpler: one spawn, one
 * file, no per-player cache.
 *
 * <p>{@link #save} writes asynchronously while {@link #saveSync} (called
 * from {@code Poppy#onDisable}) writes synchronously from the main thread —
 * {@link #writeLock} guards against both racing on the same file if
 * {@code /setspawn} runs right before shutdown.
 */
public class SpawnManager {

    private static final String SPAWN_NAME = "spawn";

    private final JavaPlugin plugin;
    private final File file;
    private final Object writeLock = new Object();
    private Home cachedSpawn;
    private boolean loaded;

    public SpawnManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "spawn.yml");
    }

    public Home getSpawn() {
        if (!loaded) {
            load();
        }
        return cachedSpawn;
    }

    public boolean hasSpawn() {
        return getSpawn() != null;
    }

    public void setSpawn(Location location) {
        cachedSpawn = Home.fromLocation(SPAWN_NAME, location);
        loaded = true;
        save();
    }

    private void load() {
        loaded = true;

        if (!file.exists()) {
            cachedSpawn = null;
            return;
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        if (!config.contains("world")) {
            cachedSpawn = null;
            return;
        }

        String world = config.getString("world", "world");
        double x = config.getDouble("x");
        double y = config.getDouble("y");
        double z = config.getDouble("z");
        float yaw = (float) config.getDouble("yaw");
        float pitch = (float) config.getDouble("pitch");
        long created = config.getLong("created", System.currentTimeMillis());

        cachedSpawn = new Home(SPAWN_NAME, world, x, y, z, yaw, pitch, created);
    }

    private void save() {
        if (cachedSpawn == null) {
            return;
        }

        YamlConfiguration config = buildConfig(cachedSpawn);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> writeToDisk(config));
    }

    public void saveSync() {
        if (cachedSpawn == null) {
            return;
        }

        writeToDisk(buildConfig(cachedSpawn));
    }

    private YamlConfiguration buildConfig(Home spawn) {
        YamlConfiguration config = new YamlConfiguration();
        config.set("world", spawn.worldName());
        config.set("x", spawn.x());
        config.set("y", spawn.y());
        config.set("z", spawn.z());
        config.set("yaw", spawn.yaw());
        config.set("pitch", spawn.pitch());
        config.set("created", spawn.createdAt());
        return config;
    }

    private void writeToDisk(YamlConfiguration config) {
        synchronized (writeLock) {
            try {
                config.save(file);
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Could not save spawn.yml", e);
            }
        }
    }
}