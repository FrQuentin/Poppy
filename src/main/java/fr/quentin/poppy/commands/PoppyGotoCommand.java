package fr.quentin.poppy.commands;

import fr.quentin.poppy.manager.ShareManager;
import fr.quentin.poppy.manager.TeleportManager;
import fr.quentin.poppy.model.Home;
import fr.quentin.poppy.util.Messages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class PoppyGotoCommand implements CommandExecutor {

    private final ShareManager shareManager;
    private final TeleportManager teleportManager;
    private final Messages messages;

    public PoppyGotoCommand(ShareManager shareManager, TeleportManager teleportManager, Messages messages) {
        this.shareManager = shareManager;
        this.teleportManager = teleportManager;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player) || args.length != 1) {
            return true;
        }

        Home home = shareManager.get(args[0]);
        if (home == null) {
            player.sendMessage(messages.get("sharehome.expired"));
            return true;
        }

        teleportManager.requestTeleport(player, home, "sharehome.teleport-success");
        return true;
    }
}