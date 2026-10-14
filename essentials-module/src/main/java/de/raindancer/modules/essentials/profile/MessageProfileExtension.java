package de.raindancer.modules.essentials.profile;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.profile.ProfileButton;
import de.raindancer.core.ui.profile.ProfileExtension;
import de.raindancer.modules.essentials.EssentialsServices;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * "Message" on somebody's profile — the same {@code /msg} this module already has, one click away
 * rather than a name that has to be typed correctly.
 *
 * <h2>Who it is offered for</h2>
 * Somebody online here, or visible on another server of the network — {@code /msg} reaches both. A private
 * message to somebody who is nowhere has nowhere to arrive (there is no offline mailbox), so rather than draw a
 * greyed button whose explanation the profile's own last-seen line already gives, nothing is offered. A
 * vanished player on another server is nowhere to anybody: {@code /msg} does not find them either.
 */
public final class MessageProfileExtension implements ProfileExtension {

    private static final Duration PATIENCE = Duration.ofMinutes(2);
    private static final String PROMPT_OWNER = "profile-message";

    private final EssentialsServices services;

    public MessageProfileExtension(EssentialsServices services) {
        this.services = services;
    }

    @Override
    public ProfileButton contribute(Player viewer, OfflinePlayer subject, Menu parent) {
        Optional<String> name = reachable(viewer, subject);
        if (name.isEmpty()) {
            return null;
        }
        ItemStack icon = Icons.of(Material.WRITABLE_BOOK, "<yellow>Message",
                "<gray>Send <white>" + name.get() + "</white> a private message.",
                "<dark_gray>Typed in chat, like /msg.");
        UUID id = subject.getUniqueId();
        return new ProfileButton(icon, click -> promptAndSend(viewer, id, name.get()));
    }

    /**
     * The name {@code /msg} would reach them by, when they can be messaged now. {@code subject} is whatever
     * {@code getOfflinePlayer(uuid)} hands back — never the real online Player, even for somebody who is one —
     * so they are looked up again here.
     */
    Optional<String> reachable(Player viewer, OfflinePlayer subject) {
        if (subject == null || viewer.getUniqueId().equals(subject.getUniqueId())) {
            return Optional.empty();
        }
        Player online = services.server().getPlayer(subject.getUniqueId());
        if (online != null) {
            return Optional.of(online.getName());
        }
        return subject.getName() == null ? Optional.empty()
                : services.messaging().findElsewhere(subject.getName(), services.core()::playersElsewhere);
    }

    private void promptAndSend(Player viewer, UUID to, String name) {
        viewer.closeInventory();
        boolean asked = services.core().prompts().ask(viewer.getUniqueId(), PROMPT_OWNER, PATIENCE,
                typed -> onAnswered(viewer, to, name, typed), () -> { });
        if (!asked) {
            services.chat().tell(viewer,
                    "<red>You are already being asked something else. Finish that conversation first.");
            return;
        }
        services.chat().tell(viewer, "<gray>Type your message to <white>" + name
                + "</white> in chat, or <white>cancel</white>.");
    }

    /**
     * The answer arrives on the prompt's own thread, which may not own either player — sending a
     * message touches both {@link org.bukkit.entity.Entity#getScheduler() their} inventories and
     * chat, neither of which is safe off the thread that owns the sender on Folia.
     */
    void onAnswered(Player viewer, UUID to, String name, String typed) {
        Scheduling.entity(services.plugin(), viewer, () -> {
            // Looked up again rather than trusted from the button: two minutes is long enough for them to have
            // logged out, or to have gone to another server, since it was pressed.
            Player here = services.server().getPlayer(to);
            if (here != null) {
                services.messaging().send(viewer, here, typed);
                return;
            }
            Optional<String> there = services.messaging().findElsewhere(name, services.core()::playersElsewhere);
            if (there.isPresent()) {
                services.messaging().sendAcross(viewer, there.get(), typed);
                return;
            }
            services.messages().send(viewer, "essentials.no-such-player", "player", name);
        });
    }
}
