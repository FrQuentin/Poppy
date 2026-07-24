package fr.quentin.poppy.listeners;

import fr.quentin.poppy.manager.AfkManager;
import fr.quentin.poppy.util.PoppyConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.NonNull;

import java.util.logging.Level;

/**
 * {@code show-health-in-tab} is checked live each run, so reload can
 * toggle it without a restart. The refresh interval is different: it's the
 * *period* of the scheduled {@link BukkitRunnable}, fixed at scheduling
 * time and not something the task can re-read on its own — so
 * {@link #reapply()} exists to cancel and reschedule it with the current
 * interval, called explicitly by {@code /poppy reload} (see
 * {@link fr.quentin.poppy.commands.PoppyCommand}), the same pattern
 * {@link SleepPercentageListener} uses for its gamerule.
 */
public class TabHealthListener implements Listener {

    private final JavaPlugin plugin;
    private final AfkManager afkManager;
    private final PoppyConfig config;

    private BukkitTask updateTask;

    public TabHealthListener(JavaPlugin plugin, AfkManager afkManager, PoppyConfig config) {
        this.plugin = plugin;
        this.afkManager = afkManager;
        this.config = config;

        scheduleUpdateTask();
    }

    /**
     * Cancels the currently scheduled update task and reschedules it with
     * the interval currently in config.yml — call after a config reload so
     * a changed {@code tab-health-update-interval-ticks} takes effect
     * without restarting the server.
     */
    public void reapply() {
        scheduleUpdateTask();
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

    private void updatePlayer(Player player) {
        double heartsRaw = (player.getHealth() + player.getAbsorptionAmount()) / 2.0;
        double hearts = Math.round(heartsRaw * 2) / 2.0;
        String heartsText = (hearts == Math.floor(hearts)) ? String.valueOf((int) hearts) : String.valueOf(hearts);

        NamedTextColor color = healthColor(player.getHealth(), maxHealth(player));

        Component prefix = afkManager.isAfk(player.getUniqueId())
                ? Component.text("[AFK] ", NamedTextColor.GRAY)
                : Component.empty();

        Component listName = prefix
                .append(player.displayName())
                .append(Component.text("  \u2764 " + heartsText, color));

        player.playerListName(listName);
    }

    private double maxHealth(Player player) {
        AttributeInstance attribute = player.getAttribute(Attribute.MAX_HEALTH);
        return attribute != null ? attribute.getValue() : 20.0;
    }

    private NamedTextColor healthColor(double health, double maxHealth) {
        double ratio = maxHealth <= 0 ? 0 : health / maxHealth;
        if (ratio > 0.66) {
            return NamedTextColor.GREEN;
        } else if (ratio > 0.33) {
            return NamedTextColor.YELLOW;
        } else {
            return NamedTextColor.RED;
        }
    }
}