package fr.quentin.poppy.commands;

import fr.quentin.poppy.manager.ShareManager;
import fr.quentin.poppy.manager.TeleportManager;
import fr.quentin.poppy.model.Home;
import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.SafeCommand;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

/**
 * Internal command triggered by the clickable link created by /sharehome —
 * never meant to be typed manually. Deliberately has no permission node in
 * plugin.yml: the short-lived {@link ShareManager} token itself is the
 * access control, so any player who received (or guessed) a valid token can
 * use it.
 */
public class PoppyGotoCommand extends SafeCommand {

    private final ShareManager shareManager;
    private final TeleportManager teleportManager;

    public PoppyGotoCommand(JavaPlugin plugin, ShareManager shareManager, TeleportManager teleportManager, Messages messages) {
        super(plugin, messages);
        this.shareManager = shareManager;
        this.teleportManager = teleportManager;
    }

    @Override
    protected boolean execute(@NonNull CommandSender sender, @NonNull Command command, @NonNull String label, String @NonNull [] args) {
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