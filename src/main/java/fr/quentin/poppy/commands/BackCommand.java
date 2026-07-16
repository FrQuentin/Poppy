package fr.quentin.poppy.commands;

import fr.quentin.poppy.manager.BackManager;
import fr.quentin.poppy.manager.TeleportManager;
import fr.quentin.poppy.model.Home;
import fr.quentin.poppy.util.Messages;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class BackCommand implements CommandExecutor {

    private final BackManager backManager;
    private final TeleportManager teleportManager;
    private final Messages messages;

    public BackCommand(BackManager backManager, TeleportManager teleportManager, Messages messages) {
        this.backManager = backManager;
        this.teleportManager = teleportManager;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.get("general.only-player"));
            return true;
        }

        Location target = backManager.getBack(player.getUniqueId());
        if (target == null) {
            player.sendMessage(messages.get("back.not-found"));
            return true;
        }

        Home backHome = Home.fromLocation("back", target);
        teleportManager.requestTeleport(player, backHome, "back.success");
        return true;
    }
}