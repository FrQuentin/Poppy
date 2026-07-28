package fr.quentin.poppy.util;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.logging.Level;

/**
 * Shared temp-file-then-atomic-rename YAML writer — the same pattern used
 * to live duplicated, nearly identically, across {@code HomeManager},
 * {@code SpawnManager}, and {@code DeathChestManager}. A crash,
 * out-of-disk-space error, or forced kill mid-write can otherwise leave a
 * truncated, unparsable file behind; a rename on the same filesystem is
 * atomic at the OS level, so readers only ever see the fully-old or
 * fully-new file, never a half-written one. Falls back to a plain
 * (non-atomic) move if the filesystem doesn't support atomic moves (some
 * Docker overlay filesystems, some network mounts).
 */
public final class AtomicYamlWriter {

    private AtomicYamlWriter() {
    }

    /**
     * @param context short label used only in the log message if the write
     *                fails, e.g. {@code "homes for <uuid>"} or {@code "spawn.yml"}
     */
    public static void save(YamlConfiguration config, File file, JavaPlugin plugin, String context) {
        File tempFile = new File(file.getParentFile(), file.getName() + ".tmp");

        try {
            config.save(tempFile);
            try {
                Files.move(tempFile.toPath(), file.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tempFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save " + context, e);
            if (!tempFile.delete()) {
                plugin.getLogger().log(Level.WARNING, "Could not delete leftover temp file: " + tempFile);
            }
        }
    }
}