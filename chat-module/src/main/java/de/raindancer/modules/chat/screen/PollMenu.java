package de.raindancer.modules.chat.screen;

import de.raindancer.core.platform.rule.Verdict;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.core.ui.prompt.AnvilInput;
import de.raindancer.core.ui.prompt.Parsers;
import de.raindancer.core.world.time.Times;
import de.raindancer.modules.chat.ChatServices;
import de.raindancer.modules.chat.rules.PollRule;
import de.raindancer.modules.chat.service.PollService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Building a poll by clicking: the question, up to six answers, how long it runs, and Start. What is
 * built is kept per player until it starts, so closing the window loses nothing.
 */
public final class PollMenu extends Menu {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final List<Duration> LENGTHS = List.of(Duration.ofSeconds(30), Duration.ofMinutes(1),
            Duration.ofMinutes(2), Duration.ofMinutes(5), Duration.ofMinutes(10), Duration.ofMinutes(30));

    private final ChatServices services;

    public PollMenu(ChatServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>New poll");
    }

    @Override
    public String breadcrumb() {
        return "Poll";
    }

    @Override
    protected void render() {
        PollService polls = services.polls();
        PollService.Draft draft = polls.draftOf(viewer.getUniqueId());

        band(MenuLayout.WHO, 2, Icons.of(Material.WRITABLE_BOOK,
                        draft.question().isBlank() ? "<yellow>Ask a question" : "<white>" + MINI.escapeTags(draft.question()),
                        "<gray>What everybody will be asked.", "", "<dark_gray>Click to type it."),
                click -> AnvilInput.open(viewer, "Your question", draft.question().isBlank() ? "" : draft.question(),
                        Parsers.text(PollRule.LONGEST_QUESTION),
                        question -> {
                            polls.draft(viewer.getUniqueId(), polls.draftOf(viewer.getUniqueId()).withQuestion(question));
                            open();
                        },
                        this::open));

        int at = LENGTHS.indexOf(draft.lasting());
        band(MenuLayout.WHO, 4, Icons.of(Material.CLOCK, "<white>Runs for " + Times.describe(draft.lasting()),
                        "<gray>Then the results go up for everybody.", "",
                        "<dark_gray>Click for longer, right click for shorter."),
                click -> {
                    int now = at < 0 ? 2 : at;
                    int next = click.isRightClick() ? Math.max(0, now - 1) : Math.min(LENGTHS.size() - 1, now + 1);
                    polls.draft(viewer.getUniqueId(), draft.lasting(LENGTHS.get(next)));
                    refresh();
                });

        boolean room = draft.answers().size() < PollRule.MOST_ANSWERS;
        band(MenuLayout.WHO, 6, room,
                Icons.of(Material.LIME_DYE, "<green>Add an answer",
                        "<gray>Up to " + PollRule.MOST_ANSWERS + ", " + PollRule.LONGEST_ANSWER + " characters each.", "",
                        "<dark_gray>Click to type one."),
                "That's " + PollRule.MOST_ANSWERS + " already — nobody reads past that",
                click -> AnvilInput.open(viewer, "An answer", "", Parsers.text(PollRule.LONGEST_ANSWER),
                        answer -> {
                            polls.draft(viewer.getUniqueId(), polls.draftOf(viewer.getUniqueId()).withAnswer(answer));
                            open();
                        },
                        this::open));

        for (int index = 0; index < draft.answers().size(); index++) {
            int which = index;
            cell(MenuLayout.RULES, index + 1, Icons.of(Material.NAME_TAG,
                            "<aqua>" + (index + 1) + ". " + MINI.escapeTags(draft.answers().get(index)),
                            "<dark_gray>Click to take it out."),
                    click -> {
                        polls.draft(viewer.getUniqueId(), draft.withoutAnswer(which));
                        refresh();
                    });
        }

        band(MenuLayout.LAND, 3, draft.answers().isEmpty(),
                Icons.of(Material.LEVER, "<white>Just yes or no", "<gray>Fills in the two answers for you."),
                "There are answers already",
                click -> {
                    polls.draft(viewer.getUniqueId(), draft.withAnswer("Yes").withAnswer("No"));
                    refresh();
                });

        Verdict ready = polls.rule().check(draft.question(), draft.answers(), draft.lasting());
        boolean running = polls.current().isPresent();
        List<String> lore = new ArrayList<>(List.of("<gray>Puts it in chat with a button per answer."));
        band(MenuLayout.LAND, 5, ready.isAllowed() && !running,
                Icons.of(Material.EMERALD, "<green>Start the poll", lore),
                running ? "A poll is already running — one at a time" : services.messages().raw(ready.reason())
                        .replaceAll("<[^>]+>", ""),
                click -> {
                    viewer.closeInventory();
                    polls.start(viewer, draft.question(), draft.answers(), draft.lasting());
                });

        if (running) {
            toolbar(4, Icons.of(Material.BARRIER, "<red>End the running poll now",
                            "<gray>The results go up straight away.", "",
                            "<dark_gray>Only whoever started it, or staff."),
                    click -> {
                        viewer.closeInventory();
                        polls.endEarly(viewer);
                    });
        }
    }

    @Override
    protected List<String> helpLines() {
        return services.messages().lines("chat.poll.help").stream().map(MINI::serialize).toList();
    }
}
