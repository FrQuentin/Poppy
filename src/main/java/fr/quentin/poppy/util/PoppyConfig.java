package fr.quentin.poppy.util;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;

/**
 * Thin typed facade over {@code plugin.getConfig()}. Every value is read
 * from the {@link FileConfiguration} exactly once, at construction and on
 * every {@link #reload()} — not on every getter call.
 *
 * <p>The original design re-read {@code plugin.getConfig()} on every
 * single getter call, so {@code /poppy reload} would apply immediately
 * everywhere with zero extra plumbing. That's simple, but it was applied
 * uniformly even on genuinely hot paths: {@code cancelOnMove()} /
 * {@code cancelOnDamage()} on every {@code PlayerMoveEvent}/
 * {@code EntityDamageEvent} (dozens per second per player),
 * {@code deathChestEnabled()} at the top of eight different
 * {@code DeathChestManager} handlers including {@code onChunkLoad}, and
 * two config reads per {@link PoppyLogger#log} call — each individual read
 * is cheap (a {@code MemorySection} path split, a map lookup), but the
 * aggregate cost across a busy server is real and hard to profile because
 * it's spread across dozens of call sites rather than concentrated in one
 * place.
 *
 * <p>Every value is now read once per {@link #reload()} into a
 * {@code volatile} field (or an immutable {@link List}/{@link Map} behind
 * one), and getters just return the cached value — {@code /poppy reload}
 * still applies everywhere immediately (it's what triggers the re-read),
 * but normal operation never touches {@link FileConfiguration} at all.
 * {@code volatile} also makes this class safe to read from a thread other
 * than the main one — {@link PoppyLogger} does exactly that from its own
 * I/O executor thread.
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
    private volatile long flyMaxDurationCooldownMillis;
    private volatile long flyMaxDurationWarningSeconds;

    private volatile long feedCooldownMillis;
    private volatile long healCooldownMillis;

    private volatile boolean flyForceDisableOnJoin;

    private volatile boolean combatLogPunishOnKick;

    private volatile long economyMaxMoney;
    private volatile long economyMinMoney;
    private volatile long economyStartingMoney;
    private volatile int economyBaltopSize;

    public PoppyConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        plugin.reloadConfig();
        FileConfiguration c = plugin.getConfig();

        flyForceDisableOnJoin = c.getBoolean("fly-force-disable-on-join", true);

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
        flyMaxDurationCooldownMillis = Math.max(0, c.getInt("fly-max-duration-cooldown-minutes", 60)) * 60L * 1000L;
        flyMaxDurationWarningSeconds = Math.max(0, c.getInt("fly-max-duration-warning-seconds", 30));

        feedCooldownMillis = Math.max(0, c.getInt("feed-cooldown-seconds", 180)) * 1000L;
        healCooldownMillis = Math.max(0, c.getInt("heal-cooldown-seconds", 180)) * 1000L;

        combatLogPunishOnKick = c.getBoolean("combat-log-punish-on-kick", false);

        economyMaxMoney = c.getLong("max-money", 10_000_000_000_000L);
        economyMinMoney = c.getLong("min-money", 0L);
        economyStartingMoney = c.getLong("starting-money", 0L);
        economyBaltopSize = Math.max(1, c.getInt("baltop-size", 10));
    }

    private int computeTrashSize(int configured) {
        int normalized = (configured / 9) * 9;
        if (normalized < 9) {
            normalized = 27;
        }
        return Math.min(54, normalized);
    }

    /**
     * Eagerly resolves every known {@link PoppyLogger.Category} at reload
     * time, rather than reading {@code logging.categories.<name>} lazily
     * per call — the exact hot-path cost {@link PoppyLogger#log} was
     * paying twice per log line.
     */
    private Map<String, Boolean> buildLoggingCategories(FileConfiguration c) {
        Map<String, Boolean> categories = new java.util.HashMap<>();
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

    public int trashSize() {
        return trashSize;
    }

    public boolean customJoinMessage() {
        return customJoinMessage;
    }

    public boolean customQuitMessage() {
        return customQuitMessage;
    }

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

    public long poppyLoreCooldownMillis() {
        return poppyLoreCooldownMillis;
    }

    public long poppyEasterEggCooldownMillis() {
        return poppyEasterEggCooldownMillis;
    }

    public int sleepPercentage() {
        return sleepPercentage;
    }

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

    public long feedCooldownMillis() {
        return feedCooldownMillis;
    }

    public long healCooldownMillis() {
        return healCooldownMillis;
    }

    public boolean tpaBlockToCombat() {
        return tpaBlockToCombat;
    }

    public boolean flyForceDisableOnJoin() {
        return flyForceDisableOnJoin;
    }

    public boolean combatLogPunishOnKick() {
        return combatLogPunishOnKick;
    }

    public int rtpMaxConcurrentSearches() {
        return rtpMaxConcurrentSearches;
    }

    public long flyMaxDurationMillis() {
        return flyMaxDurationMillis;
    }

    public long flyMaxDurationCooldownMillis() {
        return flyMaxDurationCooldownMillis;
    }

    public long flyMaxDurationWarningSeconds() {
        return flyMaxDurationWarningSeconds;
    }

    public long tpaToggleCooldownMillis() {
        return tpaToggleCooldownMillis;
    }

    public boolean deathChestReadOnly() {
        return deathChestReadOnly;
    }

    public long economyMaxMoney() {
        return economyMaxMoney;
    }

    public long economyMinMoney() {
        return economyMinMoney;
    }

    public long economyStartingMoney() {
        return economyStartingMoney;
    }

    public int economyBaltopSize() {
        return economyBaltopSize;
    }
}