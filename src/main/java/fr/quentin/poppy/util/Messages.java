package fr.quentin.poppy.util;

import fr.quentin.poppy.util.io.AtomicYamlWriter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.ParsingException;
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
 * Loads every player-facing message from messages.yml.
 *
 * <p><b>Dual syntax:</b> a message can be written either in the classic
 * legacy {@code &}-code format ({@code &a}, {@code &c}, ...) or in
 * MiniMessage tag syntax ({@code <red>}, {@code <bold>},
 * {@code <gradient:blue:green>}, {@code <hover:show_text:'...'>},
 * {@code <click:run_command:'/home'>}, etc.) — {@link #get} auto-detects
 * which one a given string uses via {@link #MINIMESSAGE_TAG_PATTERN} and
 * deserializes with the matching parser. This is fully backward
 * compatible: every existing {@code &}-code message in messages.yml
 * keeps working exactly as before, untouched, while new messages (or
 * ones migrated over time) can opt into MiniMessage's richer feature set
 * — gradients, hover text, and click actions written directly in
 * messages.yml, without needing Java code changes for every new
 * clickable/hoverable message the way {@code ClickEvent}/{@code HoverEvent}
 * previously had to be built manually per call site.
 *
 * <p>A malformed MiniMessage tag (mismatched brackets, an unknown tag
 * name) never crashes message sending — {@link ParsingException} is
 * caught, logged as a warning naming the offending path, and the raw
 * string is deserialized as plain text instead, so a typo in
 * messages.yml degrades to ugly-but-harmless output rather than an
 * exception bubbling up through whatever command triggered it.
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
 * merges the jar's bundled defaults on top of the on-disk file, and only
 * writes the merged result back — via {@link AtomicYamlWriter}, atomic —
 * when {@link #hasMissingKeys} finds something the merge actually needed
 * to add, so an admin's file isn't needlessly rewritten (and re-risked)
 * on every single reload.
 *
 * <p>The {@code config} field is {@code volatile}: {@link #get} can be
 * called from async callbacks, and {@link #reload} replaces the whole
 * object rather than mutating it in place — {@code volatile} guarantees
 * every thread sees either the complete old reference or the complete new
 * one after a reload, never a half-updated view.
 *
 * <p>Placeholder substitution happens <b>after</b> deserialization (legacy
 * or MiniMessage, either way), and in a single combined-regex pass, not
 * sequential per-token string replaces — it operates on the resulting
 * {@link Component} tree via {@link Component#replaceText}, which works
 * identically regardless of which parser produced it.
 */
public class Messages {

    /**
     * Matches a plausible MiniMessage tag anywhere in a string — opening
     * ({@code <red>}, {@code <gradient:blue:green>}) or closing
     * ({@code </red>}), including tags carrying arguments. Deliberately
     * permissive rather than a full grammar: this is only a fast
     * "does this look like MiniMessage" gate before choosing a
     * deserializer, not a validator — {@link MiniMessage} itself
     * validates for real, and a false-positive match on ordinary text
     * containing a stray {@code <...>} would just get handed to
     * MiniMessage, which either parses it harmlessly or throws (caught
     * below, falling back to plain text).
     */
    private static final Pattern MINIMESSAGE_TAG_PATTERN = Pattern.compile("</?[a-zA-Z0-9_#][a-zA-Z0-9_:#/'\" .,\\-]*>");

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
     * in the jar's bundled copy but missing from the on-disk file.
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
     *
     * <p>Written in either legacy {@code &}-code or MiniMessage tag syntax
     * — see the class-level doc.
     */
    public Component get(String path, String... placeholders) {
        String raw = config.getString(path, path);

        Component result = deserialize(raw, path)
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

    /**
     * Picks MiniMessage or legacy {@code &}-code deserialization based on
     * whether {@code raw} looks like it contains a MiniMessage tag. A
     * MiniMessage parse failure (a malformed tag) is caught and logged,
     * falling back to plain-text deserialization of the same raw string
     * rather than letting the exception propagate.
     */
    private Component deserialize(String raw, String path) {
        if (!MINIMESSAGE_TAG_PATTERN.matcher(raw).find()) {
            return LegacyComponentSerializer.legacyAmpersand().deserialize(raw);
        }

        try {
            return MiniMessage.miniMessage().deserialize(raw);
        } catch (ParsingException e) {
            plugin.getLogger().log(Level.WARNING, "Malformed MiniMessage tag in messages.yml at '" + path + "' — showing plain text instead", e);
            return Component.text(raw);
        }
    }
}