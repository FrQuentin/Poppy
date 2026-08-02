package fr.quentin.poppy.commands.spawn;

import fr.quentin.poppy.commands.home.HomeCommand;
import fr.quentin.poppy.manager.spawn.SpawnManager;
import fr.quentin.poppy.manager.teleport.TeleportManager;
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
 *
 * <p>The destination is passed as a supplier that re-fetches the spawn
 * from {@link SpawnManager} at teleport time, rather than the {@link Home}
 * captured here — this way, if the spawn is removed (e.g. via /delspawn)
 * during the teleport warmup, {@link TeleportManager} notices at the last
 * moment and cancels instead of teleporting to stale coordinates. Same
 * fix as {@link HomeCommand} for the equivalent /delhome case.
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

        teleportManager.requestTeleport(player, spawnManager::getSpawn, "spawn.success");
        return true;
    }
}