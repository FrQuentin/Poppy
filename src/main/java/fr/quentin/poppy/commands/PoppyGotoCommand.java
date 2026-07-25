package fr.quentin.poppy.commands;

import fr.quentin.poppy.manager.HomeManager;
import fr.quentin.poppy.manager.ShareManager;
import fr.quentin.poppy.manager.TeleportManager;
import fr.quentin.poppy.model.Home;
import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.PoppyConfig;
import fr.quentin.poppy.util.PoppyLogger;
import fr.quentin.poppy.util.SafeCommand;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Internal command triggered by the clickable link created by /sharehome —
 * never meant to be typed manually. Deliberately has no permission node in
 * plugin.yml: the short-lived {@link ShareManager} token itself is the
 * access control, so any player who received (or guessed) a valid token can
 * use it.
 *
 * <p>The token resolves to the sharer's UUID and home name, re-fetched
 * live from {@link HomeManager} at teleport time via a
 * {@link java.util.function.Supplier} rather than a frozen snapshot — see
 * {@link ShareManager} for why. If the home no longer exists by the time
 * someone clicks the link (deleted after sharing), this fails the same
 * way an expired token would.
 *
 * <p>The token is a full UUID (128 bits of randomness), making brute-force
 * guessing already computationally infeasible within the token's short
 * expiry window on its own. {@link #failedAttempts}/{@link #lockedUntil}
 * add defense-in-depth on top of that: after
 * {@code sharehome-max-failed-attempts} wrong tokens in a row from the
 * same player, they're locked out of trying again for
 * {@code sharehome-lockout-seconds}.
 *
 * <p>Deliberately not cleared on quit — same reasoning as
 * {@code PoppyLoreListener}'s cooldown map: clearing it would let a
 * brute-forcing player reset their own lockout for free by disconnecting
 * and reconnecting with the same account.
 */
public class PoppyGotoCommand extends SafeCommand {

    private final ShareManager shareManager;
    private final HomeManager homeManager;
    private final TeleportManager teleportManager;
    private final PoppyConfig config;
    private final PoppyLogger logger;

    private final Map<UUID, Integer> failedAttempts = new HashMap<>();
    private final Map<UUID, Long> lockedUntil = new HashMap<>();

    public PoppyGotoCommand(JavaPlugin plugin, ShareManager shareManager, HomeManager homeManager, TeleportManager teleportManager,
                            Messages messages, PoppyConfig config, PoppyLogger logger) {
        super(plugin, messages);
        this.shareManager = shareManager;
        this.homeManager = homeManager;
        this.teleportManager = teleportManager;
        this.config = config;
        this.logger = logger;
    }

    @Override
    protected boolean execute(@NonNull CommandSender sender, @NonNull Command command, @NonNull String label, String @NonNull [] args) {
        if (!(sender instanceof Player player) || args.length != 1) {
            return true;
        }

        long lockoutRemaining = lockoutRemainingSeconds(player.getUniqueId());
        if (lockoutRemaining > 0) {
            player.sendMessage(messages.get("sharehome.locked-out", "seconds", String.valueOf(lockoutRemaining)));
            return true;
        }

        ShareManager.SharedHome shared = shareManager.get(args[0]);
        if (shared == null) {
            registerFailedAttempt(player);
            player.sendMessage(messages.get("sharehome.expired"));
            return true;
        }

        failedAttempts.remove(player.getUniqueId());

        UUID ownerUuid = shared.ownerUuid();
        String homeName = shared.homeName();

        Home home = homeManager.getHome(ownerUuid, homeName);
        if (home == null) {
            // The owner deleted or renamed this home since sharing it — the link is
            // no longer meaningful, treat it the same as an expired one.
            player.sendMessage(messages.get("sharehome.expired"));
            return true;
        }

        teleportManager.requestTeleport(player, () -> homeManager.getHome(ownerUuid, homeName), "sharehome.teleport-success");
        return true;
    }

    private void registerFailedAttempt(Player player) {
        UUID uuid = player.getUniqueId();
        int attempts = failedAttempts.merge(uuid, 1, Integer::sum);

        if (attempts >= config.sharehomeMaxFailedAttempts()) {
            failedAttempts.remove(uuid);
            lockedUntil.put(uuid, System.currentTimeMillis() + config.sharehomeLockoutMillis());
            logger.log(PoppyLogger.Category.SHARE, player, "locked out of /poppygoto after too many invalid tokens in a row");
        }
    }

    private long lockoutRemainingSeconds(UUID uuid) {
        Long until = lockedUntil.get(uuid);
        if (until == null) {
            return 0;
        }

        long remainingMillis = until - System.currentTimeMillis();
        if (remainingMillis <= 0) {
            lockedUntil.remove(uuid);
            return 0;
        }

        return (remainingMillis / 1000) + 1;
    }
}