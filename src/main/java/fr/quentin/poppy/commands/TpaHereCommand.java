package fr.quentin.poppy.commands;

import fr.quentin.poppy.manager.TpaManager;
import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.PlayerNameSuggestions;
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
 * Handles /tpahere <player>: asks another player to teleport to the
 * sender. If they accept (via /tpaccept), the recipient is teleported to
 * the sender — see {@link TpaAcceptCommand}.
 */
public class TpaHereCommand extends SafeCommand implements TabCompleter {

    private final TpaManager tpaManager;

    public TpaHereCommand(JavaPlugin plugin, TpaManager tpaManager, Messages messages) {
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
            player.sendMessage(messages.get("tpa.here-usage"));
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

        tpaManager.createRequest(target.getUniqueId(), player.getUniqueId(), TpaManager.Type.HERE);
        player.sendMessage(messages.get("tpa.here-sent", "player", target.getName()));

        Component prefix = messages.get("tpa.here-received-prefix", "player", player.getName());
        Component click = messages.get("tpa.here-received-click")
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