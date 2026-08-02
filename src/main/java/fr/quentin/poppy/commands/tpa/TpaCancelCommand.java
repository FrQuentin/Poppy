package fr.quentin.poppy.commands.tpa;

import fr.quentin.poppy.manager.tpa.TpaManager;
import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.PoppyLogger;
import fr.quentin.poppy.util.SafeCommand;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

import java.util.List;

/**
 * Handles /tpacancel <player>: cancels the sender's own outgoing /tpa or
 * /tpahere request to the given player, notifying them it was withdrawn.
 */
public class TpaCancelCommand extends SafeCommand implements TabCompleter {

    private final TpaManager tpaManager;
    private final PoppyLogger logger;

    public TpaCancelCommand(JavaPlugin plugin, TpaManager tpaManager, Messages messages, PoppyLogger logger) {
        super(plugin, messages);
        this.tpaManager = tpaManager;
        this.logger = logger;
    }

    @Override
    protected boolean execute(@NonNull CommandSender sender, @NonNull Command command, @NonNull String label, String @NonNull [] args) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }

        if (args.length != 1) {
            player.sendMessage(messages.get("tpa.cancel-usage"));
            return true;
        }

        Player target = tpaManager.findOutgoingTargetByName(player.getUniqueId(), args[0]);
        if (target == null) {
            player.sendMessage(messages.get("tpa.no-outgoing-request", "player", args[0]));
            return true;
        }

        tpaManager.removeRequest(target.getUniqueId(), player.getUniqueId());
        logger.log(PoppyLogger.Category.TPA, player, "cancelled request to " + target.getName());
        player.sendMessage(messages.get("tpa.cancel-success", "player", target.getName()));
        target.sendMessage(messages.get("tpa.cancel-notify", "player", player.getName()));

        return true;
    }

    @Override
    public List<String> onTabComplete(@NonNull CommandSender sender, @NonNull Command command, @NonNull String alias, String @NonNull [] args) {
        if (!(sender instanceof Player player) || args.length != 1) {
            return List.of();
        }
        return tpaManager.outgoingTargetNames(player.getUniqueId(), args[0]);
    }
}