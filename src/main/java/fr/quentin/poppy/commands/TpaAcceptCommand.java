package fr.quentin.poppy.commands;

import fr.quentin.poppy.manager.CombatManager;
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
import java.util.function.Supplier;

/**
 * Handles /tpaccept <player>: accepts a pending /tpa or /tpahere request
 * from the given player. Which of the two players actually moves depends
 * on the request's {@link TpaManager.Type} — see {@link TpaManager}.
 *
 * <p>The destination is re-resolved at the actual moment of teleport (end
 * of warmup), not frozen at accept time — see {@link #liveDestination}.
 * Without that, the destination-in-combat check
 * ({@link #destinationInCombat}) only ever ran once, at accept time: a
 * requester could send /tpahere right BEFORE engaging combat and have an
 * untagged ally materialize in the middle of the fight during the
 * {@code teleport-warmup-seconds} window.
 *
 * <p>{@link TpaManager#removeRequest} is only called once every
 * validation (target offline, distance, destination combat) has already
 * passed — a request rejected by, say, the distance check is left intact
 * rather than consumed, so both players can simply retry without
 * re-sending a brand new request.
 */
public class TpaAcceptCommand extends SafeCommand implements TabCompleter {

    private final TpaManager tpaManager;
    private final TeleportManager teleportManager;
    private final CombatManager combatManager;
    private final PoppyConfig config;
    private final PoppyLogger logger;

    public TpaAcceptCommand(JavaPlugin plugin, TpaManager tpaManager, TeleportManager teleportManager, CombatManager combatManager,
                            Messages messages, PoppyConfig config, PoppyLogger logger) {
        super(plugin, messages);
        this.tpaManager = tpaManager;
        this.teleportManager = teleportManager;
        this.combatManager = combatManager;
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
        if (requester == null) {
            tpaManager.removeRequest(player.getUniqueId(), request.requester());
            player.sendMessage(messages.get("tpa.no-request", "player", args[0]));
            return true;
        }

        if (!withinAllowedRange(player, requester)) {
            player.sendMessage(messages.get("tpa.too-far"));
            requester.sendMessage(messages.get("tpa.too-far"));
            return true;
        }

        Player destinationOwner = request.type() == TpaManager.Type.NORMAL ? player : requester;
        if (destinationInCombat(destinationOwner)) {
            player.sendMessage(messages.get("tpa.destination-in-combat"));
            requester.sendMessage(messages.get("tpa.destination-in-combat"));
            return true;
        }

        // Every validation passed — only now is the request actually consumed.
        tpaManager.removeRequest(player.getUniqueId(), request.requester());

        if (request.type() == TpaManager.Type.NORMAL) {
            // the requester moves to the accepter (this player)
            logger.log(PoppyLogger.Category.TPA, player, "accepted /tpa from " + requester.getName());
            player.sendMessage(messages.get("tpa.accept-success", "player", requester.getName()));
            teleportManager.requestTeleportResolved(requester, liveDestination(player), "tpa.teleported", null);
        } else {
            // the accepter (this player) moves to the requester
            logger.log(PoppyLogger.Category.TPA, player, "accepted /tpahere from " + requester.getName());
            player.sendMessage(messages.get("tpa.accept-here-success", "player", requester.getName()));
            teleportManager.requestTeleportResolved(player, liveDestination(requester), "tpa.teleported", null);
        }

        return true;
    }

    /**
     * Destination re-resolved at the actual moment of teleport (end of
     * warmup) — see the class-level doc. Returns a distinct
     * {@link TeleportManager.Resolution#failureMessagePath()} per failure
     * reason: "went offline" vs "re-entered combat" are very different
     * situations for the mover to understand.
     */
    private Supplier<TeleportManager.Resolution> liveDestination(Player destinationOwner) {
        return () -> {
            if (!destinationOwner.isOnline()) {
                return new TeleportManager.Resolution(null, "teleport.target-missing");
            }
            if (destinationInCombat(destinationOwner)) {
                return new TeleportManager.Resolution(null, "tpa.destination-combat-during-warmup");
            }
            return TeleportManager.Resolution.of(Home.fromLocation(destinationOwner.getName(), destinationOwner.getLocation()));
        };
    }

    private boolean destinationInCombat(Player destinationOwner) {
        return config.tpaBlockToCombat() && combatManager.isInCombat(destinationOwner.getUniqueId());
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