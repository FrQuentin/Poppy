package fr.quentin.poppy.util;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;

/**
 * Writes detailed, per-event activity logs to daily-rotating files under
 * {@code plugins/Poppy/logs/}. Every category can be toggled independently
 * in config.yml, and logging as a whole can be turned off — all read live
 * via {@link PoppyConfig}, so {@code /poppy reload} applies immediately.
 *
 * <p>All I/O runs on {@link #ioExecutor}, a single-thread executor — the
 * same pattern {@link fr.quentin.poppy.manager.HomeManager} uses, for the
 * same two reasons: (1) it avoids ever calling Bukkit's own scheduler,
 * which throws {@code IllegalPluginAccessException} immediately if
 * scheduled after the plugin has been disabled — a real risk here since
 * {@link #log} can be called from async callbacks (e.g. a teleport's
 * {@code .exceptionally(...)}) that may complete just after
 * {@code onDisable}; and (2) a single thread gives a natural, cheap
 * ordering guarantee with no extra locking. {@link #writer} is kept open
 * across calls rather than opened and closed per line, and only rotated
 * when the date actually changes — see {@link #writerForToday}.
 *
 * <p>{@link #shutdown()} must be called from {@code Poppy#onDisable} to
 * flush and close the open file cleanly and stop accepting new log calls.
 */
public class PoppyLogger {

    public enum Category {
        HOME, TELEPORT, SHARE, TPA, DEATH, DEATH_CHEST, COMBAT, AFK, SLEEP, EASTER_EGG, ADMIN
    }

    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter FILE_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final JavaPlugin plugin;
    private final PoppyConfig config;
    private final File logsFolder;
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Poppy-Logger-IO");
        thread.setDaemon(true);
        return thread;
    });

    // Only ever touched from ioExecutor's single thread — no synchronization needed.
    private LocalDate currentFileDate;
    private BufferedWriter writer;
    private volatile boolean shutDown;

    public PoppyLogger(JavaPlugin plugin, PoppyConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.logsFolder = new File(plugin.getDataFolder(), "logs");
        if (!logsFolder.exists()) {
            logsFolder.mkdirs();
        }
    }

    public void log(Category category, Player player, String message) {
        log(category, player.getName(), message);
    }

    public void log(Category category, String actorName, String message) {
        if (shutDown) {
            return;
        }
        if (!config.loggingEnabled() || !config.loggingCategoryEnabled(category.name())) {
            return;
        }

        String line = "[" + LocalDateTime.now().format(TIMESTAMP_FORMAT) + "] [" + category.name() + "] " + actorName + ": " + message;

        if (config.loggingConsoleMirror()) {
            plugin.getLogger().info(line);
        }

        ioExecutor.submit(() -> writeLine(line));
    }

    /**
     * Only ever runs on {@link #ioExecutor}'s single thread.
     */
    private void writeLine(String line) {
        try {
            BufferedWriter activeWriter = writerForToday();
            activeWriter.write(line);
            activeWriter.newLine();
            activeWriter.flush();
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not write to Poppy log file", e);
        }
    }

    /**
     * Returns the writer for today's file, opening a new one only when the
     * date has actually changed since the last write (or on the very
     * first write) — the writer otherwise stays open across calls rather
     * than being reopened per line.
     */
    private BufferedWriter writerForToday() throws IOException {
        LocalDate today = LocalDate.now();
        if (writer != null && today.equals(currentFileDate)) {
            return writer;
        }

        closeWriterQuietly();

        currentFileDate = today;
        File file = new File(logsFolder, "poppy-" + today.format(FILE_DATE_FORMAT) + ".log");
        writer = new BufferedWriter(new FileWriter(file, true));
        return writer;
    }

    private void closeWriterQuietly() {
        if (writer == null) {
            return;
        }
        try {
            writer.close();
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not close Poppy log file", e);
        }
        writer = null;
    }

    /**
     * Stops accepting new log calls, flushes and closes the currently open
     * file, then shuts the executor down. Must be called from
     * {@code Poppy#onDisable} — without it, the last few buffered lines of
     * a session could be lost, and the file handle would leak.
     */
    public void shutdown() {
        shutDown = true;
        ioExecutor.submit(this::closeWriterQuietly);
        ioExecutor.shutdown();
    }
}