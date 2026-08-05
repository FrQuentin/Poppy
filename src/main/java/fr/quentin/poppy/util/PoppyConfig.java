package fr.quentin.poppy.util;

import fr.quentin.poppy.util.io.AtomicYamlWriter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * Thin typed facade over {@code plugin.getConfig()}. Every value is read
 * from the {@link FileConfiguration} exactly once, at construction and on
 * every {@link #reload()} — not on every getter call, so hot paths
 * ({@code PlayerMoveEvent}, {@code EntityDamageEvent}, per-log-line reads,
 * etc.) never touch the underlying YAML structure directly. Getters just
 * return the cached {@code volatile} value; {@code /poppy reload} still
 * applies everywhere immediately, since that's exactly what triggers the
 * re-read.
 *
 * <p><b>Default-merging on {@link #reload()}:</b> a plugin update that
 * adds a new config.yml key used to leave that key permanently invisible
 * and uneditable for every existing install — the code's own
 * {@code getInt(key, fallback)} default kept the plugin behaving
 * correctly in memory, but an admin had no way to even discover the key
 * existed to customize it. This is exactly what happened with
 * {@code poppy-lore-cooldown-minutes}: read by this class with a
 * fallback default, but never actually present in the shipped
 * config.yml. {@link #reload} now merges the jar's bundled defaults on
 * top of the on-disk file — checked against a completely separate,
 * defaults-free {@link YamlConfiguration} read straight from disk, so
 * there's no ambiguity from Bukkit's {@code isSet()}/{@code contains()}
 * semantics under an attached {@code setDefaults} — and only writes the
 * merged result back (via {@link AtomicYamlWriter}, atomic) when
 * something was actually missing. Same pattern {@code Messages#reload}
 * already used for messages.yml; this closes the same class of bug
 * structurally so it can't recur silently for any future key.
 */
public final class PoppyConfig {

    private final JavaPlugin plugin;

    private volatile int teleportWarmupSeconds;
    private volatile boolean cancelOnMove;
    private volatile boolean cancelOnDamage;

    private volatile int sharehomeExpirySeconds;
    private volatile long sharehomeCooldownMillis;
    private volatile int sharehomeMaxFailedAttempts;
    private volatile long sharehomeLockoutMillis;

    private volatile boolean backOnDeath;

    private volatile int rtpMinRadius;
    private volatile int rtpMaxRadius;
    private volatile int rtpMaxAttempts;
    private volatile long rtpCooldownMillis;
    private volatile int rtpMaxConcurrentSearches;

    private volatile int trashSize;

    private volatile boolean customJoinMessage;
    private volatile boolean customQuitMessage;

    private volatile boolean showHealthInTab;
    private volatile long tabHealthUpdateIntervalTicks;

    private volatile boolean combatTagEnabled;
    private volatile long combatTagMillis;
    private volatile boolean combatLogPunishEnabled;
    private volatile boolean combatLogPunishOnKick;

    private volatile boolean afkAutoEnabled;
    private volatile long afkIdleMillis;
    private volatile long afkToggleCooldownMillis;

    private volatile boolean customUnknownCommandMessage;

    private volatile boolean deathCoordsEnabled;

    private volatile boolean deathChestEnabled;
    private volatile boolean deathChestProtect;
    private volatile boolean deathChestStoreXp;
    private volatile long deathChestExpiryMillis;
    private volatile int deathChestXpRefundPercent;
    private volatile boolean deathChestReadOnly;

    private volatile int tpaExpirySeconds;
    private volatile long tpaRequestCooldownMillis;
    private volatile double tpaMaxDistance;
    private volatile boolean tpaAllowCrossWorld;
    private volatile boolean tpaBlockToCombat;
    private volatile long tpaToggleCooldownMillis;

    private volatile long poppyLoreCooldownMillis;
    private volatile long poppyEasterEggCooldownMillis;

    private volatile int sleepPercentage;
    private volatile boolean sleepStatusMessageEnabled;
    private volatile long sleepStatusCooldownMillis;

    private volatile boolean loggingEnabled;
    private volatile boolean loggingConsoleMirror;
    private volatile int loggingFlushIntervalSeconds;
    private volatile int loggingRetentionDays;
    private volatile Map<String, Boolean> loggingCategories;

    private volatile int homesDefaultLimit;
    private volatile List<Integer> homesLimitTiers;

    private volatile boolean silkSpawnerEnabled;
    private volatile List<String> silkSpawnerBlacklist;

    private volatile long flyLockoutMillis;
    private volatile boolean flyParticlesEnabled;
    private volatile long flyMaxDurationMillis;
    private volatile long flyMaxDurationWarningSeconds;
    private volatile boolean flyForceDisableOnJoin;

    private volatile long feedCooldownMillis;
    private volatile long healCooldownMillis;

    private volatile long msgCooldownMillis;

    public PoppyConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        plugin.reloadConfig();
        FileConfiguration c = plugin.getConfig();

        // Merges any config.yml key present in the jar's bundled defaults but
        // missing from the on-disk file — see the class-level doc.
        File file = new File(plugin.getDataFolder(), "config.yml");
        try (InputStream in = plugin.getResource("config.yml")) {
            if (in != null) {
                YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
                YamlConfiguration rawFromDisk = YamlConfiguration.loadConfiguration(file);

                if (hasMissingKeys(rawFromDisk, defaults)) {
                    rawFromDisk.setDefaults(defaults);
                    rawFromDisk.options().copyDefaults(true);
                    AtomicYamlWriter.save(rawFromDisk, file, plugin, "config.yml");
                    // Already has everything merged in memory — no need to re-read
                    // the file we just wrote.
                    c = rawFromDisk;
                }
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not merge default config values into config.yml", e);
        }

        teleportWarmupSeconds = Math.max(0, c.getInt("teleport-warmup-seconds", 3));
        cancelOnMove = c.getBoolean("cancel-on-move", true);
        cancelOnDamage = c.getBoolean("cancel-on-damage", true);

        sharehomeExpirySeconds = Math.max(30, c.getInt("sharehome-expiry-seconds", 300));
        sharehomeCooldownMillis = Math.max(0, c.getInt("sharehome-cooldown-seconds", 30)) * 1000L;
        sharehomeMaxFailedAttempts = Math.max(1, c.getInt("sharehome-max-failed-attempts", 5));
        sharehomeLockoutMillis = Math.max(0, c.getInt("sharehome-lockout-seconds", 60)) * 1000L;

        backOnDeath = c.getBoolean("back-on-death", true);

        rtpMinRadius = Math.max(0, c.getInt("rtp-min-radius", 100));
        rtpMaxRadius = Math.max(rtpMinRadius + 1, c.getInt("rtp-max-radius", 5000));
        rtpMaxAttempts = Math.max(1, c.getInt("rtp-max-attempts", 20));
        rtpCooldownMillis = Math.max(0, c.getInt("rtp-cooldown-seconds", 30)) * 1000L;
        rtpMaxConcurrentSearches = Math.max(1, c.getInt("rtp-max-concurrent-searches", 3));

        trashSize = computeTrashSize(c.getInt("trash-size", 27));

        customJoinMessage = c.getBoolean("custom-join-message", true);
        customQuitMessage = c.getBoolean("custom-quit-message", true);

        showHealthInTab = c.getBoolean("show-health-in-tab", true);
        tabHealthUpdateIntervalTicks = Math.max(5, c.getLong("tab-health-update-interval-ticks", 20));

        combatTagEnabled = c.getBoolean("combat-tag-enabled", true);
        combatTagMillis = Math.max(0, c.getLong("combat-tag-seconds", 10)) * 1000L;
        combatLogPunishEnabled = c.getBoolean("combat-log-punish", true);
        combatLogPunishOnKick = c.getBoolean("combat-log-punish-on-kick", false);

        afkAutoEnabled = c.getBoolean("afk-auto-enabled", true);
        afkIdleMillis = Math.max(1, c.getLong("afk-auto-minutes", 5)) * 60L * 1000L;
        afkToggleCooldownMillis = Math.max(0, c.getInt("afk-toggle-cooldown-seconds", 5)) * 1000L;

        customUnknownCommandMessage = c.getBoolean("custom-unknown-command-message", true);

        deathCoordsEnabled = c.getBoolean("death-coords-enabled", true);

        deathChestEnabled = c.getBoolean("death-chest-enabled", true);
        deathChestProtect = c.getBoolean("death-chest-protect", true);
        deathChestStoreXp = c.getBoolean("death-chest-store-xp", true);
        deathChestExpiryMillis = Math.max(0, c.getInt("death-chest-expiry-minutes", 30)) * 60L * 1000L;
        deathChestXpRefundPercent = Math.clamp(c.getInt("death-chest-xp-refund-percent", 50), 0, 100);
        deathChestReadOnly = c.getBoolean("death-chest-read-only", true);

        tpaExpirySeconds = Math.max(5, c.getInt("tpa-expiry-seconds", 60));
        tpaRequestCooldownMillis = Math.max(0, c.getInt("tpa-request-cooldown-seconds", 5)) * 1000L;
        tpaMaxDistance = Math.max(0, c.getDouble("tpa-max-distance", 0));
        tpaAllowCrossWorld = c.getBoolean("tpa-allow-cross-world", true);
        tpaBlockToCombat = c.getBoolean("tpa-block-to-combat", true);
        tpaToggleCooldownMillis = Math.max(0, c.getInt("tpa-toggle-cooldown-seconds", 3)) * 1000L;

        poppyLoreCooldownMillis = Math.max(0, c.getInt("poppy-lore-cooldown-minutes", 30)) * 60L * 1000L;
        poppyEasterEggCooldownMillis = Math.max(0, c.getInt("poppy-easteregg-cooldown-seconds", 3)) * 1000L;

        sleepPercentage = Math.clamp(c.getInt("sleep-percentage", 50), 0, 100);
        sleepStatusMessageEnabled = c.getBoolean("sleep-status-message-enabled", true);
        sleepStatusCooldownMillis = Math.max(0, c.getInt("sleep-status-cooldown-seconds", 5)) * 1000L;

        loggingEnabled = c.getBoolean("logging.enabled", true);
        loggingConsoleMirror = c.getBoolean("logging.console-mirror", false);
        loggingFlushIntervalSeconds = Math.max(1, c.getInt("logging.flush-interval-seconds", 3));
        loggingRetentionDays = Math.max(0, c.getInt("logging.retention-days", 30));
        loggingCategories = buildLoggingCategories(c);

        homesDefaultLimit = c.getInt("homes-default-limit", 10);
        homesLimitTiers = List.copyOf(c.getIntegerList("homes-limit-tiers"));

        silkSpawnerEnabled = c.getBoolean("silkspawner-enabled", true);
        silkSpawnerBlacklist = List.copyOf(c.getStringList("silkspawner-blacklist"));

        flyLockoutMillis = Math.max(0, c.getInt("fly-lockout-seconds", 30)) * 1000L;
        flyParticlesEnabled = c.getBoolean("fly-particles-enabled", true);
        flyMaxDurationMillis = Math.max(0, c.getInt("fly-max-duration-minutes", 30)) * 60L * 1000L;
        flyMaxDurationWarningSeconds = Math.max(0, c.getInt("fly-max-duration-warning-seconds", 30));
        flyForceDisableOnJoin = c.getBoolean("fly-force-disable-on-join", true);

        feedCooldownMillis = Math.max(0, c.getInt("feed-cooldown-seconds", 180)) * 1000L;
        healCooldownMillis = Math.max(0, c.getInt("heal-cooldown-seconds", 180)) * 1000L;

        msgCooldownMillis = Math.max(0, c.getInt("msg-cooldown-seconds", 2)) * 1000L;
    }

    /**
     * True if the on-disk file is missing any key present in the bundled
     * defaults — {@code loaded} must never have had {@code setDefaults}
     * called on it, so {@code isSet} here can only ever reflect what's
     * truly present in the physical file, with zero fallback ambiguity.
     */
    private boolean hasMissingKeys(YamlConfiguration loaded, YamlConfiguration defaults) {
        for (String key : defaults.getKeys(true)) {
            if (!loaded.isSet(key)) {
                return true;
            }
        }
        return false;
    }

    private int computeTrashSize(int configured) {
        int normalized = (configured / 9) * 9;
        if (normalized < 9) {
            normalized = 27;
        }
        return Math.min(54, normalized);
    }

    private Map<String, Boolean> buildLoggingCategories(FileConfiguration c) {
        Map<String, Boolean> categories = new HashMap<>();
        for (PoppyLogger.Category category : PoppyLogger.Category.values()) {
            categories.put(category.name(), c.getBoolean("logging.categories." + category.name().toLowerCase(), true));
        }
        return Map.copyOf(categories);
    }

    public int teleportWarmupSeconds() {
        return teleportWarmupSeconds;
    }

    public boolean cancelOnMove() {
        return cancelOnMove;
    }

    public boolean cancelOnDamage() {
        return cancelOnDamage;
    }

    public int sharehomeExpirySeconds() {
        return sharehomeExpirySeconds;
    }

    public long sharehomeCooldownMillis() {
        return sharehomeCooldownMillis;
    }

    public int sharehomeMaxFailedAttempts() {
        return sharehomeMaxFailedAttempts;
    }

    public long sharehomeLockoutMillis() {
        return sharehomeLockoutMillis;
    }

    public boolean backOnDeath() {
        return backOnDeath;
    }

    public int rtpMinRadius() {
        return rtpMinRadius;
    }

    public int rtpMaxRadius() {
        return rtpMaxRadius;
    }

    public int rtpMaxAttempts() {
        return rtpMaxAttempts;
    }

    public long rtpCooldownMillis() {
        return rtpCooldownMillis;
    }

    public int rtpMaxConcurrentSearches() {
        return rtpMaxConcurrentSearches;
    }

    public int trashSize() {
        return trashSize;
    }

    public boolean customJoinMessage() {
        return customJoinMessage;
    }

    public boolean customQuitMessage() {
        return customQuitMessage;
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean showHealthInTab() {
        return showHealthInTab;
    }

    public long tabHealthUpdateIntervalTicks() {
        return tabHealthUpdateIntervalTicks;
    }

    public boolean combatTagEnabled() {
        return combatTagEnabled;
    }

    public long combatTagMillis() {
        return combatTagMillis;
    }

    public boolean combatLogPunishEnabled() {
        return combatLogPunishEnabled;
    }

    public boolean combatLogPunishOnKick() {
        return combatLogPunishOnKick;
    }

    public boolean afkAutoEnabled() {
        return afkAutoEnabled;
    }

    public long afkIdleMillis() {
        return afkIdleMillis;
    }

    public long afkToggleCooldownMillis() {
        return afkToggleCooldownMillis;
    }

    public boolean customUnknownCommandMessage() {
        return customUnknownCommandMessage;
    }

    public boolean deathCoordsEnabled() {
        return deathCoordsEnabled;
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean deathChestEnabled() {
        return deathChestEnabled;
    }

    public boolean deathChestProtect() {
        return deathChestProtect;
    }

    public boolean deathChestStoreXp() {
        return deathChestStoreXp;
    }

    public long deathChestExpiryMillis() {
        return deathChestExpiryMillis;
    }

    public int deathChestXpRefundPercent() {
        return deathChestXpRefundPercent;
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean deathChestReadOnly() {
        return deathChestReadOnly;
    }

    public int tpaExpirySeconds() {
        return tpaExpirySeconds;
    }

    public long tpaRequestCooldownMillis() {
        return tpaRequestCooldownMillis;
    }

    public double tpaMaxDistance() {
        return tpaMaxDistance;
    }

    public boolean tpaAllowCrossWorld() {
        return tpaAllowCrossWorld;
    }

    public boolean tpaBlockToCombat() {
        return tpaBlockToCombat;
    }

    public long tpaToggleCooldownMillis() {
        return tpaToggleCooldownMillis;
    }

    public long poppyLoreCooldownMillis() {
        return poppyLoreCooldownMillis;
    }

    public long poppyEasterEggCooldownMillis() {
        return poppyEasterEggCooldownMillis;
    }

    public int sleepPercentage() {
        return sleepPercentage;
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean sleepStatusMessageEnabled() {
        return sleepStatusMessageEnabled;
    }

    public long sleepStatusCooldownMillis() {
        return sleepStatusCooldownMillis;
    }

    public boolean loggingEnabled() {
        return loggingEnabled;
    }

    public boolean loggingConsoleMirror() {
        return loggingConsoleMirror;
    }

    public int loggingFlushIntervalSeconds() {
        return loggingFlushIntervalSeconds;
    }

    public int loggingRetentionDays() {
        return loggingRetentionDays;
    }

    public boolean loggingCategoryEnabled(String categoryName) {
        return loggingCategories.getOrDefault(categoryName, true);
    }

    public int homesDefaultLimit() {
        return homesDefaultLimit;
    }

    public List<Integer> homesLimitTiers() {
        return homesLimitTiers;
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean silkSpawnerEnabled() {
        return silkSpawnerEnabled;
    }

    public List<String> silkSpawnerBlacklist() {
        return silkSpawnerBlacklist;
    }

    public long flyLockoutMillis() {
        return flyLockoutMillis;
    }

    public boolean flyParticlesEnabled() {
        return flyParticlesEnabled;
    }

    public long flyMaxDurationMillis() {
        return flyMaxDurationMillis;
    }

    public long flyMaxDurationWarningSeconds() {
        return flyMaxDurationWarningSeconds;
    }

    public boolean flyForceDisableOnJoin() {
        return flyForceDisableOnJoin;
    }

    public long feedCooldownMillis() {
        return feedCooldownMillis;
    }

    public long healCooldownMillis() {
        return healCooldownMillis;
    }

    public long msgCooldownMillis() {
        return msgCooldownMillis;
    }
}