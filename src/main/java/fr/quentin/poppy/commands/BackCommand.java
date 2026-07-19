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
import org.jspecify.annotations.NonNull;

/**
 * Handles /back: teleports the sender to their previous location, as recorded
 * by {@link BackManager} before their last Poppy teleport (or on death, if
 * {@code back-on-death} is enabled in config.yml).
 *
 * <p>Note that {@link BackManager} stores a raw Bukkit {@link Location} rather
 * than a world name, so the target world may have been unloaded since the
 * location was recorded — this is checked explicitly below rather than left
 * to fail inside {@link Home#fromLocation}.
 */
public class BackCommand extends SafeCommand {

    private final BackManager backManager;
    private final TeleportManager teleportManager;

    public BackCommand(JavaPlugin plugin, BackManager backManager, TeleportManager teleportManager, Messages messages) {
        super(plugin, messages);
        this.backManager = backManager;
        this.teleportManager = teleportManager;
    }

    @Override
    protected boolean execute(@NonNull CommandSender sender, @NonNull Command command, @NonNull String label, String @NonNull [] args) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }

        Location target = backManager.getBack(player.getUniqueId());
        if (target == null) {
            player.sendMessage(messages.get("back.not-found"));
            return true;
        }

        if (target.getWorld() == null) {
            player.sendMessage(messages.get("general.world-not-loaded"));
            return true;
        }

        Home backHome = Home.fromLocation("back", target);
        teleportManager.requestTeleport(player, backHome, "back.success");
        return true;
    }
}