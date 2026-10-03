package de.raindancer.modules.manhunt.hud;

import de.raindancer.core.ui.effect.Cues;
import de.raindancer.core.ui.effect.Effects;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.ui.profile.PlayerSwitch;
import de.raindancer.modules.manhunt.ManhuntSettings;
import de.raindancer.modules.manhunt.model.Hunt;
import de.raindancer.modules.manhunt.stats.HuntSummary;
import de.raindancer.modules.manhunt.stats.Milestone;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * A milestone, told to everybody in the hunt: a title, a sound and a line in chat — the chat line
 * always, the title and the sound only for those who have not muted them ({@link #SWITCH}) on a
 * server that has them on.
 */
public final class Announcer {

    /** Each player's own "titles and sounds for milestones". */
    public static final PlayerSwitch SWITCH = new PlayerSwitch("manhunt", "announcements", true);

    private static final Title.Times TIMES = Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(3),
            Duration.ofMillis(750));

    private final Plugin plugin;
    private final Messages messages;
    private final Effects effects;
    private final Supplier<ManhuntSettings> settings;
    private final Predicate<Player> wants;

    public Announcer(Plugin plugin, Messages messages, Effects effects, Supplier<ManhuntSettings> settings,
                     Predicate<Player> wants) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.effects = effects;
        this.settings = Objects.requireNonNull(settings, "settings");
        this.wants = Objects.requireNonNull(wants, "wants");
    }

    public void milestone(Hunt hunt, Milestone milestone, String who, long atMillis) {
        String name = who == null ? "Somebody" : who;
        boolean titles = settings.get().announceMilestones();
        for (UUID id : hunt.everybody()) {
            Player player = plugin.getServer().getPlayer(id);
            if (player == null) {
                continue;
            }
            messages.send(player, "manhunt.milestone.chat", "who", name,
                    "milestone", messages.raw("manhunt.milestone-name." + milestone.id()),
                    "time", HuntSummary.clock(atMillis));
            if (titles && wants.test(player)) {
                player.showTitle(Title.title(
                        messages.get("manhunt.milestone." + milestone.id() + ".title", "who", name),
                        messages.get("manhunt.milestone." + milestone.id() + ".subtitle", "who", name), TIMES));
                if (effects != null) {
                    effects.play(id, Cues.NOTIFY);
                }
            }
        }
    }
}
