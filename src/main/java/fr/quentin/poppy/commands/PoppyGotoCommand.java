package fr.quentin.poppy.commands;

import fr.quentin.poppy.manager.HomeManager;
import fr.quentin.poppy.manager.ShareManager;
import fr.quentin.poppy.manager.TeleportManager;
import fr.quentin.poppy.model.Home;
import fr.quentin.poppy.util.CooldownStore;
import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.PoppyConfig;
import fr.quentin.poppy.util.PoppyLogger;
import fr.quentin.poppy.util.SafeCommand;
import org.bukkit.Bukkit;
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
 * access control.
 *
 * <p>The token resolves to the sharer's UUID and home name, re-fetched
 * live via {@link HomeManager#getHomeUncached} at teleport time — the
 * owner may not be online this session, so this avoids leaking a cache
 * entry {@code HomeCacheListener} would never see a quit event to clean
 * up.
 *
 * <p>The token is a full UUID (128 bits), making brute-force guessing
 * already computationally infeasible on its own. {@link #failedAttempts}/
 * {@link #lockoutStore} add defense-in-depth: after
 * {@code sharehome-max-failed-attempts} wrong tokens in a row, the player
 * is locked out for {@code sharehome-lockout-seconds}. The lockout itself
 * lives in a shared {@link CooldownStore}.
 *
 * <p>{@link #failedAttempts} can't live in {@link CooldownStore} the same
 * way — it's a count, not a plain expiry — so each entry carries its own
 * last-attempt timestamp ({@link FailedAttempts}), and both the attempt
 * count and the raw map itself have an expiry of their own:
 * {@link #registerFailedAttempt} resets the count to 1 if the previous
 * attempt is older than {@link #ATTEMPT_WINDOW_MILLIS} (so a wrong guess
 * five minutes after a previous one doesn't compound toward a lockout),
 * and {@link #purgeStaleAttempts()} — run every 5 minutes — evicts any
 * entry nobody has touched recently at all. Without either, a single
 * wrong token from a player never seen again would leave a permanent
 * entry in memory, the same unbounded-growth pattern {@link CooldownStore}
 * exists to prevent elsewhere, applied here to a counter instead of a
 * plain cooldown.
 */
public class PoppyGotoCommand extends SafeCommand {

    private static final long ATTEMPT_WINDOW_MILLIS = 5 * 60 * 1000L;
    private static final long PURGE_INTERVAL_TICKS = 20L * 60 * 5;

    private record FailedAttempts(int count, long lastAttemptMillis) {
    }

    private final ShareManager shareManager;
    private final HomeManager homeManager;
    private final TeleportManager teleportManager;
    private final PoppyConfig config;
    private final PoppyLogger logger;
    private final CooldownStore lockoutStore;

    private final Map<UUID, FailedAttempts> failedAttempts = new HashMap<>();

    public PoppyGotoCommand(JavaPlugin plugin, ShareManager shareManager, HomeManager homeManager, TeleportManager teleportManager,
                            Messages messages, PoppyConfig config, PoppyLogger logger) {
        super(plugin, messages);
        this.shareManager = shareManager;
        this.homeManager = homeManager;
        this.teleportManager = teleportManager;
        this.config = config;
        this.logger = logger;
        this.lockoutStore = new CooldownStore(plugin);

        Bukkit.getScheduler().runTaskTimer(plugin, this::purgeStaleAttempts, PURGE_INTERVAL_TICKS, PURGE_INTERVAL_TICKS);
    }

    @Override
    protected boolean execute(@NonNull CommandSender sender, @NonNull Command command, @NonNull String label, String @NonNull [] args) {
        if (!(sender instanceof Player player) || args.length != 1) {
            return true;
        }

        long lockoutRemaining = lockoutStore.remainingSeconds(player.getUniqueId());
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

        Home home = homeManager.getHomeUncached(ownerUuid, homeName);
        if (home == null) {
            player.sendMessage(messages.get("sharehome.expired"));
            return true;
        }

        teleportManager.requestTeleport(player, () -> homeManager.getHomeUncached(ownerUuid, homeName), "sharehome.teleport-success");
        return true;
    }

    private void registerFailedAttempt(Player player) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();

        FailedAttempts previous = failedAttempts.get(uuid);
        int count = (previous == null || now - previous.lastAttemptMillis() > ATTEMPT_WINDOW_MILLIS) ? 1 : previous.count() + 1;

        if (count >= config.sharehomeMaxFailedAttempts()) {
            failedAttempts.remove(uuid);
            lockoutStore.start(uuid, config.sharehomeLockoutMillis());
            logger.log(PoppyLogger.Category.SHARE, player, "locked out of /poppygoto after too many invalid tokens in a row");
            return;
        }

        failedAttempts.put(uuid, new FailedAttempts(count, now));
    }

    private void purgeStaleAttempts() {
        long cutoff = System.currentTimeMillis() - ATTEMPT_WINDOW_MILLIS;
        failedAttempts.entrySet().removeIf(entry -> entry.getValue().lastAttemptMillis() < cutoff);
    }
}