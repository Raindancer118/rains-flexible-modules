package de.raindancer.modules.hungergames.service;

import de.raindancer.core.ui.actionbar.ActionBarPriority;
import de.raindancer.core.ui.actionbar.ActionBars;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.hungergames.HungerGamesSettings;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * The one door every round-wide line goes out through: chat, an on-screen title, and the action bar.
 *
 * <h2>What moved to Core, and why this class is thinner for it</h2>
 * The source engine kept its own placeholder substitution, its own legacy-colour rendering and its own
 * per-channel toggles reading a hand-written settings catalogue. All three are Core's job now:
 * {@link Messages} fills placeholders and renders MiniMessage (escaping anything a player typed, so a
 * tribute named {@code <red>} cannot recolour the sentence about them), and the wording itself — with its
 * placeholders already in it — lives in {@code messages.yml} under keys this module owns. What is left here
 * is purely the fan-out: given a message key, work out which of the three channels are switched on right
 * now, in {@link HungerGamesSettings}, and send to each.
 *
 * <h2>Why the action bar goes through {@link ActionBars} and titles do not</h2>
 * A player has exactly one action bar and several plugins may want it, which is the arbitration
 * {@link ActionBars} exists to do — see its class note. A title has no such contention: Adventure's own
 * {@code showTitle} simply replaces whatever title was showing, and nothing else on this server is fighting
 * a Hunger Games round for a player's title, so there is nothing here for {@code ActionBars} to arbitrate.
 */
public final class AnnouncementService implements IHungerGamesService {

    /** Where an announcement may appear. */
    public enum Style {
        CHAT,
        TITLE,
        ACTIONBAR
    }

    /** How long a round announcement holds the action bar before whatever it interrupted resumes. */
    private static final Duration ACTIONBAR_HOLD = Duration.ofSeconds(4);

    private static final String OWNER = "hungergames-announce";

    private final Messages messages;
    private final ActionBars actionBars;

    private HungerGamesSettings settings = HungerGamesSettings.DEFAULTS;

    public AnnouncementService(Messages messages, ActionBars actionBars) {
        this.messages = messages;
        this.actionBars = actionBars;
    }

    @Override
    public void settings(HungerGamesSettings settings) {
        this.settings = settings;
    }

    /** Sends a message-key announcement to one audience member, on the given channels. */
    public void send(UUID player, Audience recipient, String key, Style[] styles, Object... values) {
        if (!settings.announcementsEnabled()) {
            return;
        }
        Component rendered = messages.get(key, values);
        for (Style style : enabledStylesOf(styles)) {
            switch (style) {
                case CHAT -> messages.send(recipient, key, values);
                case TITLE -> recipient.showTitle(Title.title(rendered, Component.empty()));
                case ACTIONBAR -> {
                    if (actionBars != null && player != null) {
                        actionBars.show(player, OWNER, rendered, ACTIONBAR_HOLD, ActionBarPriority.HIGH);
                    }
                }
            }
        }
    }

    /**
     * The channels {@code styles} asks for that are also switched on globally.
     *
     * <p>Pure, and the whole reason this class is worth a unit test on its own: the fan-out logic — "chat
     * and title were both asked for, but the server has titles switched off" — has nothing to do with a
     * server and everything to do with a set intersection.
     */
    public Set<Style> enabledStylesOf(Style... requested) {
        EnumSet<Style> enabled = EnumSet.noneOf(Style.class);
        for (Style style : requested) {
            if (styleEnabled(style)) {
                enabled.add(style);
            }
        }
        return enabled;
    }

    private boolean styleEnabled(Style style) {
        return switch (style) {
            case CHAT -> settings.announceUseChat();
            case TITLE -> settings.announceUseTitle();
            case ACTIONBAR -> settings.announceUseActionbar();
        };
    }
}
