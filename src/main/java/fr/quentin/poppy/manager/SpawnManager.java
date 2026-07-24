package fr.quentin.poppy.manager;

import fr.quentin.poppy.model.Home;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.logging.Level;

/**
 * Loads, caches, and persists the single server-wide spawn point to
 * spawn.yml. Modeled after {@link HomeManager} but simpler: one spawn, one
 * file, no per-player cache.
 *
 * <p>Unlike {@link HomeManager} (which writes async via a dedicated
 * executor to stay off the main thread during frequent home
 * creates/deletes), every write here is synchronous. {@code /setspawn}
 * and {@code /delspawn} are rare admin-only actions, not something a
 * player triggers repeatedly — so the small main-thread I/O cost isn't
 * worth the complexity, and it sidesteps a real ordering bug an earlier
 * async version had: {@code save()} queuing a write on Bukkit's scheduler
 * while {@code clearSpawn()} deleted the file synchronously could let the
 * queued write wake up *after* the deletion and resurrect spawn.yml right
 * after an admin removed it. With everything synchronous and in-order on
 * the calling thread, that race can't happen, and the old {@code writeLock}
 * (which only provided mutual exclusion, not ordering) is no longer needed.
 */
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

    /**
     * Clears the spawn point: removes it from the cache and deletes
     * spawn.yml from disk, so {@link #hasSpawn()} returns false afterward.
     */
    public void clearSpawn() {
        cachedSpawn = null;
        loaded = true;

        if (file.exists() && !file.delete()) {
            plugin.getLogger().log(Level.WARNING, "Could not delete spawn.yml");
        }
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

        writeToDisk(buildConfig(cachedSpawn));
    }

    /**
     * Kept as a separate public method for {@code Poppy#onDisable} to call
     * explicitly — functionally identical to {@link #save()} now that
     * every write is synchronous, but the name documents intent at the
     * call site (a final flush before shutdown).
     */
    public void saveSync() {
        save();
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

    /**
     * Writes to a temporary file first, then atomically renames it over
     * the real file — {@code YamlConfiguration#save(File)} on its own
     * writes directly into the destination file, so a crash, out-of-disk
     * error, or forced kill mid-write could leave spawn.yml truncated and
     * unparsable. A rename on the same filesystem is atomic at the OS
     * level: readers only ever see the fully-old or fully-new file, never
     * a half-written one. Same fix as {@link HomeManager#writeToDisk}.
     */
    protected void writeToDisk(YamlConfiguration config) {
        File tempFile = new File(file.getParentFile(), file.getName() + ".tmp");

        try {
            config.save(tempFile);
            Files.move(tempFile.toPath(), file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save spawn.yml", e);
            tempFile.delete();
        }
    }
}