package fr.quentin.poppy.util;

import fr.quentin.poppy.util.io.AtomicYamlWriter;
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
 * a plugin update that adds new message keys would otherwise leave those
 * keys permanently missing for every existing install. {@link #reload}
 * merges the jar's bundled defaults on top of the on-disk file via
 * {@link YamlConfiguration#setDefaults} + {@code copyDefaults(true)}.
 *
 * <p>The merged result is only written back to disk — via
 * {@link AtomicYamlWriter}, not a raw {@code save(file)} — when
 * {@link #hasMissingKeys} finds something the merge actually needed to
 * add. Two things this fixes over the original version: (1) an
 * unconditional {@code save()} here was the one file in this plugin an
 * admin hand-edits and had no crash-safety at all — a truncated write
 * mid-save would corrupt their customizations with no recovery, unlike
 * every other persisted file which already used the atomic
 * temp-file-then-rename pattern; (2) {@code reload()} runs on every
 * startup and every {@code /poppy reload}, so rewriting the file every
 * single time — even when nothing changed — needlessly re-risked it for
 * no reason. Skipping the write entirely when there's nothing to merge
 * closes both: the file is only ever touched when it actually needs to
 * change, and that touch is now atomic.
 *
 * <p><b>Known, unverified caveat:</b> whether {@code copyDefaults(true)} +
 * {@code save()} preserves an admin's own {@code #} comments in
 * messages.yml depends on the server/Bukkit version and isn't something
 * this class can guarantee from the API alone. Worth checking manually on
 * your exact server version: add a comment to messages.yml, add a new key
 * to the bundled defaults (or delete an existing line to force a merge),
 * run {@code /poppy reload}, and confirm the comment survived.
 *
 * <p>The {@code config} field is {@code volatile}: {@link #get} can be
 * called from async callbacks, and {@link #reload} replaces the whole
 * object rather than mutating it in place — {@code volatile} guarantees
 * every thread sees either the complete old reference or the complete new
 * one after a reload, never a half-updated view.
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

                if (hasMissingKeys(loaded, defaults)) {
                    AtomicYamlWriter.save(loaded, file, plugin, "messages.yml");
                }
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not merge default messages into messages.yml", e);
        }

        config = loaded;
    }

    /**
     * True if the on-disk file is missing any key present in the bundled
     * defaults — {@link org.bukkit.configuration.MemorySection#isSet}
     * (unlike {@code contains}) ignores values that only come from
     * defaults, so this reliably detects "would {@code save()} actually
     * add anything new" before ever touching the disk.
     */
    private boolean hasMissingKeys(YamlConfiguration loaded, YamlConfiguration defaults) {
        for (String key : defaults.getKeys(true)) {
            if (!loaded.isSet(key)) {
                return true;
            }
        }
        return false;
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
                .replacement((matchResult, _) -> {
                    String key = matchResult.group(1);
                    return Component.text(values.getOrDefault(key, matchResult.group()));
                })
                .build());
    }
}