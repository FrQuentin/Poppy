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
 * backing /reply. Updated on every /msg or /reply.
 *
 * <p><b>Recipients aren't blindly redirected — see {@link #CONVERSATION_HOLD_MILLIS}:</b>
 * the sender always points at the recipient, but the recipient only
 * points back at the sender if they're not already in a recent
 * conversation with someone else. Without this, {@link #recordConversation}
 * unconditionally overwriting both directions was a trivial social-
 * engineering vector on a public server: while A and B were mid-conversation,
 * C could send A a single unrelated /msg, silently hijacking A's next
 * /reply away from B and toward C instead — no permission required, no
 * warning shown. The hold window is deliberately short (not permanent):
 * if B never actually replies, the window lapses and someone else can
 * legitimately start a conversation with A.
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

    /** How long a held conversation partner resists being overwritten by a third party. */
    private static final long CONVERSATION_HOLD_MILLIS = 60_000L;

    private record Partner(UUID uuid, long updatedAt) {
    }

    private final Map<UUID, Partner> lastPartner = new HashMap<>();

    /**
     * The sender always points at the recipient. The recipient only
     * points back if they're not already in a recent conversation with
     * someone else — see the class-level doc.
     */
    public void recordConversation(UUID sender, UUID recipient) {
        long now = System.currentTimeMillis();
        lastPartner.put(sender, new Partner(recipient, now));

        Partner current = lastPartner.get(recipient);
        boolean held = current != null
                && !current.uuid().equals(sender)
                && now - current.updatedAt() < CONVERSATION_HOLD_MILLIS;

        if (!held) {
            lastPartner.put(recipient, new Partner(sender, now));
        }
    }

    public UUID getLastPartner(UUID uuid) {
        Partner partner = lastPartner.get(uuid);
        return partner != null ? partner.uuid() : null;
    }

    @EventHandler
    public void onQuit(@NonNull PlayerQuitEvent event) {
        lastPartner.remove(event.getPlayer().getUniqueId());
    }
}