package fr.quentin.poppy.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;

/**
 * Writes detailed, per-event activity logs to daily-rotating files under
 * {@code plugins/Poppy/logs/}, separate from the session summary printed
 * to console by {@code Poppy#logStartupBanner}/{@code logShutdownSummary}.
 * Every category (home, teleport, tpa, death, combat...) can be toggled
 * independently in config.yml, and logging as a whole can be turned off.
 *
 * <p>{@link #log} only builds a line and offers it to {@link #pendingLines}
 * — a lock-free queue — rather than touching the filesystem or the
 * scheduler at all. A single repeating async task ({@link #flush}, every
 * {@code logging.flush-interval-seconds}) drains the whole queue and
 * writes it in one open/append/close cycle. The previous version opened
 * and closed a {@link FileWriter} — and scheduled a whole async task — for
 * every single log call, which under real activity (many players, several
 * categories firing per action) meant dozens of filesystem opens per
 * second; batching removes that entirely.
 *
 * <p>{@link #shutdown()} must be called from {@code Poppy#onDisable}: the
 * queue is purely in-memory, so anything not yet flushed at the moment the
 * plugin disables would otherwise be lost on restart, and Bukkit stops
 * running this plugin's scheduled tasks once disabling begins.
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

    private final ConcurrentLinkedQueue<String> pendingLines = new ConcurrentLinkedQueue<>();
    private final Object writeLock = new Object();
    private BukkitTask flushTask;
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

        if (enabled) {
            long intervalTicks = Math.max(20L, plugin.getConfig().getInt("logging.flush-interval-seconds", 3) * 20L);
            this.flushTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::flush, intervalTicks, intervalTicks);
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

        pendingLines.offer(line);
    }

    /**
     * Drains every pending line and writes them to today's file in one
     * open/append/close cycle. Runs on the repeating async task; also
     * called once more, synchronously, from {@link #shutdown()}.
     */
    private void flush() {
        if (pendingLines.isEmpty()) {
            return;
        }

        List<String> batch = new ArrayList<>();
        String line;
        while ((line = pendingLines.poll()) != null) {
            batch.add(line);
        }

        synchronized (writeLock) {
            try {
                File file = fileForToday();
                try (FileWriter writer = new FileWriter(file, true)) {
                    for (String entry : batch) {
                        writer.write(entry);
                        writer.write(System.lineSeparator());
                    }
                }
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Could not write to Poppy log file", e);
            }
        }
    }

    /**
     * Cancels the periodic flush task and performs one final synchronous
     * flush — call this from {@code Poppy#onDisable} before the plugin
     * fully disables, since Bukkit stops running this plugin's scheduled
     * tasks around the same time, and anything queued after the last
     * periodic flush would otherwise be silently lost on restart.
     */
    public void shutdown() {
        if (flushTask != null) {
            flushTask.cancel();
        }
        flush();
    }

    /**
     * Only called from within {@link #writeLock}, so no separate
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