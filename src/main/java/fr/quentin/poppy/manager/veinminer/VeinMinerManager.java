package fr.quentin.poppy.manager.veinminer;

import fr.quentin.poppy.util.PoppyConfig;
import fr.quentin.poppy.util.io.AtomicYamlWriter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Tracks each player's personal veinminer preference — on top of the
 * plugin-wide {@code veinminer-enabled} switch in config.yml, each
 * player can turn the feature on/off for themselves via /veinminer. A
 * player who has never toggled it falls back to
 * {@code veinminer-default-enabled}.
 *
 * <p>Persisted to {@code veinminer.yml} with a single synchronous atomic
 * write per toggle — toggling isn't spammy enough to warrant the
 * debounced-write pattern the larger managers use, and it's gated by a
 * short cooldown at the command layer regardless.
 */
public class VeinMinerManager {

    private final JavaPlugin plugin;
    private final PoppyConfig config;
    private final File file;
    private final Map<UUID, Boolean> overrides = new HashMap<>();

    public VeinMinerManager(JavaPlugin plugin, PoppyConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.file = new File(plugin.getDataFolder(), "veinminer.yml");
        load();
    }

    public boolean isEnabled(UUID uuid) {
        return overrides.getOrDefault(uuid, config.veinminerDefaultEnabled());
    }

    /**
     * Flips the player's own veinminer preference and persists it.
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
                plugin.getLogger().log(Level.WARNING, "Skipping an invalid UUID in veinminer.yml: " + uuidString);
            }
        }
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, Boolean> entry : new HashMap<>(overrides).entrySet()) {
            yaml.set("players." + entry.getKey(), entry.getValue());
        }
        AtomicYamlWriter.save(yaml, file, plugin, "veinminer.yml");
    }
}