package fr.quentin.poppy.manager.afk;

import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.PoppyConfig;
import fr.quentin.poppy.util.PoppyLogger;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.logging.Level;

/**
 * Always scheduled (see {@code Poppy#onEnable}); checks
 * {@code afk-auto-enabled} live each run so it can be toggled without a
 * restart, rather than only being scheduled once at startup if enabled.
 */
public class AutoAfkTask extends BukkitRunnable {

    private final JavaPlugin plugin;
    private final AfkManager afkManager;
    private final Messages messages;
    private final PoppyConfig config;
    private final PoppyLogger logger;

    public AutoAfkTask(JavaPlugin plugin, AfkManager afkManager, Messages messages, PoppyConfig config, PoppyLogger logger) {
        this.plugin = plugin;
        this.afkManager = afkManager;
        this.messages = messages;
        this.config = config;
        this.logger = logger;
    }

    @Override
    public void run() {
        if (!config.afkAutoEnabled()) {
            return;
        }

        long idleMillis = config.afkIdleMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            try {
                if (afkManager.isAfk(player.getUniqueId())) {
                    continue;
                }

                if (afkManager.millisSinceActivity(player.getUniqueId()) >= idleMillis) {
                    afkManager.toggle(player.getUniqueId());
                    logger.log(PoppyLogger.Category.AFK, player, "went AFK (auto, inactive)");

                    Component broadcast = messages.get("afk.now-afk", "player", player.getName());
                    Bukkit.getServer().sendMessage(broadcast);
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Error in AutoAfkTask for " + player.getName(), e);
            }
        }
    }
}