package fr.quentin.poppy.commands;

import fr.quentin.poppy.gui.HomesGUI;
import fr.quentin.poppy.manager.HomeManager;
import fr.quentin.poppy.model.Home;
import fr.quentin.poppy.util.Messages;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public class HomeCommand implements CommandExecutor, TabCompleter {

    private final HomeManager homeManager;
    private final HomesGUI homesGUI;
    private final Messages messages;

    public HomeCommand(HomeManager homeManager, HomesGUI homesGUI, Messages messages) {
        this.homeManager = homeManager;
        this.homesGUI = homesGUI;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.get("general.only-player"));
            return true;
        }

        if (args.length == 0) {
            homesGUI.open(player, homeManager);
            return true;
        }

        String name = args[0];
        Home home = homeManager.getHome(player.getUniqueId(), name);

        if (home == null) {
            player.sendMessage(messages.get("home.not-found"));
            return true;
        }

        Location location = home.toLocation();
        if (location == null) {
            player.sendMessage(messages.get("general.world-not-loaded"));
            return true;
        }

        player.teleport(location);
        player.sendMessage(messages.get("home.success", "home", home.getName()));
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
