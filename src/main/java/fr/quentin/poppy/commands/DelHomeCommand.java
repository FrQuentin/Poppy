package fr.quentin.poppy.commands;

import fr.quentin.poppy.manager.HomeManager;
import fr.quentin.poppy.model.Home;
import fr.quentin.poppy.util.Messages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public class DelHomeCommand implements CommandExecutor, TabCompleter {

    private final HomeManager homeManager;
    private final Messages messages;

    public DelHomeCommand(HomeManager homeManager, Messages messages) {
        this.homeManager = homeManager;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.get("general.only-player"));
            return true;
        }

        if (args.length != 1) {
            player.sendMessage(messages.get("delhome.usage"));
            return true;
        }

        String name = args[0];
        Home home = homeManager.getHome(player.getUniqueId(), name);

        if (home == null) {
            player.sendMessage(messages.get("delhome.not-found", "home", name));
            return true;
        }

        homeManager.removeHome(player.getUniqueId(), name);
        player.sendMessage(messages.get("delhome.success", "home", home.getName()));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player) || args.length != 1) {
            return List.of();
        }
        List<String> suggestions = new ArrayList<>();
        String partial = args[0].toLowerCase();
        for (Home home : homeManager.getHomes(player.getUniqueId()).values()) {
            if (home.getName().toLowerCase().startsWith(partial)) {
                suggestions.add(home.getName());
            }
        }
        return suggestions;
    }
}
