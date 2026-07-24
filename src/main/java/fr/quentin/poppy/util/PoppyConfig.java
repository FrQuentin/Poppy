package fr.quentin.poppy.util;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * Thin typed facade over {@code plugin.getConfig()}. Every getter reads
 * live from the current FileConfiguration rather than caching a value at
 * construction time — this is what makes {@code /poppy reload} actually
 * work: {@link #reload()} just calls {@link JavaPlugin#reloadConfig()},
 * and every class holding a {@code PoppyConfig} reference immediately
 * sees the new values on its next getter call, with no extra wiring.
 *
 * <p>A few settings can't be made live this simply and need an explicit
 * nudge after reload — see {@code SleepPercentageListener#reapply} for the
 * gamerule case, and {@code Messages#reload} for messages.yml.
 */
public final class PoppyConfig {

    private final JavaPlugin plugin;

    public PoppyConfig(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        plugin.reloadConfig();
    }

    public int teleportWarmupSeconds() {
        return Math.max(0, plugin.getConfig().getInt("teleport-warmup-seconds", 3));
    }

    public boolean cancelOnMove() {
        return plugin.getConfig().getBoolean("cancel-on-move", true);
    }

    public boolean cancelOnDamage() {
        return plugin.getConfig().getBoolean("cancel-on-damage", true);
    }

    public int sharehomeExpirySeconds() {
        return Math.max(30, plugin.getConfig().getInt("sharehome-expiry-seconds", 300));
    }

    public long sharehomeCooldownMillis() {
        return Math.max(0, plugin.getConfig().getInt("sharehome-cooldown-seconds", 30)) * 1000L;
    }

    public boolean backOnDeath() {
        return plugin.getConfig().getBoolean("back-on-death", true);
    }

    public int rtpMinRadius() {
        return Math.max(0, plugin.getConfig().getInt("rtp-min-radius", 100));
    }

    public int rtpMaxRadius() {
        return Math.max(rtpMinRadius() + 1, plugin.getConfig().getInt("rtp-max-radius", 5000));
    }

    public int rtpMaxAttempts() {
        return Math.max(1, plugin.getConfig().getInt("rtp-max-attempts", 20));
    }

    public long rtpCooldownMillis() {
        return Math.max(0, plugin.getConfig().getInt("rtp-cooldown-seconds", 30)) * 1000L;
    }

    public int trashSize() {
        int configured = plugin.getConfig().getInt("trash-size", 27);
        int normalized = (configured / 9) * 9;
        if (normalized < 9) {
            normalized = 27;
        }
        return Math.min(54, normalized);
    }

    public boolean customJoinMessage() {
        return plugin.getConfig().getBoolean("custom-join-message", true);
    }

    public boolean customQuitMessage() {
        return plugin.getConfig().getBoolean("custom-quit-message", true);
    }

    public boolean showHealthInTab() {
        return plugin.getConfig().getBoolean("show-health-in-tab", true);
    }

    public long tabHealthUpdateIntervalTicks() {
        return Math.max(5, plugin.getConfig().getLong("tab-health-update-interval-ticks", 20));
    }

    public boolean combatTagEnabled() {
        return plugin.getConfig().getBoolean("combat-tag-enabled", true);
    }

    public long combatTagMillis() {
        return Math.max(0, plugin.getConfig().getLong("combat-tag-seconds", 10)) * 1000L;
    }

    public boolean afkAutoEnabled() {
        return plugin.getConfig().getBoolean("afk-auto-enabled", true);
    }

    public long afkIdleMillis() {
        return Math.max(1, plugin.getConfig().getLong("afk-auto-minutes", 5)) * 60L * 1000L;
    }

    public boolean customUnknownCommandMessage() {
        return plugin.getConfig().getBoolean("custom-unknown-command-message", true);
    }

    public boolean startupStatsEnabled() {
        return plugin.getConfig().getBoolean("startup-stats-enabled", true);
    }

    public boolean deathCoordsEnabled() {
        return plugin.getConfig().getBoolean("death-coords-enabled", true);
    }

    public boolean deathChestEnabled() {
        return plugin.getConfig().getBoolean("death-chest-enabled", true);
    }

    public boolean deathChestProtect() {
        return plugin.getConfig().getBoolean("death-chest-protect", true);
    }

    public boolean deathChestStoreXp() {
        return plugin.getConfig().getBoolean("death-chest-store-xp", true);
    }

    public long deathChestExpiryMillis() {
        return Math.max(0, plugin.getConfig().getInt("death-chest-expiry-minutes", 30)) * 60L * 1000L;
    }

    public int tpaExpirySeconds() {
        return Math.max(5, plugin.getConfig().getInt("tpa-expiry-seconds", 60));
    }

    public long poppyLoreCooldownMillis() {
        return Math.max(0, plugin.getConfig().getInt("poppy-lore-cooldown-minutes", 30)) * 60L * 1000L;
    }

    public int sleepPercentage() {
        return Math.clamp(plugin.getConfig().getInt("sleep-percentage", 50), 0, 100);
    }

    public boolean sleepStatusMessageEnabled() {
        return plugin.getConfig().getBoolean("sleep-status-message-enabled", true);
    }

    public boolean loggingEnabled() {
        return plugin.getConfig().getBoolean("logging.enabled", true);
    }

    public boolean loggingConsoleMirror() {
        return plugin.getConfig().getBoolean("logging.console-mirror", false);
    }

    public boolean loggingCategoryEnabled(String categoryName) {
        return plugin.getConfig().getBoolean("logging.categories." + categoryName.toLowerCase(), true);
    }

    public int homesDefaultLimit() {
        return plugin.getConfig().getInt("homes-default-limit", 10);
    }

    public java.util.List<Integer> homesLimitTiers() {
        return plugin.getConfig().getIntegerList("homes-limit-tiers");
    }

    /**
     * Percentage (0-100) of the player's total XP restored via the death
     * chest bottle. 100 (the default) fully negates vanilla's death XP
     * penalty — the player gets back everything they lost. Set lower to keep
     * some penalty; vanilla itself only drops {@code min(7 × level, 100)} XP
     * points on death, so a value well under 100 here gets closer to that.
     */
    public int deathChestXpRefundPercent() {
        return Math.clamp(plugin.getConfig().getInt("death-chest-xp-refund-percent", 100), 0, 100);
    }

    public long feedCooldownMillis() {
        return Math.max(0, plugin.getConfig().getInt("feed-cooldown-seconds", 180)) * 1000L;
    }

    public long healCooldownMillis() {
        return Math.max(0, plugin.getConfig().getInt("heal-cooldown-seconds", 180)) * 1000L;
    }
}