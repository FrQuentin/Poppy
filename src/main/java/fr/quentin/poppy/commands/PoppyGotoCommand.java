package fr.quentin.poppy.commands;

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
 * <p>The token is a full UUID (128 bits of randomness), making brute-force
 * guessing already computationally infeasible within the token's short
 * expiry window on its own. {@link #failedAttempts}/{@link #lockedUntil}
 * add defense-in-depth on top of that: after
 * {@code sharehome-max-failed-attempts} wrong tokens in a row from the
 * same player, they're locked out of trying again for
 * {@code sharehome-lockout-seconds} — this only slows down a single
 * logged-in account spamming guesses (an attacker would need to be an
 * actual connected player to try at all, since this command requires a
 * {@link Player} sender), but it's a cheap additional barrier.
 *
 * <p>Deliberately not cleared on quit — same reasoning as
 * {@code PoppyLoreListener}'s cooldown map: clearing it would let a
 * brute-forcing player reset their own lockout for free by disconnecting
 * and reconnecting with the same account.
 */
public class PoppyGotoCommand extends SafeCommand {

    private final ShareManager shareManager;
    private final TeleportManager teleportManager;
    private final PoppyConfig config;
    private final PoppyLogger logger;

    private final Map<UUID, Integer> failedAttempts = new HashMap<>();
    private final Map<UUID, Long> lockedUntil = new HashMap<>();

    public PoppyGotoCommand(JavaPlugin plugin, ShareManager shareManager, TeleportManager teleportManager,
                            Messages messages, PoppyConfig config, PoppyLogger logger) {
        super(plugin, messages);
        this.shareManager = shareManager;
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

        Home home = shareManager.get(args[0]);
        if (home == null) {
            registerFailedAttempt(player);
            player.sendMessage(messages.get("sharehome.expired"));
            return true;
        }

        failedAttempts.remove(player.getUniqueId());
        teleportManager.requestTeleport(player, home, "sharehome.teleport-success");
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