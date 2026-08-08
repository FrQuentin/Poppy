package fr.quentin.poppy.manager.treecapitator;

import fr.quentin.poppy.util.PoppyConfig;
import fr.quentin.poppy.util.io.AtomicYamlWriter;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Tracks each player's personal treecapitator preference — same pattern
 * as {@code VeinMinerManager}: a plugin-wide {@code treecapitator-enabled}
 * switch in config.yml, plus a per-player toggle via /treecapitator. A
 * player who has never toggled it falls back to
 * {@code treecapitator-default-enabled}.
 *
 * <p>Persisted to {@code treecapitator.yml} with a single synchronous
 * atomic write per toggle.
 */
public class TreeCapitatorManager {

    private final JavaPlugin plugin;
    private final PoppyConfig config;
    private final File file;
    private final Map<UUID, Boolean> overrides = new HashMap<>();

    public TreeCapitatorManager(JavaPlugin plugin, PoppyConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.file = new File(plugin.getDataFolder(), "treecapitator.yml");
        load();
    }

    public boolean isEnabled(UUID uuid) {
        return overrides.getOrDefault(uuid, config.treecapitatorDefaultEnabled());
    }

    /**
     * Flips the player's own treecapitator preference and persists it.
     *
     * @return the new state.
     */
    public boolean toggle(UUID uuid) {
        boolean newState = !isEnabled(uuid);
        overrides.put(uuid, newState);
        save();
        return newState;
    }

    private void load() {
        if (!file.exists()) {
            return;
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection("players");
        if (section == null) {
            return;
        }

        for (String uuidString : section.getKeys(false)) {
            try {
                overrides.put(UUID.fromString(uuidString), section.getBoolean(uuidString));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().log(Level.WARNING, "Skipping an invalid UUID in treecapitator.yml: " + uuidString);
            }
        }
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, Boolean> entry : new HashMap<>(overrides).entrySet()) {
            yaml.set("players." + entry.getKey(), entry.getValue());
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> AtomicYamlWriter.save(yaml, file, plugin, "treecapitator.yml"));
    }
}