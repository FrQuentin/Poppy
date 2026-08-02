package fr.quentin.poppy.commands.afk;

import fr.quentin.poppy.listeners.afk.AfkListener;
import fr.quentin.poppy.manager.afk.AfkManager;
import fr.quentin.poppy.manager.afk.AutoAfkTask;
import fr.quentin.poppy.util.cooldown.CooldownStore;
import fr.quentin.poppy.util.DurationFormat;
import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.PoppyConfig;
import fr.quentin.poppy.util.PoppyLogger;
import fr.quentin.poppy.util.SafeCommand;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

/**
 * Handles /afk: toggles the sender's AFK status and broadcasts the change
 * to the whole server. The actual AFK state is tracked by {@link AfkManager};
 * this class is only responsible for the command entry point and the message.
 *
 * <p>Rate-limited via {@code afk-toggle-cooldown-seconds} in config.yml,
 * tracked in a shared {@link CooldownStore} rather than a raw
 * {@code Map<UUID, Long>} — so a player who toggles /afk once doesn't
 * leave a permanent entry behind; {@link CooldownStore} purges expired
 * entries on a periodic sweep regardless of whether they're ever read
 * again.
 *
 * @see AfkListener AfkListener, which clears AFK automatically on movement/activity
 * @see AutoAfkTask AutoAfkTask, which sets AFK automatically after inactivity
 */
public class AfkCommand extends SafeCommand {

    private final AfkManager afkManager;
    private final PoppyConfig config;
    private final PoppyLogger logger;
    private final CooldownStore cooldown;

    public AfkCommand(JavaPlugin plugin, AfkManager afkManager, Messages messages, PoppyConfig config, PoppyLogger logger) {
        super(plugin, messages);
        this.afkManager = afkManager;
        this.config = config;
        this.logger = logger;
        this.cooldown = new CooldownStore(plugin);
    }

    @Override
    protected boolean execute(@NonNull CommandSender sender, @NonNull Command command, @NonNull String label, String @NonNull [] args) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }

        long remaining = cooldown.remainingSeconds(player.getUniqueId());
        if (remaining > 0) {
            player.sendMessage(messages.get("afk.too-fast", "time", DurationFormat.format(remaining)));
            return true;
        }

        cooldown.start(player.getUniqueId(), config.afkToggleCooldownMillis());

        boolean nowAfk = afkManager.toggle(player.getUniqueId());
        String messagePath = nowAfk ? "afk.now-afk" : "afk.no-longer-afk";

        logger.log(PoppyLogger.Category.AFK, player, nowAfk ? "went AFK (manual)" : "returned from AFK (manual)");

        Component broadcast = messages.get(messagePath, "player", player.getName());
        Bukkit.getServer().sendMessage(broadcast);
        return true;
    }
}