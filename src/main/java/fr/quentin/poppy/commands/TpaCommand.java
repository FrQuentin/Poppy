package fr.quentin.poppy.commands;

import fr.quentin.poppy.manager.TpaManager;
import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.PlayerNameSuggestions;
import fr.quentin.poppy.util.PoppyLogger;
import fr.quentin.poppy.util.SafeCommand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

import java.util.List;

/**
 * Handles /tpa <player>: asks another player for permission to teleport to
 * them. If they accept (via /tpaccept), the sender is teleported to the
 * target — see {@link TpaAcceptCommand}.
 */
public class TpaCommand extends SafeCommand implements TabCompleter {

    private final TpaManager tpaManager;
    private final PoppyLogger logger;

    public TpaCommand(JavaPlugin plugin, TpaManager tpaManager, Messages messages, PoppyLogger logger) {
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
            player.sendMessage(messages.get("tpa.usage"));
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            player.sendMessage(messages.get("tpa.player-not-found", "player", args[0]));
            return true;
        }

        if (target.getUniqueId().equals(player.getUniqueId())) {
            player.sendMessage(messages.get("tpa.self"));
            return true;
        }

        tpaManager.createRequest(target.getUniqueId(), player.getUniqueId(), TpaManager.Type.NORMAL);
        logger.log(PoppyLogger.Category.TPA, player, "sent /tpa request to " + target.getName());
        player.sendMessage(messages.get("tpa.sent", "player", target.getName()));

        Component prefix = messages.get("tpa.received-prefix", "player", player.getName());
        Component click = messages.get("tpa.received-click")
                .clickEvent(ClickEvent.runCommand("/tpaccept " + player.getName()));
        target.sendMessage(prefix.append(click));

        return true;
    }

    @Override
    public List<String> onTabComplete(@NonNull CommandSender sender, @NonNull Command command, @NonNull String alias, String @NonNull [] args) {
        if (!(sender instanceof Player player) || args.length != 1) {
            return List.of();
        }
        return PlayerNameSuggestions.onlineExcept(player, args[0]);
    }
}