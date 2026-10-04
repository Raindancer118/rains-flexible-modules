package de.raindancer.modules.speedrun;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.chat.ChatButton;
import de.raindancer.core.ui.checklist.Checklist;
import de.raindancer.core.ui.checklist.ChecklistChat;
import de.raindancer.core.ui.chat.ChatButtons;
import de.raindancer.core.ui.menu.ConfirmMenu;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.speedrun.util.PermissionNodes;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Every admin action the lobby offers, once — whether it is reached from the hub, a command, or a
 * chat button under a refusal. A refusal is never only a "no": {@link #suggest} adds the buttons
 * that fix it.
 */
public final class SpeedrunActions {

    /** How long a suggested-fix button keeps working. */
    static final Duration BUTTONS_LAST = Duration.ofMinutes(5);

    private final SpeedrunLobby lobby;
    /** What a command was handed, for a lobby without a toolkit; the toolkit's own wins. */
    private final Messages fallback;

    public SpeedrunActions(SpeedrunLobby lobby) {
        this(lobby, null);
    }

    public SpeedrunActions(SpeedrunLobby lobby, Messages messages) {
        this.lobby = lobby;
        this.fallback = messages;
    }

    private Optional<SpeedrunToolkit> kit() {
        return lobby.toolkit();
    }

    private Messages messages() {
        return kit().map(SpeedrunToolkit::messages).orElse(fallback);
    }

    private void say(CommandSender to, String key, Object... values) {
        Messages messages = messages();
        if (messages != null && to != null) {
            messages.send(to, key, values);
        }
    }

    // ---------------------------------------------------------------------------- starting

    /**
     * The start block's press: a countdown for everybody racing in the lobby world, their hands
     * emptied for it. A refusal is said with what fixes it.
     */
    public SpeedrunLobby.StartOutcome start(CommandSender by) {
        Set<UUID> present = lobby.presentInLobbyWorld();
        SpeedrunLobby.StartOutcome outcome = lobby.beginCountdown(present);
        if (outcome == SpeedrunLobby.StartOutcome.STARTED) {
            World lobbyWorld = Bukkit.getWorld(lobby.config().worldName());
            if (lobbyWorld != null) {
                for (Player racer : lobbyWorld.getPlayers()) {
                    if (present.contains(racer.getUniqueId())) {
                        // Folia: another racer's inventory belongs to their region, not the clicker's.
                        Scheduling.entity(lobby.plugin(), racer, () -> racer.getInventory().clear());
                    }
                }
            }
            return outcome;
        }
        refuse(by, outcome, present);
        return outcome;
    }

    /** Says why {@code outcome} stopped a start, with the buttons that fix it. */
    public void refuse(CommandSender to, SpeedrunLobby.StartOutcome outcome, Set<UUID> racers) {
        Messages messages = messages();
        if (messages == null || to == null) {
            return;
        }
        Component line = messages.prefixed(lobby.messageFor(outcome, racers), "mode", lobby.config().gameMode(),
                "world", lobby.config().worldName());
        to.sendMessage(withButtons(to, line, fixesFor(outcome)));
    }

    private List<SpeedrunPreflight.Fix> fixesFor(SpeedrunLobby.StartOutcome outcome) {
        return switch (outcome) {
            case NO_END_CONDITION -> List.of(SpeedrunPreflight.Fix.DRAGON_GOAL);
            case WORLD_MISSING -> List.of(SpeedrunPreflight.Fix.CREATE_WORLDS);
            case MODE_MISSING -> List.of(SpeedrunPreflight.Fix.PLAIN_RACE);
            case NO_PARTICIPANTS -> List.of(SpeedrunPreflight.Fix.BRING_EVERYBODY);
            case NOT_READY -> lobby.state() == SpeedrunLobbyState.FINISHED
                    ? List.of(SpeedrunPreflight.Fix.RESET) : List.of();
            default -> List.of();
        };
    }

    /** {@code line}, with a button per fix this sender may use and one to the full check. */
    Component withButtons(CommandSender to, Component line, List<SpeedrunPreflight.Fix> fixes) {
        ChatButtons buttons = kit().map(SpeedrunToolkit::buttons).orElse(null);
        if (buttons == null || !(to instanceof Player player)) {
            return line;
        }
        List<ChatButton> row = new ArrayList<>();
        boolean admin = player.hasPermission(PermissionNodes.ADMIN);
        for (SpeedrunPreflight.Fix fix : fixes) {
            if (admin && fix != SpeedrunPreflight.Fix.NONE && fix != SpeedrunPreflight.Fix.MODE_SETUP) {
                row.add(buttons.label("<green>[" + fixLabel(fix) + "]</green>")
                        .tooltip("<gray>" + fixTooltip(fix))
                        .forOnly(player.getUniqueId()).expiringIn(BUTTONS_LAST)
                        .does(clicker -> onTheirThread(clicker, who -> applyIfAllowed(fix, who, null))));
            }
        }
        row.add(buttons.label("<aqua>[Pre-flight check]</aqua>")
                .tooltip("<gray>Everything a start needs, each with a one-click fix")
                .runs("/speedrun check"));
        return line.append(Component.text(" ")).append(buttons.row(row.toArray(ChatButton[]::new)));
    }

    static String fixLabel(SpeedrunPreflight.Fix fix) {
        return switch (fix) {
            case NONE -> "";
            case RESET -> "Reset the world";
            case CREATE_WORLDS -> "Create the worlds";
            case PLAIN_RACE -> "Play a plain race";
            case DRAGON_GOAL -> "Race for the dragon";
            case BRING_EVERYBODY -> "Bring everybody here";
            case MODE_SETUP -> "Open the game's page";
            case NO_KIT -> "Switch the kit off";
            case RANDOM_SEED -> "Use random seeds";
        };
    }

    static String fixTooltip(SpeedrunPreflight.Fix fix) {
        return switch (fix) {
            case NONE -> "";
            case RESET -> "Deletes the run's worlds and makes new ones — asks first";
            case CREATE_WORLDS -> "Makes whichever of the three worlds is missing";
            case PLAIN_RACE -> "Sets game-mode to a plain race";
            case DRAGON_GOAL -> "Sets the goal to killing the dragon";
            case BRING_EVERYBODY -> "Teleports everybody online who is racing to the lobby";
            case MODE_SETUP -> "Opens the game mode's own page";
            case NO_KIT -> "Sets practice-kit to NONE, so the run is ranked";
            case RANDOM_SEED -> "Sets seed-mode to RANDOM";
        };
    }

    /** The pre-flight check as it stands, for {@code viewer} — null for the console. */
    public Checklist checklist(Player viewer) {
        return SpeedrunPreflight.of(lobby, lobby.presentInLobbyWorld(), this, viewer);
    }

    /** The pre-flight check said in chat — Core's checklist lines, each red one with its fix as a button. */
    public void checkInWords(CommandSender to) {
        ChecklistChat.tell(to, checklist(to instanceof Player player ? player : null),
                kit().map(SpeedrunToolkit::buttons).orElse(null));
    }

    /**
     * {@link #apply}, for somebody still allowed to — a chat button outlives the moment it was sent,
     * and a permission taken away in between must count.
     */
    public void applyIfAllowed(SpeedrunPreflight.Fix fix, Player by, Menu parent) {
        SpeedrunAccess needed = fix == SpeedrunPreflight.Fix.RESET ? SpeedrunAccess.RESET : SpeedrunAccess.FIX;
        if (needed.allows(lobby, by)) {
            apply(fix, by, parent);
        } else {
            say(by, "speedrun.command.staff-only", "word", fixLabel(fix));
        }
    }

    /**
     * Does what {@code fix} says. A reset asks first: with {@code parent} through a confirmation
     * page, from a chat button through another chat button.
     */
    public void apply(SpeedrunPreflight.Fix fix, Player by, Menu parent) {
        switch (fix) {
            case NONE -> { }
            case MODE_SETUP -> lobby.mode().flatMap(SpeedrunMode::setup).ifPresent(setup -> setup.open(by, parent));
            case RESET -> confirmReset(by, parent);
            case CREATE_WORLDS -> Scheduling.global(lobby.plugin(), () -> {
                lobby.ensureWorldExists();
                say(by, "speedrun.fix.worlds-created");
            });
            case PLAIN_RACE -> {
                lobby.settings().set("game-mode", "");
                say(by, "speedrun.fix.plain-race");
            }
            case DRAGON_GOAL -> {
                lobby.settings().set("advancement-key", SpeedrunSettings.DRAGON_KILL_ADVANCEMENT);
                say(by, "speedrun.fix.dragon-goal");
            }
            case BRING_EVERYBODY -> say(by, "speedrun.fix.brought", "players", String.valueOf(bringEverybody()));
            case NO_KIT -> {
                lobby.settings().set("practice-kit", SpeedrunPracticeKit.NONE.name());
                say(by, "speedrun.fix.no-kit");
            }
            case RANDOM_SEED -> {
                lobby.settings().set("seed-mode", SpeedrunSeedMode.RANDOM.name());
                say(by, "speedrun.fix.random-seed");
            }
        }
    }

    /** Everybody online and not spectating, sent to the lobby world's spawn. @return how many */
    public int bringEverybody() {
        World lobbyWorld = Bukkit.getWorld(lobby.config().worldName());
        if (lobbyWorld == null) {
            return 0;
        }
        int sent = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!lobby.isSpectator(player.getUniqueId()) && player.getWorld() != lobbyWorld) {
                player.teleportAsync(lobbyWorld.getSpawnLocation());
                sent++;
            }
        }
        return sent;
    }

    // ---------------------------------------------------------------------------- reset

    /**
     * Asks before the world is deleted — on a page when there is a menu to come back to, otherwise
     * in chat with a button that expires.
     */
    public void confirmReset(Player by, Menu parent) {
        List<String> consequences = List.of(
                "<gray>Ends whatever run is under way.",
                "<gray>Deletes <white>" + SpeedrunScreens.text(lobby.config().worldName()) + "</white>, its nether and its End,",
                "<gray>and makes new ones from the next seed.",
                "<gray>Everybody in them is put back in the fresh lobby.");
        SpeedrunToolkit tools = kit().orElse(null);
        if (parent != null && tools != null && tools.brand() != null) {
            new ConfirmMenu(by, tools.brand(), parent, "<red>Regenerate the world?", consequences,
                    () -> {
                        if (SpeedrunAccess.RESET.allows(lobby, by)) {
                            reset(by);
                        }
                    }).open();
            return;
        }
        ChatButtons buttons = tools == null ? null : tools.buttons();
        Messages messages = messages();
        if (buttons == null || messages == null) {
            reset(by);
            return;
        }
        by.sendMessage(messages.prefixed("speedrun.reset.confirm", "world", lobby.config().worldName())
                .append(Component.text(" "))
                .append(buttons.row(buttons.label("<red>[Yes, regenerate it]</red>")
                        .tooltip("<gray>The world is deleted. This cannot be undone.")
                        .forOnly(by.getUniqueId()).expiringIn(Duration.ofSeconds(30))
                        .does(clicker -> onTheirThread(clicker, who -> {
                            if (SpeedrunAccess.RESET.allows(lobby, who)) {
                                reset(who);
                            }
                        })))));
    }

    /** The reset itself, said to whoever asked for it. */
    public void reset(CommandSender by) {
        SpeedrunLobby.ResetOutcome outcome = lobby.forceReset();
        say(by, outcome == SpeedrunLobby.ResetOutcome.RESET ? "speedrun.reset.done"
                : "speedrun.reset.countdown-in-progress");
    }

    // ---------------------------------------------------------------------------- clock

    /** {@code /speedrunresume}: everybody in the run's worlds, at {@code already}. */
    public SpeedrunLobby.StartOutcome resume(CommandSender by, Duration already) {
        Set<UUID> present = lobby.presentInRunWorlds();
        SpeedrunLobby.StartOutcome outcome = lobby.resume(present, already);
        if (outcome == SpeedrunLobby.StartOutcome.STARTED) {
            say(by, "speedrun.resume.done", "players", String.valueOf(present.size()),
                    "time", SpeedrunTimerDisplay.plain(already));
        } else if (outcome == SpeedrunLobby.StartOutcome.NOT_READY) {
            say(by, "speedrun.resume.not-ready");
        } else {
            refuse(by, outcome, present);
        }
        return outcome;
    }

    /** {@code /speedruntime}: the running clock set to {@code reading}. */
    public boolean setClock(CommandSender by, Duration reading) {
        boolean set = lobby.session().map(session -> session.setElapsed(reading)).orElse(false);
        say(by, set ? "speedrun.time.set" : "speedrun.time.no-run", "time", SpeedrunTimerDisplay.plain(reading));
        return set;
    }

    /** Asks {@code player} for a time in a window — from a menu; {@code then} gets it parsed. */
    public void askTime(Player player, String titleKey, Consumer<Duration> then) {
        lobby.input().ifPresent(input -> input.window(player, text(titleKey), "", SpeedrunInput.TIME, then));
    }

    /** The same in chat — from a command typed without the time. */
    public void askTimeInChat(Player player, String promptKey, Consumer<Duration> then) {
        lobby.input().ifPresent(input -> input.chat(player, text(promptKey), SpeedrunInput.TIME,
                List.of("0", "42:05", "1:02:03"), then));
    }

    /** The seed page's "type the seed": the answer becomes the fixed seed, for somebody with SEEDS. */
    public void askSeed(Player player, Runnable after) {
        askSeed(player, SpeedrunAccess.SEEDS, after);
    }

    /**
     * Asks for one seed in a window; the answer becomes the fixed seed — asked again for {@code access}
     * at the moment it is typed, as every prompt's answer is.
     */
    public void askSeed(Player player, SpeedrunAccess access, Runnable after) {
        lobby.input().ifPresent(input -> input.window(player, text("speedrun.seed.ask"), lobby.config().seed(),
                SpeedrunInput.SEED, typed -> {
                    if (!access.allows(lobby, player)) {
                        return;
                    }
                    lobby.settings().set("seed", typed);
                    lobby.settings().set("seed-mode", SpeedrunSeedMode.FIXED.name());
                    say(player, "speedrun.seed.set", "seed", typed);
                    after.run();
                }));
    }

    /** Asks for a pool of seeds in a window; the answer becomes the pool, for somebody with SEEDS. */
    public void askSeedPool(Player player, Runnable after) {
        lobby.input().ifPresent(input -> input.window(player, text("speedrun.seed.ask-pool"),
                lobby.config().seedPool(), SpeedrunInput.SEED_POOL, typed -> {
                    if (!SpeedrunAccess.SEEDS.allows(lobby, player)) {
                        return;
                    }
                    lobby.settings().set("seed-pool", typed);
                    lobby.settings().set("seed-mode", SpeedrunSeedMode.POOL.name());
                    say(player, "speedrun.seed.pool-set", "count", String.valueOf(SpeedrunSeeds.pool(typed).size()));
                    after.run();
                }));
    }

    /** A message as plain text — what a window title or a chat question takes. */
    private String text(String key) {
        Messages messages = messages();
        return messages == null ? key : de.raindancer.core.ui.text.Text.plain(messages.get(key));
    }

    // ---------------------------------------------------------------------------- the HUD

    /** Moves {@code player}'s splits to the next place round — sidebar, boss bar, action bar, off. */
    public SpeedrunHudMode cycleHud(Player player) {
        SpeedrunToolkit tools = kit().orElse(null);
        if (tools == null || tools.hud() == null || tools.prefs() == null) {
            return SpeedrunHudMode.OFF;
        }
        SpeedrunHudMode next = tools.hud().modeOf(player.getUniqueId()).next();
        setHud(player, next);
        return next;
    }

    public void setHud(Player player, SpeedrunHudMode mode) {
        SpeedrunToolkit tools = kit().orElse(null);
        if (tools == null || tools.hud() == null || tools.prefs() == null) {
            return;
        }
        tools.prefs().hud(player.getUniqueId(), mode);
        tools.hud().redraw(player.getUniqueId());
        say(player, "speedrun.hud.now", "place", mode.label());
    }

    private void onTheirThread(UUID clicker, Consumer<Player> action) {
        Player player = Bukkit.getPlayer(clicker);
        if (player != null) {
            Scheduling.entity(lobby.plugin(), player, () -> action.accept(player));
        }
    }
}
