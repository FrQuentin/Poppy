package fr.quentin.poppy.commands.msg;

import fr.quentin.poppy.manager.msg.MessageManager;
import fr.quentin.poppy.util.cooldown.CooldownStore;
import fr.quentin.poppy.util.DurationFormat;
import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.PoppyConfig;
import fr.quentin.poppy.util.SafeCommand;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

import java.util.UUID;

/**
 * Handles /reply (alias: /r) <message>: sends a private message to the
 * sender's current conversation partner (see {@link MessageManager}),
 * displayed exactly like a fresh /msg from the replier's own
 * perspective — same {@code msg.outgoing}/{@code msg.incoming} keys as
 * {@code MsgCommand}.
 */
public class ReplyCommand extends SafeCommand {

    private final MessageManager messageManager;
    private final PoppyConfig config;
    private final CooldownStore cooldown;

    public ReplyCommand(JavaPlugin plugin, MessageManager messageManager, Messages messages, PoppyConfig config, CooldownStore cooldown) {
        super(plugin, messages);
        this.messageManager = messageManager;
        this.config = config;
        this.cooldown = cooldown;
    }

    @Override
    protected boolean execute(@NonNull CommandSender sender, @NonNull Command command, @NonNull String label, String @NonNull [] args) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }

        if (args.length < 1) {
            player.sendMessage(messages.get("msg.reply-usage"));
            return true;
        }

        UUID partnerUuid = messageManager.getLastPartner(player.getUniqueId());
        if (partnerUuid == null) {
            player.sendMessage(messages.get("msg.no-reply-target"));
            return true;
        }

        Player target = Bukkit.getPlayer(partnerUuid);

        // A vanished target is still a valid reply recipient if THEY are the one
        // who initiated this conversation (their own last partner still points
        // back at us) — the player already knows they exist, so nothing about
        // vanish is leaked by letting the reply through. Without this
        // exemption, a moderator messaging a player in vanish (a very common
        // moderation flow) made /reply completely unusable the moment it
        // actually mattered.
        boolean initiatedByTarget = target != null
                && player.getUniqueId().equals(messageManager.getLastPartner(partnerUuid));

        if (target == null || (!player.canSee(target) && !initiatedByTarget)) {
            player.sendMessage(messages.get("msg.reply-target-offline"));
            return true;
        }

        long remaining = cooldown.remainingSeconds(player.getUniqueId());
        if (remaining > 0) {
            player.sendMessage(messages.get("msg.cooldown", "time", DurationFormat.format(remaining)));
            return true;
        }

        String text = String.join(" ", args);

        cooldown.start(player.getUniqueId(), config.msgCooldownMillis());
        messageManager.recordConversation(player.getUniqueId(), target.getUniqueId());

        player.sendMessage(messages.get("msg.outgoing", "target", target.getName(), "message", text));
        target.sendMessage(messages.get("msg.incoming", "sender", player.getName(), "message", text));

        return true;
    }
}