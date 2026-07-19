package fr.quentin.poppy.commands;

import fr.quentin.poppy.manager.SpawnManager;
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
 * Handles /spawn: teleports the sender to the server spawn point set via
 * /setspawn, going through {@link TeleportManager} so the usual
 * warmup/combat-tag rules apply.
 */
public class SpawnCommand extends SafeCommand {

    private final SpawnManager spawnManager;
    private final TeleportManager teleportManager;

    public SpawnCommand(JavaPlugin plugin, SpawnManager spawnManager, TeleportManager teleportManager, Messages messages) {
        super(plugin, messages);
        this.spawnManager = spawnManager;
        this.teleportManager = teleportManager;
    }

    @Override
    protected boolean execute(@NonNull CommandSender sender, @NonNull Command command, @NonNull String label, String @NonNull [] args) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }

        Home spawn = spawnManager.getSpawn();
        if (spawn == null) {
            player.sendMessage(messages.get("spawn.not-set"));
            return true;
        }

        teleportManager.requestTeleport(player, spawn, "spawn.success");
        return true;
    }
}