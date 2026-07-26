package fr.quentin.poppy.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.intellij.lang.annotations.RegExp;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
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
 * <p>Placeholder substitution happens <b>after</b> legacy color-code
 * deserialization, and in a single combined-regex pass, not sequential
 * per-token string replaces. Two problems this avoids:
 * <ul>
 *   <li>Substituting into the raw string before parsing (the naive
 *   approach) would let untrusted values — player names in particular,
 *   which aren't under this plugin's control and can contain arbitrary
 *   characters via Bedrock/Geyser or other sources — inject their own
 *   {@code &} color codes, obfuscation, or newline-based fake chat lines
 *   into whatever message they're substituted into.</li>
 *   <li>Even after parsing first, replacing tokens one at a time in a
 *   loop is still order-dependent: if one placeholder's *value* happens
 *   to literally contain another token's text (e.g. a player somehow
 *   named exactly {@code {home}}), a later iteration of the loop could
 *   re-match and substitute inside the already-inserted value. A single
 *   combined regex matched once against the original template has no
 *   such cascade — every token is located and replaced from the same
 *   unmodified pass, so inserted values are never re-scanned.</li>
 * </ul>
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
                    // Inserted as a plain, unparsed text leaf — never re-run through the
                    // legacy color-code deserializer, so a substituted value can't inject
                    // its own formatting.
                    String key = matchResult.group(1);
                    return Component.text(values.getOrDefault(key, matchResult.group()));
                })
                .build());
    }
}