package fr.quentin.poppy.listeners.misc;

import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.jspecify.annotations.NonNull;

/**
 * Overrides the default {@code <PlayerName> message} chat format with
 * {@code PlayerName: message}, both the name and the message body in the
 * plugin's light-gray accent color (#a7aeba) — matching the palette used
 * throughout messages.yml.
 *
 * <p>Uses {@link AsyncChatEvent#renderer(ChatRenderer)} rather than
 * mutating {@link AsyncChatEvent#message()} directly — the renderer
 * approach is what lets each viewer's own client render the sender's
 * name via their existing {@link Component} (preserving any hover/click
 * already attached to it by another plugin, e.g. a nickname/rank prefix
 * system) rather than this listener having to reconstruct that from
 * scratch as plain text.
 */
public class ChatFormatListener implements Listener {

    private static final TextColor ACCENT = TextColor.fromHexString("#a7aeba");

    @EventHandler(priority = EventPriority.LOW)
    public void onChat(@NonNull AsyncChatEvent event) {
        event.renderer((sourceDisplayName, sourceDisplayNameComponent, message, viewer) ->
                sourceDisplayNameComponent.color(ACCENT)
                        .append(Component.text(": ").color(ACCENT))
                        .append(message.color(ACCENT)));
    }
}