package fr.quentin.poppy.listeners.tab;

import fr.quentin.poppy.commands.misc.PoppyCommand;
import fr.quentin.poppy.manager.afk.AfkManager;
import fr.quentin.poppy.manager.sleep.SleepPercentageListener;
import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.PoppyConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.RenderType;
import org.bukkit.scoreboard.Scoreboard;
import org.jspecify.annotations.NonNull;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
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
 * <p><b>Real vanilla heart icons, not text:</b> health is shown via a
 * scoreboard {@link Objective} on {@link DisplaySlot#PLAYER_LIST} with
 * {@link RenderType#HEARTS} — the client renders actual heart sprites
 * next to each name, the same visual as the player's own HUD, not a
 * {@code "❤ 12"} text approximation. The objective's criteria is
 * {@link Criteria#DUMMY}, not {@link Criteria#HEALTH}: the vanilla
 * {@code health} criteria auto-tracks each player's real health
 * server-side, but has a known quirk where it stays stuck at 0 until the
 * player has taken damage at least once — using {@code DUMMY} and
 * setting each player's score manually every tick (see
 * {@link #updateHearts}) avoids that entirely and gives full control.
 *
 * <p><b>Real heart sprites can't be recolored</b> — they're fixed game
 * assets (full/half/empty red, gray for absorption), unlike a text
 * {@link Component}. This is why the previous green/yellow/red hex health
 * thresholds are gone: they only made sense for the old text-based
 * display, and have no equivalent once real hearts are in use. The name
 * itself and the {@code [AFK]} prefix keep their existing hex-based
 * coloring in {@link #updatePlayer}, unaffected by this.
 *
 * <p><b>Slot-conflict safety:</b> {@link DisplaySlot#PLAYER_LIST} can only
 * ever show one objective at a time, server-wide. {@link #setupHeartsObjective()}
 * checks for an existing objective in that slot at construction; if one
 * is already there under a different name (another plugin — a ranks
 * plugin, a ping display, etc.), Poppy leaves it alone and logs a single
 * warning rather than silently overwriting it. {@link #heartsSupported}
 * reflects whether the feature is actually active.
 *
 * <p>{@link #reapply()} clears every online player's tab list name back
 * to default the moment {@code show-health-in-tab} is toggled off —
 * without this, a player already showing a colored name at reload time
 * would stay stuck with it forever. It does not touch the hearts
 * objective's scores directly; those are simply skipped on the next
 * {@link #updateAll} while the setting is off, and the objective/slot
 * assignment itself is left in place (removing and re-registering it on
 * every toggle isn't necessary — an unused objective with stale scores
 * showing 0 hearts next to nothing meaningful is harmless since the
 * PLAYER_LIST slot rendering only matters visually while players are
 * looking at the tab list, and scores are refreshed the moment the
 * setting is re-enabled).
 *
 * <p>{@link #lastSentText} caches the plain-text form of each player's
 * last sent tab name, and {@link #updatePlayer} skips the actual
 * {@code playerListName(...)} call when the new text is identical to what
 * was already sent. Evicted on quit (see {@link #onQuit}) to avoid an
 * unbounded map over a long server uptime.
 *
 * <p>{@link #updateHeaderFooter} sets the tab list footer to the current
 * online player count, independent of {@code show-health-in-tab} — see
 * its own doc for the join/quit timing details.
 */
public class TabHealthListener implements Listener {

    private static final TextColor AFK_COLOR = TextColor.fromHexString("#a7aeba");
    private static final TextColor NAME_COLOR = TextColor.fromHexString("#f3f3f3");
    private static final String HEARTS_OBJECTIVE_NAME = "poppy_hearts";

    private final JavaPlugin plugin;
    private final AfkManager afkManager;
    private final PoppyConfig config;
    private final Messages messages;
    private final Map<UUID, String> lastSentText = new HashMap<>();

    private BukkitTask updateTask;
    private Objective heartsObjective;
    private boolean heartsSupported;

    public TabHealthListener(JavaPlugin plugin, AfkManager afkManager, PoppyConfig config, Messages messages) {
        this.plugin = plugin;
        this.afkManager = afkManager;
        this.config = config;
        this.messages = messages;

        setupHeartsObjective();
        scheduleUpdateTask();
    }

    /**
     * Claims {@link DisplaySlot#PLAYER_LIST} for the hearts objective if
     * it's free, or reuses Poppy's own objective if it's already
     * registered (e.g. surviving across a {@code /poppy reload} — this
     * class itself is only constructed once per plugin lifetime, but the
     * check is cheap and defensive). Leaves an objective owned by another
     * plugin untouched — see the class-level doc.
     */
    private void setupHeartsObjective() {
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();

        Objective existing = scoreboard.getObjective(DisplaySlot.PLAYER_LIST);
        if (existing != null && !existing.getName().equals(HEARTS_OBJECTIVE_NAME)) {
            plugin.getLogger().warning("Another plugin already occupies the tab list's PLAYER_LIST scoreboard slot ('"
                    + existing.getName() + "') — Poppy will not show heart icons in tab to avoid overwriting it.");
            heartsSupported = false;
            return;
        }

        Objective objective = scoreboard.getObjective(HEARTS_OBJECTIVE_NAME);
        if (objective == null) {
            objective = scoreboard.registerNewObjective(HEARTS_OBJECTIVE_NAME, Criteria.DUMMY, Component.empty());
        }
        objective.setRenderType(RenderType.HEARTS);
        objective.setDisplaySlot(DisplaySlot.PLAYER_LIST);

        heartsObjective = objective;
        heartsSupported = true;
    }

    /**
     * Cancels the currently scheduled update task and reschedules it with
     * the interval currently in config.yml. If the setting was just turned
     * off, clears every online player's tab list name AND resets the
     * hearts objective's scores — leaving stale scores in place used to
     * show each player frozen at whatever health they had at the moment of
     * the reload (a player at 2 hearts stayed shown at 2 hearts forever),
     * which is more misleading than showing nothing. If the setting was
     * just turned on, re-attempts to claim the PLAYER_LIST slot — a
     * competing plugin may have released it (or claimed it) since
     * construction, which used to be the only point this was ever checked.
     */
    public void reapply() {
        if (!config.showHealthInTab()) {
            clearAllPlayerListNames();
            clearHearts();
        } else {
            setupHeartsObjective();
        }
        scheduleUpdateTask();
    }

    /**
     * Removes Poppy's hearts objective from PLAYER_LIST and resets every
     * online player's score on it, rather than leaving stale values behind
     * when the feature is toggled off.
     */
    private void clearHearts() {
        if (heartsObjective == null) {
            return;
        }
        try {
            Scoreboard scoreboard = heartsObjective.getScoreboard();
            if (scoreboard == null) {
                return;
            }
            if (heartsObjective.equals(scoreboard.getObjective(DisplaySlot.PLAYER_LIST))) {
                scoreboard.clearSlot(DisplaySlot.PLAYER_LIST);
            }
            for (Player player : Bukkit.getOnlinePlayers()) {
                scoreboard.resetScores(player.getName());
            }
        } catch (IllegalStateException e) {
            // Objective was unregistered by another plugin in the meantime.
            heartsObjective = null;
            heartsSupported = false;
        }
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
        try {
            updateHeaderFooter();
            if (config.showHealthInTab()) {
                updatePlayer(event.getPlayer());
                updateHearts(event.getPlayer());
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error setting tab info for " + event.getPlayer().getName(), e);
        }
    }

    @EventHandler
    public void onQuit(@NonNull PlayerQuitEvent event) {
        lastSentText.remove(event.getPlayer().getUniqueId());
        if (heartsSupported && heartsObjective != null) {
            Objects.requireNonNull(heartsObjective.getScoreboard()).resetScores(event.getPlayer().getName());
        }
        // Deferred a tick — at this exact point in the dispatch, the leaving
        // player is still counted in Bukkit.getOnlinePlayers().
        Bukkit.getScheduler().runTask(plugin, this::updateHeaderFooter);
    }

    private void updateAll() {
        updateHeaderFooter();

        if (!config.showHealthInTab()) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            try {
                updatePlayer(player);
                updateHearts(player);
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Error updating tab list health for " + player.getName(), e);
            }
        }
    }

    private void updateHeaderFooter() {
        int count = Bukkit.getOnlinePlayers().size();
        // A blank line between the player-name list and the phrase, so it
        // doesn't sit glued directly under the last entry.
        Component footer = Component.empty()
                .appendNewline()
                .append(messages.get("tab.footer", "count", String.valueOf(count)));

        for (Player player : Bukkit.getOnlinePlayers()) {
            try {
                player.sendPlayerListHeaderAndFooter(Component.empty(), footer);
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Error updating tab list footer for " + player.getName(), e);
            }
        }
    }

    /**
     * Sets the player's score on {@link #heartsObjective} to their current
     * health (rounded, clamped to at least 0) — the client renders this
     * as real heart icons via {@link RenderType#HEARTS}. A no-op if the
     * hearts slot isn't available (see {@link #setupHeartsObjective()}).
     */
    private void updateHearts(Player player) {
        if (!heartsSupported || heartsObjective == null) {
            return;
        }
        int score = (int) Math.max(0, Math.round(player.getHealth()));
        heartsObjective.getScore(player.getName()).setScore(score);
    }

    private final PlainTextComponentSerializer plainTextSerializer = PlainTextComponentSerializer.plainText();

    private void updatePlayer(Player player) {
        boolean afk = afkManager.isAfk(player.getUniqueId());

        // Plain-text cache key covering everything that affects the rendered
        // name: the player's current display name and AFK status. If neither
        // changed since the last tick, skip the packet entirely.
        String cacheKey = (afk ? "AFK|" : "|") + plainTextSerializer.serialize(player.displayName());

        UUID uuid = player.getUniqueId();
        if (cacheKey.equals(lastSentText.get(uuid))) {
            return;
        }
        lastSentText.put(uuid, cacheKey);

        TextColor nameColor = afk ? AFK_COLOR : NAME_COLOR;

        Component prefix = afk
                ? Component.text("[AFK] ", AFK_COLOR)
                : Component.empty();

        Component listName = prefix.append(player.displayName().color(nameColor));

        player.playerListName(listName);
    }
}