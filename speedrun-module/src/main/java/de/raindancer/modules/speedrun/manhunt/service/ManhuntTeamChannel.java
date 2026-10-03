package de.raindancer.modules.speedrun.manhunt.service;

import de.raindancer.core.ui.chat.ChatChannel;
import de.raindancer.modules.speedrun.manhunt.model.Hunt;
import de.raindancer.modules.speedrun.manhunt.model.ManhuntTeams;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Team chat — {@code /chat team} — for a Manhunt: your own side. During a hunt that is the hunt's
 * roster; in the lobby, the side you picked. Registered with Core's {@code ChatChannels}; the chat
 * plugin (RainsChat) does the routing, so it needs to be installed for team chat to exist.
 */
public final class ManhuntTeamChannel implements ChatChannel {

    private final ManhuntTeams teams;
    private final Supplier<Optional<Hunt>> liveHunt;

    public ManhuntTeamChannel(ManhuntTeams teams, Supplier<Optional<Hunt>> liveHunt) {
        this.teams = Objects.requireNonNull(teams, "teams");
        this.liveHunt = Objects.requireNonNull(liveHunt, "liveHunt");
    }

    @Override
    public String id() {
        return "team";
    }

    @Override
    public String label() {
        return "Team";
    }

    @Override
    public Optional<Set<UUID>> audienceFor(UUID speaker) {
        Optional<Hunt> hunt = liveHunt.get();
        if (hunt.isPresent() && hunt.get().everybody().contains(speaker)) {
            return Optional.of(hunt.get().isRunner(speaker) ? hunt.get().runners() : hunt.get().hunters());
        }
        if (teams.isRunner(speaker)) {
            return Optional.of(teams.runners());
        }
        if (teams.isHunter(speaker)) {
            return Optional.of(teams.hunters());
        }
        return Optional.empty();
    }

    @Override
    public String tagFor(UUID speaker) {
        Optional<Hunt> hunt = liveHunt.get();
        boolean runner = hunt.isPresent() && hunt.get().everybody().contains(speaker)
                ? hunt.get().isRunner(speaker) : teams.isRunner(speaker);
        return runner ? "[Runners]" : "[Hunters]";
    }
}
