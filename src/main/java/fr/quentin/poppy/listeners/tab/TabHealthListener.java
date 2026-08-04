package fr.quentin.poppy.listeners.tab;

import fr.quentin.poppy.commands.misc.PoppyCommand;
import fr.quentin.poppy.manager.afk.AfkManager;
import fr.quentin.poppy.manager.sleep.SleepPercentageListener;
import fr.quentin.poppy.util.PoppyConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.NonNull;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * {@code show-health-in-tab} is checked live each run, so reload can
 * toggle it without a restart. The refresh interval is different: it's the
 * *period* of the scheduled {@link BukkitRunnable}, fixed at scheduling
 * time and not something the task can re-read on its own — so
 * {@link #reapply()} exists to cancel and reschedule it with the current
 * interval, called explicitly by {@code /poppy reload} (see
 * {@link PoppyCommand}), the same pattern
 * {@link SleepPercentageListener} uses for its gamerule.
 *
 * <p>{@link #reapply()} also resets every online player's tab list name
 * back to default the moment the setting is toggled off — without this,
 * a player already showing "❤ X" at reload time would stay stuck with
 * that exact text forever (it's never refreshed again once disabled),
 * rather than reverting to their normal name.
 *
 * <p>{@link #lastSentText} caches the plain-text form of each player's
 * last sent tab name, and {@link #updatePlayer} skips the actual
 * {@code playerListName(...)} call when the new text is identical to what
 * was already sent — a player whose health/AFK status hasn't changed
 * between ticks would otherwise get a redundant packet sent every single
 * interval, for every online player, forever. Evicted on quit (see
 * {@link #onQuit}) to avoid an unbounded map over a long server uptime.
 *
 * <p>Health color follows three hex thresholds ({@link #HEALTH_HIGH}/
 * {@link #HEALTH_MEDIUM}/{@link #HEALTH_LOW}) matching the plugin's
 * messages.yml palette, rather than vanilla's
 * {@code NamedTextColor.GREEN}/{@code YELLOW}/{@code RED}. A player who's
 * AFK shows {@link #AFK_COLOR} for the heart count instead of a health
 * threshold color — an AFK player isn't taking real-time damage, so the
 * usual green/yellow/red signal doesn't mean much while inactive.
 *
 * <p>{@link #NAME_COLOR}/{@link #AFK_COLOR} are set <b>explicitly</b> on
 * {@link Player#displayName()} in {@link #updatePlayer}, rather than
 * leaving it uncolored — Adventure's {@link Component} color inheritance
 * meant an uncolored name nested inside {@code prefix.append(displayName)}
 * silently inherited whatever color the AFK prefix carried, with no
 * explicit theming either way. The {@code [AFK]} prefix uses the same
 * {@link #AFK_COLOR} as the name and heart count, for a consistent AFK
 * look across the whole tab entry.
 */
public class TabHealthListener implements Listener {

    private static final TextColor HEALTH_HIGH = TextColor.fromHexString("#85cc16");
    private static final TextColor HEALTH_MEDIUM = TextColor.fromHexString("#e9b308");
    private static final TextColor HEALTH_LOW = TextColor.fromHexString("#dc2625");
    private static final TextColor AFK_COLOR = TextColor.fromHexString("#a7aeba");
    private static final TextColor NAME_COLOR = TextColor.fromHexString("#f3f3f3");

    private final JavaPlugin plugin;
    private final AfkManager afkManager;
    private final PoppyConfig config;
    private final Map<UUID, String> lastSentText = new HashMap<>();

    private BukkitTask updateTask;

    public TabHealthListener(JavaPlugin plugin, AfkManager afkManager, PoppyConfig config) {
        this.plugin = plugin;
        this.afkManager = afkManager;
        this.config = config;

        scheduleUpdateTask();
    }

    /**
     * Cancels the currently scheduled update task and reschedules it with
     * the interval currently in config.yml, and clears every online
     * player's tab list name if the feature was just turned off — call
     * after a config reload.
     */
    public void reapply() {
        if (!config.showHealthInTab()) {
            clearAllPlayerListNames();
        }
        scheduleUpdateTask();
    }

    private void clearAllPlayerListNames() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            try {
                player.playerListName(null);
                lastSentText.remove(player.getUniqueId());
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Error clearing tab list name for " + player.getName(), e);
            }
        }
    }

    private void scheduleUpdateTask() {
        if (updateTask != null) {
            updateTask.cancel();
        }

        long interval = config.tabHealthUpdateIntervalTicks();
        updateTask = new BukkitRunnable() {
            @Override
            public void run() {
                updateAll();
            }
        }.runTaskTimer(plugin, 0L, interval);
    }

    @EventHandler
    public void onJoin(@NonNull PlayerJoinEvent event) {
        if (!config.showHealthInTab()) {
            return;
        }
        try {
            updatePlayer(event.getPlayer());
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error setting tab health for " + event.getPlayer().getName(), e);
        }
    }

    @EventHandler
    public void onQuit(@NonNull PlayerQuitEvent event) {
        lastSentText.remove(event.getPlayer().getUniqueId());
    }

    private void updateAll() {
        if (!config.showHealthInTab()) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            try {
                updatePlayer(player);
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Error updating tab list health for " + player.getName(), e);
            }
        }
    }

    private final PlainTextComponentSerializer plainTextSerializer = PlainTextComponentSerializer.plainText();

    private void updatePlayer(Player player) {
        double heartsRaw = (player.getHealth() + player.getAbsorptionAmount()) / 2.0;
        double hearts = Math.round(heartsRaw * 2) / 2.0;
        String heartsText = (hearts == Math.floor(hearts)) ? String.valueOf((int) hearts) : String.valueOf(hearts);

        boolean afk = afkManager.isAfk(player.getUniqueId());

        // Plain-text cache key covering everything that affects the rendered
        // name: the player's current display name, AFK prefix, and hearts
        // text/color. If none of these changed since the last tick, skip the
        // packet entirely. Serialized via PlainTextComponentSerializer rather
        // than relying on Component#toString() (or concatenating the
        // Component directly, which falls back to the same thing) — the
        // default toString() output is a verbose dump of Adventure's internal
        // structure, not the rendered text, so it works for equality checks
        // but allocates a much larger string than necessary on every tick for
        // every online player. Plain text serialization gives the same
        // change-detection guarantee at a fraction of the allocation cost.
        String cacheKey = (afk ? "AFK|" : "|") + plainTextSerializer.serialize(player.displayName()) + "|" + heartsText;

        UUID uuid = player.getUniqueId();
        if (cacheKey.equals(lastSentText.get(uuid))) {
            return;
        }
        lastSentText.put(uuid, cacheKey);

        TextColor heartColor = afk ? AFK_COLOR : healthColor(player.getHealth(), maxHealth(player));
        TextColor nameColor = afk ? AFK_COLOR : NAME_COLOR;

        Component prefix = afk
                ? Component.text("[AFK] ", AFK_COLOR)
                : Component.empty();

        Component listName = prefix
                .append(player.displayName().color(nameColor))
                .append(Component.text("  ❤ " + heartsText, heartColor));

        player.playerListName(listName);
    }

    private double maxHealth(Player player) {
        AttributeInstance attribute = player.getAttribute(Attribute.MAX_HEALTH);
        return attribute != null ? attribute.getValue() : 20.0;
    }

    private TextColor healthColor(double health, double maxHealth) {
        double ratio = maxHealth <= 0 ? 0 : health / maxHealth;
        if (ratio > 0.66) {
            return HEALTH_HIGH;
        } else if (ratio > 0.33) {
            return HEALTH_MEDIUM;
        } else {
            return HEALTH_LOW;
        }
    }
}