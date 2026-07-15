package fr.quentin.poppy.commands;

import fr.quentin.poppy.manager.HomeManager;
import fr.quentin.poppy.manager.ShareManager;
import fr.quentin.poppy.model.Home;
import fr.quentin.poppy.util.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public class ShareHomeCommand implements CommandExecutor, TabCompleter {

    private final HomeManager homeManager;
    private final ShareManager shareManager;
    private final Messages messages;

    public ShareHomeCommand(HomeManager homeManager, ShareManager shareManager, Messages messages) {
        this.homeManager = homeManager;
        this.shareManager = shareManager;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.get("general.only-player"));
            return true;
        }

        if (args.length != 1) {
            player.sendMessage(messages.get("sharehome.usage"));
            return true;
        }

        String name = args[0];
        Home home = homeManager.getHome(player.getUniqueId(), name);
        if (home == null) {
            player.sendMessage(messages.get("sharehome.not-found"));
            return true;
        }

        String token = shareManager.share(home);

        Component prefix = messages.get("sharehome.broadcast-prefix", "player", player.getName(), "home", home.getName());
        Component clickText = messages.get("sharehome.click-text", "home", home.getName())
                .clickEvent(ClickEvent.runCommand("/poppygoto " + token))
                .hoverEvent(HoverEvent.showText(messages.get("sharehome.click-hover", "home", home.getName())));

        Bukkit.getServer().sendMessage(prefix.append(clickText));
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