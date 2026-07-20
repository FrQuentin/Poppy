package fr.quentin.poppy.commands;

import fr.quentin.poppy.manager.SpawnManager;
import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.SafeCommand;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

/**
 * Handles /delspawn: removes the server-wide spawn point set via
 * /setspawn, deleting spawn.yml so /spawn stops working until a new one
 * is set.
 */
public class DelSpawnCommand extends SafeCommand {

    private final SpawnManager spawnManager;

    public DelSpawnCommand(JavaPlugin plugin, SpawnManager spawnManager, Messages messages) {
        super(plugin, messages);
        this.spawnManager = spawnManager;
    }

    @Override
    protected boolean execute(@NonNull CommandSender sender, @NonNull Command command, @NonNull String label, String @NonNull [] args) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }

        if (!spawnManager.hasSpawn()) {
            player.sendMessage(messages.get("spawn.not-set"));
            return true;
        }

        spawnManager.clearSpawn();
        player.sendMessage(messages.get("spawn.deleted"));
        return true;
    }
}