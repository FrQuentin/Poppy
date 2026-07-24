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
import java.util.logging.Level;

/**
 * Writes detailed, per-event activity logs to daily-rotating files under
 * {@code plugins/Poppy/logs/}. Every category can be toggled independently
 * in config.yml, and logging as a whole can be turned off — all read live
 * via {@link PoppyConfig}, so {@code /poppy reload} applies immediately.
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

    private final Object writeLock = new Object();
    private LocalDate currentFileDate;
    private File currentFile;

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
        if (!config.loggingEnabled() || !config.loggingCategoryEnabled(category.name())) {
            return;
        }

        String line = "[" + LocalDateTime.now().format(TIMESTAMP_FORMAT) + "] [" + category.name() + "] " + actorName + ": " + message;

        if (config.loggingConsoleMirror()) {
            plugin.getLogger().info(line);
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> writeToFile(line));
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

    private File fileForToday() {
        LocalDate today = LocalDate.now();
        if (!today.equals(currentFileDate)) {
            currentFileDate = today;
            currentFile = new File(logsFolder, "poppy-" + today.format(FILE_DATE_FORMAT) + ".log");
        }
        return currentFile;
    }
}