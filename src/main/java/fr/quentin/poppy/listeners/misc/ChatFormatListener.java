package fr.quentin.poppy.listeners.misc;

import fr.quentin.poppy.util.DurationFormat;
import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.PoppyConfig;
import fr.quentin.poppy.util.cooldown.CooldownManager;
import fr.quentin.poppy.util.cooldown.CooldownStore;
import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.jspecify.annotations.NonNull;

/**
 * Overrides the default {@code <PlayerName> message} chat format with
 * {@code PlayerName: message}, both in the plugin's light-gray accent
 * color (#a7aeba). Uses {@link AsyncChatEvent#renderer(ChatRenderer)}
 * rather than mutating {@link AsyncChatEvent#message()} directly — this
 * lets each viewer's own client render the sender's name via their
 * existing {@link Component} (preserving any hover/click already
 * attached by another plugin) rather than reconstructing it as plain
 * text.
 *
 * <p>Also enforces {@code chat-cooldown-seconds} — a plain rate limit on
 * how often the same player can send a chat message, checked and
 * enforced before the renderer is ever set: if the player is on
 * cooldown, the event is cancelled outright and nothing is sent to
 * anyone, including the sender's own client. Players with
 * {@code poppy.chat.bypass} skip this entirely.
 */
public class ChatFormatListener implements Listener {

    private static final TextColor ACCENT = TextColor.fromHexString("#a7aeba");

    private final Messages messages;
    private final PoppyConfig config;
    private final CooldownStore cooldown;

    public ChatFormatListener(Messages messages, PoppyConfig config, CooldownManager cooldownManager) {
        this.messages = messages;
        this.config = config;
        this.cooldown = cooldownManager.get("chat");
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onChat(@NonNull AsyncChatEvent event) {
        Player player = event.getPlayer();

        if (!player.hasPermission("poppy.chat.bypass")) {
            long remaining = cooldown.remainingSeconds(player.getUniqueId());
            if (remaining > 0) {
                event.setCancelled(true);
                player.sendMessage(messages.get("chat.cooldown", "time", DurationFormat.format(remaining)));
                return;
            }
            cooldown.start(player.getUniqueId(), config.chatCooldownMillis());
        }

        event.renderer((sourceDisplayName, sourceDisplayNameComponent, message, viewer) ->
                sourceDisplayNameComponent.color(ACCENT)
                        .append(Component.text(": ").color(ACCENT))
                        .append(message.color(ACCENT)));
    }
}