package de.raindancer.modules.essentials.screen;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.essentials.EssentialsServices;
import de.raindancer.modules.essentials.model.Nickname;
import de.raindancer.modules.essentials.util.PermissionNodes;
import de.raindancer.modules.essentials.util.Players;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * What bare {@code /nick} opens, rather than the command needing a name straight off the command
 * line: set one, take it off, or — for whoever may manage it — jump to the blocklist editor.
 * {@code /nick <name>} and {@code /nick off} still work exactly as they did, unchanged; this is the
 * other door into the same {@link de.raindancer.modules.essentials.service.NicknameService}.
 *
 * <h2>Why typing happens in chat, not on this page</h2>
 * See Core's {@code SettingsChatInput} for the same call: an anvil rename cannot show what a
 * nickname is now or how long one may be, both of which matter more here than staying in the
 * window. This closes the inventory, asks in chat through Core's shared {@code ChatPrompts}, and
 * hands the line to {@code NicknameService.set} — the same validation, the same refusals, the same
 * blocklist flagging {@code /nick <name>} already had.
 */
public final class NickMenu extends Menu {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final Duration PATIENCE = Duration.ofMinutes(2);
    private static final String PROMPT_OWNER = "nick";

    private final EssentialsServices services;

    public NickMenu(EssentialsServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent, 3);
        this.services = services;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Your nickname");
    }

    @Override
    public String breadcrumb() {
        return "Nickname";
    }

    @Override
    protected void render() {
        boolean enabled = services.nicknames().isEnabled();
        Optional<String> current = services.nicknames().nicknameOf(viewer.getUniqueId());

        band(MenuLayout.WHO, 4, Icons.head(viewer, "<white>" + viewer.getName(),
                current.map(nick -> List.of("<gray>Known as: <white>" + nick))
                        .orElse(List.of("<gray>No nickname set — shown as your own name."))));

        band(MenuLayout.WHO, 2, enabled,
                Icons.of(Material.NAME_TAG, "<white>Set a nickname",
                        "<gray>Type it in chat.",
                        "<gray>Colour is allowed.",
                        "<gray>Up to " + services.config().nicknameLimit() + " characters."),
                "Nicknames are switched off on this server",
                event -> promptForNickname());

        band(MenuLayout.WHO, 6, enabled && current.isPresent(),
                Icons.of(Material.BARRIER, "<red>Remove it",
                        "<gray>Go back to being shown as",
                        "<gray>your own name."),
                current.isEmpty() ? "You have no nickname set"
                        : "Nicknames are switched off on this server",
                event -> {
                    services.nicknames().clear(viewer);
                    refresh();
                });

        if (viewer.hasPermission(PermissionNodes.BLOCKLIST_MANAGE)) {
            toolbar(4, Icons.of(Material.BOOK, "<white>Blocklist editor",
                            "<gray>What nobody may nickname themselves."),
                    event -> new BlocklistMenu(services, viewer, this).open());
        }
    }

    private void promptForNickname() {
        boolean asked = services.core().prompts().ask(viewer.getUniqueId(), PROMPT_OWNER, PATIENCE,
                this::onAnswered, this::reopenElsewhere);
        if (!asked) {
            services.chat().tell(viewer,
                    "<red>You are already being asked something else — finish that first.");
            return;
        }
        services.chat().tell(viewer,
                "<gray>Type your new nickname in chat, or \"cancel\" to leave it as it is.");
        viewer.closeInventory();
    }

    /**
     * The answer arrives on Core's prompt thread, which may not own this player — everything past
     * this point ends in {@code player.openInventory()}, which throws on Folia unless it runs on
     * the thread that owns them. See the class note.
     */
    private void onAnswered(String typed) {
        Scheduling.entity(services.plugin(), viewer, () -> {
            String plain = Nickname.of(typed).plain();
            boolean nameInUse = Players.realNameInUse(services.server(), plain)
                    && !plain.equalsIgnoreCase(viewer.getName());
            services.nicknames().set(viewer, typed, nameInUse);
            new NickMenu(services, viewer, parent()).open();
        });
    }

    /** Cancelled, expired, or answered — always ends back here, on the thread that owns the player. */
    private void reopenElsewhere() {
        Scheduling.entity(services.plugin(), viewer,
                () -> new NickMenu(services, viewer, parent()).open());
    }
}
