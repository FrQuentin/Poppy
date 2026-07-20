package fr.quentin.poppy.commands;

import fr.quentin.poppy.manager.TpaManager;
import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.SafeCommand;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

import java.util.List;

/**
 * Handles /tpadeny <player>: rejects a pending /tpa or /tpahere request
 * from the given player, notifying them it was denied.
 */
public class TpaDenyCommand extends SafeCommand implements TabCompleter {

    private final TpaManager tpaManager;

    public TpaDenyCommand(JavaPlugin plugin, TpaManager tpaManager, Messages messages) {
        super(plugin, messages);
        this.tpaManager = tpaManager;
    }

    @Override
    protected boolean execute(@NonNull CommandSender sender, @NonNull Command command, @NonNull String label, String @NonNull [] args) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }

        if (args.length != 1) {
            player.sendMessage(messages.get("tpa.deny-usage"));
            return true;
        }

        TpaManager.Request request = tpaManager.findRequestByName(player.getUniqueId(), args[0]);
        if (request == null) {
            player.sendMessage(messages.get("tpa.no-request", "player", args[0]));
            return true;
        }

        tpaManager.removeRequest(player.getUniqueId(), request.requester());
        player.sendMessage(messages.get("tpa.deny-success", "player", args[0]));

        Player requester = Bukkit.getPlayer(request.requester());
        if (requester != null) {
            requester.sendMessage(messages.get("tpa.deny-notify", "player", player.getName()));
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(@NonNull CommandSender sender, @NonNull Command command, @NonNull String alias, String @NonNull [] args) {
        if (!(sender instanceof Player player) || args.length != 1) {
            return List.of();
        }
        return tpaManager.pendingRequesterNames(player.getUniqueId(), args[0]);
    }
}