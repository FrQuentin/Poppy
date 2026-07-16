package fr.quentin.poppy.commands;

import fr.quentin.poppy.manager.SpawnManager;
import fr.quentin.poppy.util.Messages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;

public class SetSpawnCommand implements CommandExecutor {

    private final SpawnManager spawnManager;
    private final Messages messages;

    public SetSpawnCommand(SpawnManager spawnManager, Messages messages) {
        this.spawnManager = spawnManager;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(@NonNull CommandSender sender, @NonNull Command command, @NonNull String label, String @NonNull [] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.get("general.only-player"));
            return true;
        }

        spawnManager.setSpawn(player.getLocation());
        player.sendMessage(messages.get("spawn.set"));
        return true;
    }
}