package fr.quentin.poppy.commands;

import fr.quentin.poppy.manager.SpawnManager;
import fr.quentin.poppy.manager.TeleportManager;
import fr.quentin.poppy.model.Home;
import fr.quentin.poppy.util.Messages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class SpawnCommand implements CommandExecutor {

    private final SpawnManager spawnManager;
    private final TeleportManager teleportManager;
    private final Messages messages;

    public SpawnCommand(SpawnManager spawnManager, TeleportManager teleportManager, Messages messages) {
        this.spawnManager = spawnManager;
        this.teleportManager = teleportManager;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.get("general.only-player"));
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