package fr.quentin.poppy.listeners;

import fr.quentin.poppy.manager.AfkManager;
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

import java.util.logging.Level;

public class TabHealthListener implements Listener {

    private final JavaPlugin plugin;
    private final AfkManager afkManager;
    private final boolean enabled;

    public TabHealthListener(JavaPlugin plugin, AfkManager afkManager) {
        this.plugin = plugin;
        this.afkManager = afkManager;
        this.enabled = plugin.getConfig().getBoolean("show-health-in-tab", true);

        if (enabled) {
            long interval = Math.max(5, plugin.getConfig().getLong("tab-health-update-interval-ticks", 20));
            new BukkitRunnable() {
                @Override
                public void run() {
                    updateAll();
                }
            }.runTaskTimer(plugin, 0L, interval);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!enabled) {
            return;
        }

        try {
            updatePlayer(event.getPlayer());
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error setting tab health for " + event.getPlayer().getName(), e);
        }
    }

    private void updateAll() {
        try {
            for (Player player : Bukkit.getOnlinePlayers()) {
                updatePlayer(player);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error updating tab list health", e);
        }
    }

    private void updatePlayer(Player player) {
        double heartsRaw = player.getHealth() / 2.0;
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