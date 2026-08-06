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

    /**
     * The namespace every one of this module's wording keys lives under in {@code messages.yml}.
     *
     * <h2>The bug this constant exists because of</h2>
     * Core's {@code Messages} registry is server-wide and flat, so a key is {@code hungergames.winner} and
     * not {@code winner}. Five callers passed the bare name — and Core's answer to an unknown key is to
     * render the key itself, so a player who earned a sponsor token was told
     * <i>"&lt;sponsor-token-earned&gt;"</i>, with the plugin's raw jar name in front of it because the
     * fallback carries no brand either.
     *
     * <p>Four of the five were on paths nobody exercises in a unit test: a supply drop landing, a beacon
     * spawning, a shop purchase, a refused purchase. Every one of them is a sentence that only ever appears
     * in front of forty people at once.
     *
     * <p>So the qualification happens <em>here</em> rather than at five call sites. A rule that has to be
     * remembered at every call site is a rule that is followed at four of them by March — and this module's
     * wording still arrives in waves, so there will be more callers.
     */
    private static final String NAMESPACE = "hungergames.";

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
        String wording = qualified(key);
        Component rendered = messages.get(wording, values);
        for (Style style : enabledStylesOf(styles)) {
            switch (style) {
                case CHAT -> messages.send(recipient, wording, values);
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
     * A wording key with this module's namespace on it, whether the caller remembered or not.
     *
     * <p>Public and static so the rule is one function that can be checked without a server — see
     * {@link #NAMESPACE} for what it was like when the rule was five call sites instead.
     *
     * <p>Idempotent: a key that already carries the namespace is returned untouched, so a caller who does
     * remember is not punished with {@code hungergames.hungergames.winner}. Anything else with a dot in it is
     * also left alone — that is another plugin's key, and silently re-homing it into this namespace would turn
     * a deliberate cross-plugin reference into a missing one.
     */
    public static String qualified(String key) {
        if (key == null || key.isEmpty()) {
            return key;
        }
        return key.indexOf('.') < 0 ? NAMESPACE + key : key;
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
