package de.raindancer.modules.moderation.listener;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.chat.Chat;
import de.raindancer.modules.moderation.ModerationServices;
import de.raindancer.modules.moderation.model.ModerationPermission;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Routing what a moderator says to the staff rather than to the server.
 *
 * <h2>Where this sits in the chain, and why</h2>
 * {@code NORMAL}, and <b>after</b> Core's punishment check, which sits at {@code LOW}. A muted
 * moderator is muted: staff chat is not a way around a mute somebody else handed out, and the order is
 * what makes that true rather than a rule written down somewhere.
 *
 * <p>It also runs after Core's {@code PromptListener}, which sits at {@code LOWEST} and eats lines that
 * are answers to a question. Somebody typing the reason for a ban is answering a prompt, not talking —
 * and having that land in staff chat instead is the sort of thing that only happens once but is very
 * memorable.
 *
 * <h2>Why the message is read as plain text</h2>
 * Because it is rebuilt into the staff line through MiniMessage, and a player who typed
 * {@code <red>} into chat must not thereby colour the staff channel — or, worse, close a tag the
 * template opened. Plain text in, tags supplied here.
 */
public final class StaffChatListener implements IModerationListener {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final ModerationServices services;

    public StaffChatListener(ModerationServices services) {
        this.services = services;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player talking = event.getPlayer();
        if (!services.staffChat().isTalking(talking.getUniqueId())) {
            return;
        }
        if (!talking.hasPermission(ModerationPermission.STAFF_CHAT.node())) {
            // Their permission was taken away while they were still toggled on. Put them back into
            // ordinary chat rather than swallowing the line: a message that reaches nobody is worse
            // than one that reaches the wrong room, because nobody notices it.
            services.staffChat().stop(talking.getUniqueId());
            services.messages().send(talking, "moderation.staff-chat.no-longer-yours");
            return;
        }
        event.setCancelled(true);
        say(talking.getName(), PLAIN.serialize(event.message()));
    }

    /**
     * Puts one line in front of everybody who may read the channel, including the console.
     *
     * <h2>Why the walk is scheduled</h2>
     * This is reached from {@code AsyncChatEvent}, which Paper fires on a worker thread — and reading
     * the online player list and asking each one for a permission is main-thread work. On Folia there
     * is no single main thread at all, and the global region is where a server-wide list is safe to
     * touch. Sending a component to an {@code Audience} is thread-safe; deciding <em>who</em> the
     * audience is, is not.
     */
    public void say(String who, String what) {
        String line = services.staffChat().prefix() + " <white><who></white><gray>: <what>";

        // The console first and without waiting: it needs no player list, and a staff channel whose
        // history is lost when everybody logs off is not much of a record.
        services.chat().console(line, Chat.arg("who", who), Chat.arg("what", what));

        Scheduling.global(services.plugin(), () -> {
            List<Player> staff = new ArrayList<>();
            for (Player listening : services.server().getOnlinePlayers()) {
                if (listening.hasPermission(ModerationPermission.STAFF_CHAT.node())) {
                    staff.add(listening);
                }
            }
            if (!staff.isEmpty()) {
                services.chat().broadcast(staff, line, Chat.arg("who", who), Chat.arg("what", what));
            }
        });
    }

    @Override
    public void forget(UUID player) {
        // Nothing of its own: the toggle lives in StaffChatService, and the session listener is what
        // clears it. Overridden rather than defaulted so that is a decision on the record.
    }

    @Override
    public String describe() {
        return "chat from somebody in staff chat, routed to the staff instead of the server";
    }
}
