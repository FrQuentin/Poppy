package fr.quentin.poppy.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.Map;
import java.util.logging.Level;

/**
 * Writes detailed, per-event activity logs to daily-rotating files under
 * {@code plugins/Poppy/logs/}, separate from the session summary printed
 * to console by {@code Poppy#logStartupBanner}/{@code logShutdownSummary}.
 * Every category (home, teleport, tpa, death, combat...) can be toggled
 * independently in config.yml, and logging as a whole can be turned off.
 *
 * <p>File writes normally happen asynchronously (same pattern as
 * {@link fr.quentin.poppy.manager.HomeManager}), guarded by a single lock
 * since every category writes to the same daily file. However, Bukkit's
 * scheduler rejects new async tasks once a plugin has started disabling
 * (throwing {@code IllegalPluginAccessException}) — several categories
 * here (ADMIN especially) can legitimately fire during shutdown, so
 * {@link #log} checks {@link JavaPlugin#isEnabled()} and falls back to a
 * synchronous write on the calling thread when the plugin is no longer
 * enabled, rather than crashing or silently dropping the line.
 */
public class PoppyLogger {

    public enum Category {
        HOME, TELEPORT, SHARE, TPA, DEATH, DEATH_CHEST, COMBAT, AFK, SLEEP, EASTER_EGG, ADMIN
    }

    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter FILE_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final JavaPlugin plugin;
    private final File logsFolder;
    private final boolean enabled;
    private final boolean consoleMirror;
    private final Map<Category, Boolean> categoryEnabled = new EnumMap<>(Category.class);

    private final Object writeLock = new Object();
    private LocalDate currentFileDate;
    private File currentFile;

    public PoppyLogger(JavaPlugin plugin) {
        this.plugin = plugin;
        this.logsFolder = new File(plugin.getDataFolder(), "logs");
        if (!logsFolder.exists()) {
            logsFolder.mkdirs();
        }

        this.enabled = plugin.getConfig().getBoolean("logging.enabled", true);
        this.consoleMirror = plugin.getConfig().getBoolean("logging.console-mirror", false);

        for (Category category : Category.values()) {
            String key = "logging.categories." + category.name().toLowerCase();
            categoryEnabled.put(category, plugin.getConfig().getBoolean(key, true));
        }
    }

    /**
     * Logs an event attributed to a player. Prefer this overload whenever a
     * {@link Player} is available.
     */
    public void log(Category category, Player player, String message) {
        log(category, player.getName(), message);
    }

    /**
     * Logs an event with a free-form actor name — for events not tied to a
     * specific online player (e.g. an expired death chest, or a
     * console-triggered action). Use {@code "CONSOLE"} or {@code "SYSTEM"}
     * as the actor for those.
     */
    public void log(Category category, String actorName, String message) {
        if (!enabled || !categoryEnabled.getOrDefault(category, true)) {
            return;
        }

        String line = "[" + LocalDateTime.now().format(TIMESTAMP_FORMAT) + "] [" + category.name() + "] " + actorName + ": " + message;

        if (consoleMirror) {
            plugin.getLogger().info(line);
        }

        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> writeToFile(line));
        } else {
            // The scheduler rejects new async tasks once the plugin has started
            // disabling — write synchronously here instead of losing the line
            // or throwing IllegalPluginAccessException.
            writeToFile(line);
        }
    }

    private void writeToFile(String line) {
        synchronized (writeLock) {
            try {
                File file = fileForToday();
                try (FileWriter writer = new FileWriter(file, true)) {
                    writer.write(line);
                    writer.write(System.lineSeparator());
                }
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Could not write to Poppy log file", e);
            }
        }
    }

    /**
     * Only called from within the {@link #writeLock}, so no separate
     * synchronization is needed for the date-rollover check itself.
     */
    private File fileForToday() {
        LocalDate today = LocalDate.now();
        if (!today.equals(currentFileDate)) {
            currentFileDate = today;
            currentFile = new File(logsFolder, "poppy-" + today.format(FILE_DATE_FORMAT) + ".log");
        }
        return currentFile;
    }
}