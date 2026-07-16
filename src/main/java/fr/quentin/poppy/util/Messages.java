package fr.quentin.poppy.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

/**
 * Loads every player-facing message from messages.yml so colors (using classic &a, &c, etc.
 * codes) can be tweaked without touching any Java code.
 */
public class Messages {

    private final File file;
    private YamlConfiguration config;

    public Messages(JavaPlugin plugin) {
        this.file = new File(plugin.getDataFolder(), "messages.yml");

        if (!file.exists()) {
            plugin.saveResource("messages.yml", false);
        }

        reload();
    }

    /**
     * Reloads messages.yml from disk (call this if you edit the file while the server is running
     * and want the changes without a restart).
     */
    public void reload() {
        config = YamlConfiguration.loadConfiguration(file);
    }

    /**
     * Gets a message by its dotted path (e.g. "sethome.success"), replacing any {placeholder}
     * with its value. Placeholders are passed as alternating key/value pairs, e.g.
     * get("sethome.success", "home", "base") replaces {home} with "base".
     */
    public Component get(String path, String... placeholders) {
        String raw = config.getString(path, path);

        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            raw = raw.replace("{" + placeholders[i] + "}", placeholders[i + 1]);
        }

        return LegacyComponentSerializer.legacyAmpersand()
                .deserialize(raw)
                .decoration(TextDecoration.ITALIC, false);
    }
}