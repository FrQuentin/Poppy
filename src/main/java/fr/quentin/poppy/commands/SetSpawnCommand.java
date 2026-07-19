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
 * Handles /setspawn: sets the server-wide spawn point to the sender's
 * current location, persisted by {@link SpawnManager} to spawn.yml.
 */
public class SetSpawnCommand extends SafeCommand {

    private final SpawnManager spawnManager;

    public SetSpawnCommand(JavaPlugin plugin, SpawnManager spawnManager, Messages messages) {
        super(plugin, messages);
        this.spawnManager = spawnManager;
    }

    @Override
    protected boolean execute(@NonNull CommandSender sender, @NonNull Command command, @NonNull String label, String @NonNull [] args) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }

        spawnManager.setSpawn(player.getLocation());
        player.sendMessage(messages.get("spawn.set"));
        return true;
    }
}