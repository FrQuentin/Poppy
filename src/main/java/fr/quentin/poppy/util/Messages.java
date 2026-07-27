package fr.quentin.poppy.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.intellij.lang.annotations.RegExp;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.regex.Pattern;

/**
 * Loads every player-facing message from messages.yml so colors (using classic &a, &c, etc.
 * codes) can be tweaked without touching any Java code.
 *
 * <p>If a requested path is missing from messages.yml, {@link #get} falls
 * back to displaying the path itself rather than an empty string or an
 * exception — an admin who broke their messages.yml sees exactly which key
 * is missing, in-game, instead of a silent blank message.
 *
 * <p><b>Default-merging on {@link #reload()}:</b> the file is only ever
 * copied from the jar once, the first time it doesn't exist — after that,
 * a plugin update that adds new message keys (or an admin who deleted a
 * line) would otherwise leave those keys permanently missing for every
 * existing install, showing the raw path in-game instead of real text
 * (e.g. a literal {@code death.chest-gui-title} as a chest's title) until
 * they're manually re-added. {@link #reload} merges the jar's bundled
 * defaults on top of the on-disk file via
 * {@link YamlConfiguration#setDefaults} +
 * {@link org.bukkit.configuration.ConfigurationOptions#copyDefaults(boolean)},
 * then saves the merged result back to disk — an admin's existing
 * customizations are preserved (they take priority over the defaults
 * being merged in), and any newly-added key gets its default value
 * written to their file automatically, visible for them to edit going
 * forward rather than silently falling back forever.
 *
 * <p>The {@code config} field is {@code volatile}: {@link #get} can be
 * called from async callbacks (e.g. a teleport's {@code .exceptionally(...)}),
 * and {@link #reload} replaces the whole object rather than mutating it in
 * place — {@code volatile} guarantees every thread sees either the
 * complete old reference or the complete new one after a reload, never a
 * half-updated view.
 *
 * <p>Placeholder substitution happens <b>after</b> legacy color-code
 * deserialization, and in a single combined-regex pass, not sequential
 * per-token string replaces — see {@link #get} for why.
 */
public class Messages {

    private final JavaPlugin plugin;
    private final File file;
    private volatile YamlConfiguration config;

    public Messages(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "messages.yml");

        if (!file.exists()) {
            plugin.saveResource("messages.yml", false);
        }

        reload();
    }

    /**
     * Reloads messages.yml from disk, merging in any message key present
     * in the jar's bundled copy but missing from the on-disk file — see
     * the class-level doc.
     */
    public void reload() {
        YamlConfiguration loaded = YamlConfiguration.loadConfiguration(file);

        try (InputStream in = plugin.getResource("messages.yml")) {
            if (in != null) {
                YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
                loaded.setDefaults(defaults);
                loaded.options().copyDefaults(true);
                loaded.save(file);
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not merge default messages into messages.yml", e);
        }

        config = loaded;
    }

    /**
     * Gets a message by its dotted path (e.g. "sethome.success"), replacing any {placeholder}
     * with its value. Placeholders are passed as alternating key/value pairs, e.g.
     * get("sethome.success", "home", "base") replaces {home} with "base".
     */
    public Component get(String path, String... placeholders) {
        String raw = config.getString(path, path);

        Component result = LegacyComponentSerializer.legacyAmpersand()
                .deserialize(raw)
                .decoration(TextDecoration.ITALIC, false);

        if (placeholders.length < 2) {
            return result;
        }

        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            values.put(placeholders[i], placeholders[i + 1]);
        }

        @RegExp String combinedPattern = "\\{(" + String.join("|", values.keySet().stream().map(Pattern::quote).toArray(String[]::new)) + ")}";

        return result.replaceText(TextReplacementConfig.builder()
                .match(combinedPattern)
                .replacement((matchResult, builder) -> {
                    String key = matchResult.group(1);
                    return Component.text(values.getOrDefault(key, matchResult.group()));
                })
                .build());
    }
}