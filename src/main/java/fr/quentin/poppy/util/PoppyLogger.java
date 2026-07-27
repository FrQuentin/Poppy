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
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Writes detailed, per-event activity logs to daily-rotating files under
 * {@code plugins/Poppy/logs/}. Every category can be toggled independently
 * in config.yml, and logging as a whole can be turned off — all read live
 * via {@link PoppyConfig}, so {@code /poppy reload} applies immediately.
 *
 * <p>All I/O runs on {@link #ioExecutor}, a single-thread scheduled
 * executor — the same pattern {@link fr.quentin.poppy.manager.HomeManager}
 * uses, for the same two reasons: (1) it avoids ever calling Bukkit's own
 * scheduler, which throws {@code IllegalPluginAccessException} immediately
 * if scheduled after the plugin has been disabled — a real risk here
 * since {@link #log} can be called from async callbacks that may complete
 * just after {@code onDisable}; and (2) a single thread gives a natural,
 * cheap ordering guarantee with no extra locking. Being a
 * {@link ScheduledExecutorService} rather than a plain one also lets
 * {@link #flushTask} run periodically on this same thread, with no need
 * to involve Bukkit's scheduler for that either.
 *
 * <p>{@link #writer} is kept open across calls rather than opened and
 * closed per line, and only rotated when the date actually changes — see
 * {@link #writerForToday}. {@link #writeLine} deliberately does
 * <b>not</b> flush after every line: on a busy server (combat, teleport,
 * AFK, death, sleep are all logged by default), that was dozens of
 * {@code fsync}-equivalent disk flushes per second for no real benefit —
 * a shared/hosted disk feels that. Durability is instead handled by
 * {@link #flushTask}, a periodic flush every
 * {@code logging.flush-interval-seconds} (config.yml), plus an
 * unconditional final flush at {@link #shutdown()} (via
 * {@link BufferedWriter#close()}, which flushes before closing).
 *
 * <p>{@link #purgeOldLogs()} runs once at construction, deleting any
 * {@code poppy-YYYY-MM-DD.log} file older than
 * {@code logging.retention-days} (config.yml, 30 by default; 0 keeps
 * every file forever) — without this, daily log files accumulate
 * indefinitely, and combined with any spam vector that generates a lot of
 * log lines, that can add up to a meaningful amount of disk space over
 * months.
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
    private static final String FILE_PREFIX = "poppy-";
    private static final String FILE_SUFFIX = ".log";

    private final JavaPlugin plugin;
    private final PoppyConfig config;
    private final File logsFolder;
    private final ScheduledExecutorService ioExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Poppy-Logger-IO");
        thread.setDaemon(true);
        return thread;
    });

    // Only ever touched from ioExecutor's single thread — no synchronization needed.
    private LocalDate currentFileDate;
    private BufferedWriter writer;
    private volatile boolean shutDown;

    private final ScheduledFuture<?> flushTask;

    public PoppyLogger(JavaPlugin plugin, PoppyConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.logsFolder = new File(plugin.getDataFolder(), "logs");
        if (!logsFolder.exists() && !logsFolder.mkdirs()) {
            plugin.getLogger().warning("Could not create the logs folder: " + logsFolder);
        }

        ioExecutor.execute(this::purgeOldLogs);

        long intervalSeconds = config.loggingFlushIntervalSeconds();
        flushTask = ioExecutor.scheduleAtFixedRate(this::flushPeriodically, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
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

        try {
            ioExecutor.execute(() -> writeLine(line));
        } catch (RejectedExecutionException e) {
            plugin.getLogger().log(Level.WARNING, "Could not queue a log line (I/O executor already shut down)", e);
        }
    }

    /**
     * Only ever runs on {@link #ioExecutor}'s single thread. Does not
     * flush — see the class-level doc on why that's handled by
     * {@link #flushTask} instead.
     */
    @SuppressWarnings("resource")
    private void writeLine(String line) {
        try {
            BufferedWriter activeWriter = writerForToday();
            activeWriter.write(line);
            activeWriter.newLine();
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not write to Poppy log file", e);
        }
    }

    /**
     * Periodic durability flush — only ever runs on {@link #ioExecutor}'s
     * single thread, same as every other method touching {@link #writer}.
     */
    private void flushPeriodically() {
        if (writer == null) {
            return;
        }
        try {
            writer.flush();
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not flush Poppy log file", e);
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
        File file = new File(logsFolder, FILE_PREFIX + today.format(FILE_DATE_FORMAT) + FILE_SUFFIX);
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
     * Deletes any {@code poppy-YYYY-MM-DD.log} file older than
     * {@code logging.retention-days} — see the class-level doc. Runs once
     * at construction, off the main thread via {@link #ioExecutor}. A file
     * whose name doesn't parse as one of ours (manually renamed,
     * unexpected format) is left alone rather than guessed at.
     */
    private void purgeOldLogs() {
        int retentionDays = config.loggingRetentionDays();
        if (retentionDays <= 0) {
            return;
        }

        File[] files = logsFolder.listFiles((_, name) -> name.startsWith(FILE_PREFIX) && name.endsWith(FILE_SUFFIX));
        if (files == null) {
            return;
        }

        LocalDate cutoff = LocalDate.now().minusDays(retentionDays);
        for (File file : files) {
            try {
                String datePart = file.getName().substring(FILE_PREFIX.length(), file.getName().length() - FILE_SUFFIX.length());
                LocalDate fileDate = LocalDate.parse(datePart, FILE_DATE_FORMAT);
                if (fileDate.isBefore(cutoff) && !file.delete()) {
                    plugin.getLogger().warning("Could not delete an old Poppy log file: " + file.getName());
                }
            } catch (Exception e) {
                // Not a recognized poppy-YYYY-MM-DD.log file name — skip it rather than guess.
            }
        }
    }

    /**
     * Stops accepting new log calls, cancels the periodic flush, flushes
     * and closes the currently open file (via {@link BufferedWriter#close()},
     * which flushes first), then shuts the executor down. Must be called
     * from {@code Poppy#onDisable} — without it, the last few buffered
     * lines of a session could be lost, and the file handle would leak.
     */
    public void shutdown() {
        shutDown = true;
        flushTask.cancel(false);
        ioExecutor.execute(this::closeWriterQuietly);
        ioExecutor.shutdown();
    }
}