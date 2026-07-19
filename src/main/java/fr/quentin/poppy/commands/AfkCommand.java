package fr.quentin.poppy.commands;

import fr.quentin.poppy.manager.AfkManager;
import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.SafeCommand;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public class AfkCommand extends SafeCommand {

    private final AfkManager afkManager;

    public AfkCommand(JavaPlugin plugin, AfkManager afkManager, Messages messages) {
        super(plugin, messages);
        this.afkManager = afkManager;
    }

    @Override
    protected boolean execute(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.get("general.only-player"));
            return true;
        }

        boolean nowAfk = afkManager.toggle(player.getUniqueId());
        String messagePath = nowAfk ? "afk.now-afk" : "afk.no-longer-afk";

        Component broadcast = messages.get(messagePath, "player", player.getName());
        Bukkit.getServer().sendMessage(broadcast);
        return true;
    }
}