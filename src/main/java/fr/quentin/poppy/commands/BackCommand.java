package fr.quentin.poppy.commands;

import fr.quentin.poppy.manager.BackManager;
import fr.quentin.poppy.manager.TeleportManager;
import fr.quentin.poppy.model.Home;
import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.SafeCommand;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public class BackCommand extends SafeCommand {

    private final BackManager backManager;
    private final TeleportManager teleportManager;

    public BackCommand(JavaPlugin plugin, BackManager backManager, TeleportManager teleportManager, Messages messages) {
        super(plugin, messages);
        this.backManager = backManager;
        this.teleportManager = teleportManager;
    }

    @Override
    protected boolean execute(CommandSender sender, Command command, String label, String[] args) {
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