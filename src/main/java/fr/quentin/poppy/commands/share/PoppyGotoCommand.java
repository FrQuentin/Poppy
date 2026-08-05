package fr.quentin.poppy.commands.share;

import fr.quentin.poppy.manager.home.HomeManager;
import fr.quentin.poppy.manager.share.ShareManager;
import fr.quentin.poppy.manager.teleport.TeleportManager;
import fr.quentin.poppy.model.Home;
import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.PoppyConfig;
import fr.quentin.poppy.util.PoppyLogger;
import fr.quentin.poppy.util.SafeCommand;
import fr.quentin.poppy.util.cooldown.CooldownManager;
import fr.quentin.poppy.util.cooldown.CooldownStore;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

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
                            Messages messages, PoppyConfig config, PoppyLogger logger, CooldownManager cooldownManager) {
        super(plugin, messages);
        this.shareManager = shareManager;
        this.homeManager = homeManager;
        this.teleportManager = teleportManager;
        this.config = config;
        this.logger = logger;
        this.lockoutStore = cooldownManager.get("poppygoto-lockout");

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