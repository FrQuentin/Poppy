package fr.quentin.poppy.manager;

import fr.quentin.poppy.util.Messages;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

import java.util.logging.Level;

/**
 * Feeds player activity into {@link AfkManager}: records movement (ignoring
 * head-rotation-only events, since {@link PlayerMoveEvent} fires on those
 * too) and automatically clears AFK status the moment a player who was
 * marked AFK moves again.
 */
public class AfkListener implements Listener {

    private final JavaPlugin plugin;
    private final AfkManager afkManager;
    private final Messages messages;

    public AfkListener(JavaPlugin plugin, AfkManager afkManager, Messages messages) {
        this.plugin = plugin;
        this.afkManager = afkManager;
        this.messages = messages;
    }

    @EventHandler
    public void onJoin(@NonNull PlayerJoinEvent event) {
        afkManager.recordActivity(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(@NonNull PlayerQuitEvent event) {
        afkManager.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onMove(@NonNull PlayerMoveEvent event) {
        try {
            Player player = event.getPlayer();

            Location from = event.getFrom();
            Location to = event.getTo();
            if (to == null) {
                return;
            }

            if (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY() && from.getBlockZ() == to.getBlockZ()) {
                return;
            }

            afkManager.recordActivity(player.getUniqueId());

            if (!afkManager.isAfk(player.getUniqueId())) {
                return;
            }

            afkManager.clearAfk(player.getUniqueId());
            Component broadcast = messages.get("afk.no-longer-afk", "player", player.getName());
            Bukkit.getServer().sendMessage(broadcast);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error in AfkListener#onMove for " + event.getPlayer().getName(), e);
        }
    }
}