package de.raindancer.modules.ctf.screen;

import de.raindancer.core.ui.effect.Cues;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.modules.ctf.CtfServices;
import de.raindancer.modules.ctf.model.GameType;
import de.raindancer.modules.ctf.model.Mutator;
import de.raindancer.modules.ctf.service.VoteService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The ballot: one row each for the map, the kind of round and the twist, with the votes so far. */
public final class VoteMenu extends Menu implements ICtfScreen {

    private final CtfServices services;

    public VoteMenu(CtfServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        return MiniMessage.miniMessage().deserialize("<dark_gray>Vote");
    }

    @Override
    protected void render() {
        VoteService votes = services.votes();
        if (!votes.isOpen()) {
            band(2, 4, Icons.of(Material.CLOCK, "<gray>No vote yet", "<gray>It opens when enough players",
                    "<gray>are in and the countdown starts."));
            return;
        }
        row(1, VoteService.Question.MAP);
        row(2, VoteService.Question.TYPE);
        row(3, VoteService.Question.MUTATOR);
    }

    private void row(int band, VoteService.Question question) {
        VoteService votes = services.votes();
        List<String> options = votes.options(question);
        if (options.isEmpty()) {
            return;
        }
        Map<String, Integer> counts = votes.counts(question);
        String mine = votes.choiceOf(viewer.getUniqueId(), question).orElse("");
        int column = 1;
        for (String option : options.subList(0, Math.min(7, options.size()))) {
            Material icon = switch (question) {
                case MAP -> Material.FILLED_MAP;
                case TYPE -> GameType.parse(option).map(GameType::icon).orElse(Material.PAPER);
                case MUTATOR -> Mutator.parse(option).map(Mutator::icon).orElse(Material.PAPER);
            };
            List<String> lore = new ArrayList<>();
            switch (question) {
                case TYPE -> GameType.parse(option).ifPresent(type -> type.lines().forEach(line -> lore.add("<gray>" + line)));
                case MUTATOR -> Mutator.parse(option).ifPresent(twist -> twist.lines().forEach(line -> lore.add("<gray>" + line)));
                case MAP -> services.maps().load(option).filter(map -> !map.author().isBlank())
                        .ifPresent(map -> lore.add("<gray>By " + map.author()));
            }
            lore.add("<gray>Votes: <white>" + counts.getOrDefault(option, 0));
            lore.add(option.equals(mine) ? "<green>Your vote." : "<yellow>Click to vote.");
            String name = (option.equals(mine) ? "<green>" : "<white>") + services.rounds().label(question, option);
            band(band, column++, Icons.of(icon, name, lore), click -> {
                if (votes.cast(viewer.getUniqueId(), question, option)) {
                    services.core().effects().play(viewer.getUniqueId(), Cues.CLICK);
                } else {
                    services.messages().send(viewer, "ctf.vote.closed");
                }
                refresh();
            });
        }
    }

    @Override
    public String breadcrumb() {
        return "vote";
    }

    @Override
    public String describe() {
        return "voting on the next round";
    }
}
