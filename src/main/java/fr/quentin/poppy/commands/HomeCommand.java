package fr.quentin.poppy.commands;

import fr.quentin.poppy.gui.HomesGUI;
import fr.quentin.poppy.manager.HomeManager;
import fr.quentin.poppy.manager.TeleportManager;
import fr.quentin.poppy.model.Home;
import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.SafeCommand;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.UUID;

/**
 * Handles /home: with no argument, opens the /homes GUI; with a home name,
 * teleports directly to it (going through {@link TeleportManager} so the
 * usual warmup/combat-tag rules apply).
 *
 * <p>The destination is passed as a supplier that re-fetches the home from
 * {@link HomeManager} by name, rather than the {@link Home} instance
 * captured here — this way, if the home is deleted (e.g. via /delhome)
 * during the teleport warmup, {@link TeleportManager} notices at the last
 * moment and cancels instead of teleporting to stale coordinates.
 */
public class HomeCommand extends SafeCommand implements TabCompleter {

    private final HomeManager homeManager;
    private final HomesGUI homesGUI;
    private final TeleportManager teleportManager;

    public HomeCommand(JavaPlugin plugin, HomeManager homeManager, HomesGUI homesGUI, TeleportManager teleportManager, Messages messages) {
        super(plugin, messages);
        this.homeManager = homeManager;
        this.homesGUI = homesGUI;
        this.teleportManager = teleportManager;
    }

    @Override
    protected boolean execute(@NonNull CommandSender sender, @NonNull Command command, @NonNull String label, String @NonNull [] args) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }

        if (args.length == 0) {
            homesGUI.open(player, homeManager);
            return true;
        }

        String name = args[0];
        Home home = homeManager.getHome(player.getUniqueId(), name);

        if (home == null) {
            player.sendMessage(messages.get("home.not-found"));
            return true;
        }

        UUID uuid = player.getUniqueId();
        teleportManager.requestTeleport(player, () -> homeManager.getHome(uuid, name), "home.success");
        return true;
    }

    @Override
    public List<String> onTabComplete(@NonNull CommandSender sender, @NonNull Command command, @NonNull String alias, String @NonNull [] args) {
        if (!(sender instanceof Player player) || args.length != 1) {
            return List.of();
        }
        return homeManager.suggestHomeNames(player.getUniqueId(), args[0]);
    }
}