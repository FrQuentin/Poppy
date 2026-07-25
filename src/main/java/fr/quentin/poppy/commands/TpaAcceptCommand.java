package fr.quentin.poppy.commands;

import fr.quentin.poppy.manager.TeleportManager;
import fr.quentin.poppy.manager.TpaManager;
import fr.quentin.poppy.model.Home;
import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.PoppyConfig;
import fr.quentin.poppy.util.PoppyLogger;
import fr.quentin.poppy.util.SafeCommand;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

import java.util.List;

/**
 * Handles /tpaccept <player>: accepts a pending /tpa or /tpahere request
 * from the given player. Which of the two players actually moves depends
 * on the request's {@link TpaManager.Type} — see {@link TpaManager}.
 *
 * <p>The actual teleport goes through {@link TeleportManager#requestTeleport},
 * so the usual warmup/cancel-on-move/combat-tag rules apply to whoever is
 * moving, exactly like /home or /back.
 *
 * <p>{@link #withinAllowedRange} enforces {@code tpa-max-distance} and
 * {@code tpa-allow-cross-world} (config.yml, both permissive/unlimited by
 * default) before actually teleporting — without a limit, /tpa and
 * /tpahere function as an unrestricted, instant teleport network between
 * two accounts across the whole map (or between dimensions), which is
 * often considered an exploit on a competitive public server even though
 * it isn't a technical bug. Checked here (accept time), not at request
 * time, since positions can change during the {@code tpa-expiry-seconds}
 * window a request stays pending.
 */
public class TpaAcceptCommand extends SafeCommand implements TabCompleter {

    private final TpaManager tpaManager;
    private final TeleportManager teleportManager;
    private final PoppyConfig config;
    private final PoppyLogger logger;

    public TpaAcceptCommand(JavaPlugin plugin, TpaManager tpaManager, TeleportManager teleportManager,
                            Messages messages, PoppyConfig config, PoppyLogger logger) {
        super(plugin, messages);
        this.tpaManager = tpaManager;
        this.teleportManager = teleportManager;
        this.config = config;
        this.logger = logger;
    }

    @Override
    protected boolean execute(@NonNull CommandSender sender, @NonNull Command command, @NonNull String label, String @NonNull [] args) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }

        if (args.length != 1) {
            player.sendMessage(messages.get("tpa.accept-usage"));
            return true;
        }

        TpaManager.Request request = tpaManager.findRequestByName(player.getUniqueId(), args[0]);
        if (request == null) {
            player.sendMessage(messages.get("tpa.no-request", "player", args[0]));
            return true;
        }

        Player requester = Bukkit.getPlayer(request.requester());
        tpaManager.removeRequest(player.getUniqueId(), request.requester());

        if (requester == null) {
            // requester disconnected between the request and the accept
            player.sendMessage(messages.get("tpa.no-request", "player", args[0]));
            return true;
        }

        if (!withinAllowedRange(player, requester)) {
            player.sendMessage(messages.get("tpa.too-far"));
            requester.sendMessage(messages.get("tpa.too-far"));
            return true;
        }

        if (request.type() == TpaManager.Type.NORMAL) {
            // the requester moves to the accepter (this player)
            logger.log(PoppyLogger.Category.TPA, player, "accepted /tpa from " + requester.getName());
            player.sendMessage(messages.get("tpa.accept-success", "player", requester.getName()));
            Home destination = Home.fromLocation(player.getName(), player.getLocation());
            teleportManager.requestTeleport(requester, destination, "tpa.teleported");
        } else {
            // the accepter (this player) moves to the requester
            logger.log(PoppyLogger.Category.TPA, player, "accepted /tpahere from " + requester.getName());
            player.sendMessage(messages.get("tpa.accept-here-success", "player", requester.getName()));
            Home destination = Home.fromLocation(requester.getName(), requester.getLocation());
            teleportManager.requestTeleport(player, destination, "tpa.teleported");
        }

        return true;
    }

    private boolean withinAllowedRange(Player a, Player b) {
        boolean sameWorld = a.getWorld().equals(b.getWorld());

        if (!sameWorld) {
            return config.tpaAllowCrossWorld();
        }

        double maxDistance = config.tpaMaxDistance();
        if (maxDistance <= 0) {
            return true;
        }

        return a.getLocation().distance(b.getLocation()) <= maxDistance;
    }

    @Override
    public List<String> onTabComplete(@NonNull CommandSender sender, @NonNull Command command, @NonNull String alias, String @NonNull [] args) {
        if (!(sender instanceof Player player) || args.length != 1) {
            return List.of();
        }
        return tpaManager.pendingRequesterNames(player.getUniqueId(), args[0]);
    }
}