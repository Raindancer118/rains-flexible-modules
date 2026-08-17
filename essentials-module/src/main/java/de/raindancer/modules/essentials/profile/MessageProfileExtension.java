package de.raindancer.modules.essentials.profile;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.profile.ProfileButton;
import de.raindancer.core.ui.profile.ProfileExtension;
import de.raindancer.modules.essentials.EssentialsServices;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;

/**
 * "Message" on somebody's profile — the same {@code /msg} this module already has, one click away
 * rather than a name that has to be typed correctly.
 *
 * <h2>Why only when they are online</h2>
 * {@code MessagingService.send} takes an online {@link Player}, because a private message to
 * somebody who is not here has nowhere to arrive — there is no offline mailbox. Rather than draw a
 * greyed button whose explanation is "they are not here", which the profile's own last-seen line
 * already says, this simply offers nothing for an offline subject: a page that changes shape with
 * who it is about is the whole point of {@link ProfileExtension}.
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
        if (subject == null || viewer.getUniqueId().equals(subject.getUniqueId())) {
            return null;
        }
        // subject is whatever Bukkit.getOfflinePlayer(uuid) hands back — a lightweight wrapper that
        // is never the real online Player, even for somebody who is: looked up again here rather
        // than trusted from the parameter, the same reason onAnswered() re-fetches before sending.
        Player online = Bukkit.getPlayer(subject.getUniqueId());
        if (online == null) {
            return null;
        }
        ItemStack icon = Icons.of(Material.WRITABLE_BOOK, "<yellow>Message",
                "<gray>Send <white>" + online.getName() + "</white> a private message.",
                "<dark_gray>Typed in chat, like /msg.");
        return new ProfileButton(icon, click -> promptAndSend(viewer, online));
    }

    private void promptAndSend(Player viewer, Player to) {
        viewer.closeInventory();
        boolean asked = services.core().prompts().ask(viewer.getUniqueId(), PROMPT_OWNER, PATIENCE,
                typed -> onAnswered(viewer, to, typed), () -> { });
        if (!asked) {
            services.chat().tell(viewer,
                    "<red>You are already being asked something else — finish that first.");
            return;
        }
        services.chat().tell(viewer, "<gray>Type your message to <white>" + to.getName()
                + "</white> in chat, or <white>cancel</white>.");
    }

    /**
     * The answer arrives on the prompt's own thread, which may not own either player — sending a
     * message touches both {@link org.bukkit.entity.Entity#getScheduler() their} inventories and
     * chat, neither of which is safe off the thread that owns the sender on Folia.
     */
    private void onAnswered(Player viewer, Player to, String typed) {
        Scheduling.entity(services.plugin(), viewer, () -> {
            // Re-fetched rather than trusted from the closure: two minutes is long enough for them
            // to have logged out since the button was pressed, and messaging.send needs them online.
            Player stillHere = services.server().getPlayer(to.getUniqueId());
            if (stillHere == null) {
                services.messages().send(viewer, "essentials.no-such-player", "player", to.getName());
                return;
            }
            services.messaging().send(viewer, stillHere, typed);
        });
    }
}
