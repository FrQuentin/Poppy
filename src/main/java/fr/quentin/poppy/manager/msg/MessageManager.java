package fr.quentin.poppy.manager.msg;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jspecify.annotations.NonNull;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks each player's current private-message conversation partner,
 * backing /reply. Updated symmetrically on every /msg or /reply: both
 * the sender's and the recipient's "last partner" point at each other,
 * so either side can use /reply to continue the conversation naturally
 * — not just whoever received the most recent message.
 *
 * <p>{@link #onQuit} only removes the leaving player as a KEY (their own
 * outgoing /reply target) — deliberately leaves them as a VALUE in
 * anyone else's entry alone. Someone mid-conversation with a player who
 * just disconnected still sees them as their last partner, and gets the
 * normal "player not found"-style message when they actually try to
 * /reply, rather than a silent "you have nobody to reply to" that erases
 * the conversation the instant the other side goes offline.
 */
public class MessageManager implements Listener {

    private final Map<UUID, UUID> lastPartner = new HashMap<>();

    public void recordConversation(UUID a, UUID b) {
        lastPartner.put(a, b);
        lastPartner.put(b, a);
    }

    public UUID getLastPartner(UUID uuid) {
        return lastPartner.get(uuid);
    }

    @EventHandler
    public void onQuit(@NonNull PlayerQuitEvent event) {
        lastPartner.remove(event.getPlayer().getUniqueId());
    }
}