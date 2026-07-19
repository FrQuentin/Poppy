package fr.quentin.poppy.commands;

import fr.quentin.poppy.manager.HomeManager;
import fr.quentin.poppy.manager.ShareManager;
import fr.quentin.poppy.model.Home;
import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.SafeCommand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.List;

public class ShareHomeCommand extends SafeCommand implements TabCompleter {

    private final HomeManager homeManager;
    private final ShareManager shareManager;

    public ShareHomeCommand(JavaPlugin plugin, HomeManager homeManager, ShareManager shareManager, Messages messages) {
        super(plugin, messages);
        this.homeManager = homeManager;
        this.shareManager = shareManager;
    }

    @Override
    protected boolean execute(CommandSender sender, Command command, String label, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) {
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

        Component prefix = messages.get("sharehome.broadcast-prefix", "player", player.getName(), "home", home.name());
        Component clickText = messages.get("sharehome.click-text", "home", home.name())
                .clickEvent(ClickEvent.runCommand("/poppygoto " + token))
                .hoverEvent(HoverEvent.showText(messages.get("sharehome.click-hover", "home", home.name())));

        Bukkit.getServer().sendMessage(prefix.append(clickText));
        return true;
    }

    @Override
    public List<String> onTabComplete(@NonNull CommandSender sender, @NonNull Command command, @NonNull String alias, String @NonNull [] args) {
        if (!(sender instanceof Player player) || args.length != 1) {
            return List.of();
        }
        return homeManager.suggestHomeNames(player.getUniqueId(), args[0]);
    }
}