package fr.quentin.poppy.manager;

import fr.quentin.poppy.util.Messages;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.logging.Level;

public class AutoAfkTask extends BukkitRunnable {

    private final JavaPlugin plugin;
    private final AfkManager afkManager;
    private final Messages messages;
    private final long idleMillis;

    public AutoAfkTask(JavaPlugin plugin, AfkManager afkManager, Messages messages) {
        this.plugin = plugin;
        this.afkManager = afkManager;
        this.messages = messages;
        long idleMinutes = Math.max(1, plugin.getConfig().getLong("afk-auto-minutes", 5));
        this.idleMillis = idleMinutes * 60L * 1000L;
    }

    @Override
    public void run() {
        try {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (afkManager.isAfk(player.getUniqueId())) {
                    continue;
                }

                if (afkManager.millisSinceActivity(player.getUniqueId()) >= idleMillis) {
                    afkManager.toggle(player.getUniqueId());
                    Component broadcast = messages.get("afk.now-afk", "player", player.getName());
                    Bukkit.getServer().sendMessage(broadcast);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error in AutoAfkTask", e);
        }
    }
}