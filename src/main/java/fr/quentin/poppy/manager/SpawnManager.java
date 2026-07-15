package fr.quentin.poppy.manager;

import fr.quentin.poppy.model.Home;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.logging.Level;

public class SpawnManager {

    private static final String SPAWN_NAME = "spawn";

    private final JavaPlugin plugin;
    private final File file;
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
        YamlConfiguration config = new YamlConfiguration();
        config.set("world", cachedSpawn.getWorldName());
        config.set("x", cachedSpawn.getX());
        config.set("y", cachedSpawn.getY());
        config.set("z", cachedSpawn.getZ());
        config.set("yaw", cachedSpawn.getYaw());
        config.set("pitch", cachedSpawn.getPitch());
        config.set("created", cachedSpawn.getCreatedAt());

        try {
            config.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save spawn.yml", e);
        }
    }
}