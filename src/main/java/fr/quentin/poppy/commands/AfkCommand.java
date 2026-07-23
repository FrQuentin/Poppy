package fr.quentin.poppy.commands;

import fr.quentin.poppy.manager.AfkManager;
import fr.quentin.poppy.util.Messages;
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
 * @see fr.quentin.poppy.manager.AfkListener AfkListener, which clears AFK automatically on movement
 * @see fr.quentin.poppy.manager.AutoAfkTask AutoAfkTask, which sets AFK automatically after inactivity
 */
public class AfkCommand extends SafeCommand {

    private final AfkManager afkManager;
    private final PoppyLogger logger;

    public AfkCommand(JavaPlugin plugin, AfkManager afkManager, Messages messages, PoppyLogger logger) {
        super(plugin, messages);
        this.afkManager = afkManager;
        this.logger = logger;
    }

    @Override
    protected boolean execute(@NonNull CommandSender sender, @NonNull Command command, @NonNull String label, String @NonNull [] args) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }

        boolean nowAfk = afkManager.toggle(player.getUniqueId());
        String messagePath = nowAfk ? "afk.now-afk" : "afk.no-longer-afk";

        logger.log(PoppyLogger.Category.AFK, player, nowAfk ? "went AFK (manual)" : "returned from AFK (manual)");

        Component broadcast = messages.get(messagePath, "player", player.getName());
        Bukkit.getServer().sendMessage(broadcast);
        return true;
    }
}