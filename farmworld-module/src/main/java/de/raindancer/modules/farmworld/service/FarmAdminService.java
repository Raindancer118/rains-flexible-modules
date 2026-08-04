package de.raindancer.modules.farmworld.service;

import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.world.farm.WorldSet;
import de.raindancer.core.world.time.Times;
import de.raindancer.modules.farmworld.FarmWorldSettings;
import de.raindancer.modules.farmworld.model.FarmWorldView;
import de.raindancer.modules.farmworld.rules.FarmAccessRule;
import de.raindancer.modules.farmworld.rules.FarmWorldNameRule;
import de.raindancer.modules.farmworld.store.FarmWorldCatalogue;
import de.raindancer.modules.farmworld.util.PermissionNodes;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Making farm worlds, changing them, and throwing one away.
 *
 * <h2>Why every entrance comes through here</h2>
 * Because there are two of them — the commands and the admin menu — and an invariant guarded at one is
 * not guarded. "May this person change farm worlds", "is this a name a farm world can have" and "is the
 * server already at its ceiling" are asked here, once, so the menu and the command cannot come to
 * disagree about any of them.
 *
 * <h2>The one operation in this repository that cannot be undone</h2>
 * {@link #regenerate} deletes up to three worlds. Everything about actually doing it is Core's — moving
 * people out, unloading without saving, asking {@code mayDelete} before it removes a single file, and
 * refusing to recreate a folder it only half deleted. What is here is that <b>nothing reaches it without
 * a confirmation</b>: the menu goes through {@code ConfirmScreen}, and the command wants the word
 * {@code confirm} typed after the name. Both, not either — a guard on one of two entrances is not a
 * guard.
 */
public final class FarmAdminService implements IFarmWorldService {

    private final FarmWorldCatalogue catalogue;
    private final FarmAccessRule access;
    private final FarmWorldNameRule names = new FarmWorldNameRule();
    private final Messages messages;
    private final Plugin plugin;
    private final Server server;
    private final LogChannel log;

    private volatile FarmWorldSettings settings;

    public FarmAdminService(Plugin plugin, Server server, FarmWorldCatalogue catalogue,
                            FarmAccessRule access, Messages messages, LogChannel log,
                            FarmWorldSettings settings) {
        this.plugin = plugin;
        this.server = server;
        this.catalogue = catalogue;
        this.access = access;
        this.messages = messages;
        this.log = log;
        this.settings = settings;
    }

    /**
     * Nothing here reads the settings today.
     *
     * <p>Implemented and kept anyway, because the service that is forgotten when it <em>starts</em>
     * reading something is the one that keeps yesterday's numbers until the next restart — and that gets
     * reported as "the config does not work" rather than as a missing line here.
     */
    @Override
    public void settings(FarmWorldSettings fresh) {
        this.settings = fresh;
    }

    /** The name rule, for a screen that wants to refuse before asking. */
    public FarmWorldNameRule names() {
        return names;
    }

    // ------------------------------------------------------------------------ making one

    /**
     * Makes a farm world, and its worlds with it.
     *
     * @param every how often to throw it away, or null for only when somebody asks
     * @param border how far from the middle it goes, or null for no border
     * @return the farm world, or empty when something refused it — which has already been said
     */
    public Optional<FarmWorldView> create(CommandSender maker, String name, Duration every,
                                          Integer border) {
        if (!mayManage(maker)) {
            return Optional.empty();
        }
        FarmWorldNameRule.Verdict verdict = names.check(name);
        if (!verdict.isFine()) {
            messages.send(maker, verdict.messageKey(),
                    "name", String.valueOf(name), "limit", names.longestName());
            return Optional.empty();
        }
        if (catalogue.exists(name)) {
            messages.send(maker, "farmworlds.already-exists", "name", name);
            return Optional.empty();
        }
        if (!names.isRoomFor(catalogue.count())) {
            messages.send(maker, "farmworlds.too-many", "limit", FarmWorldNameRule.MOST);
            return Optional.empty();
        }

        WorldSet set;
        try {
            set = WorldSet.builder(name).every(every).border(border).build();
        } catch (IllegalArgumentException refused) {
            // Should be unreachable: the name rule asked Core the same question a moment ago. Kept
            // because "unreachable" and "unreachable until somebody changes the rule" are the same
            // sentence, and the thing on the other side of it deletes folders.
            messages.send(maker, "farmworlds.name.dangerous", "name", String.valueOf(name));
            return Optional.empty();
        }

        messages.send(maker, "farmworlds.making", "name", set.name());
        int made = catalogue.define(set);
        registerNodeFor(set.name());
        written();
        if (made == 0) {
            // Defined but with nothing behind it. Said out loud rather than reported as a success: a
            // farm world on the list that cannot be entered is worse than one that was refused, because
            // nothing points at what went wrong.
            messages.send(maker, "farmworlds.could-not-make", "name", set.name());
            return Optional.empty();
        }
        messages.send(maker, "farmworlds.created", "name", set.name(), "count", made);
        return catalogue.byName(set.name());
    }

    /**
     * Takes a farm world off the list, leaving its worlds exactly where they are.
     *
     * <p>Deliberately not a delete. This is how an owner stops a farm world being thrown away on its
     * schedule — usually because they have decided to keep it — and the wording says so, because
     * somebody who expected the worlds to go will otherwise leave three folders behind and never know.
     */
    public boolean forget(CommandSender remover, String name) {
        if (!mayManage(remover)) {
            return false;
        }
        if (!catalogue.undefine(name)) {
            messages.send(remover, "farmworlds.unknown", "name", String.valueOf(name));
            return false;
        }
        written();
        messages.send(remover, "farmworlds.forgotten", "name", name);
        return true;
    }

    // ------------------------------------------------------------------------ changing one

    /** How often it is thrown away; null stops it happening on a schedule at all. */
    public boolean setSchedule(CommandSender changer, String name, Duration every) {
        return change(changer, name,
                set -> WorldSet.builder(set.name())
                        .withNether(set.hasNether())
                        .withEnd(set.hasEnd())
                        .every(every)
                        .seed(set.fixedSeed())
                        .border(set.borderRadius())
                        .build(),
                view -> {
                    if (every == null) {
                        messages.send(changer, "farmworlds.schedule-off", "name", view.name());
                    } else {
                        messages.send(changer, "farmworlds.schedule-set", "name", view.name(),
                                "time", Times.describe(every));
                    }
                });
    }

    /**
     * How far from the middle it goes; null takes the border away.
     *
     * <p>Applied to the world the next time it is made rather than now, and the wording says so. Moving a
     * live border inwards would leave whatever people built outside it on the wrong side of a wall, which
     * is a worse surprise than a border that arrives with the new map.
     */
    public boolean setBorder(CommandSender changer, String name, Integer radius) {
        return change(changer, name,
                set -> WorldSet.builder(set.name())
                        .withNether(set.hasNether())
                        .withEnd(set.hasEnd())
                        .every(set.regenerateAfter())
                        .seed(set.fixedSeed())
                        .border(radius)
                        .build(),
                view -> {
                    if (radius == null) {
                        messages.send(changer, "farmworlds.border-off", "name", view.name());
                    } else {
                        messages.send(changer, "farmworlds.border-set", "name", view.name(),
                                "blocks", radius);
                    }
                });
    }

    /**
     * Whether it has its own nether and end.
     *
     * <p>Switching one <em>off</em> does not delete the world it was: Core stops managing it, and the
     * folder stays where it is. Said out loud, because the alternative reading — that turning the nether
     * off throws the farm nether away — is the one somebody will assume.
     */
    public boolean setDimensions(CommandSender changer, String name, boolean nether, boolean end) {
        return change(changer, name,
                set -> WorldSet.builder(set.name())
                        .withNether(nether)
                        .withEnd(end)
                        .every(set.regenerateAfter())
                        .seed(set.fixedSeed())
                        .border(set.borderRadius())
                        .build(),
                view -> messages.send(changer, "farmworlds.dimensions-set",
                        "name", view.name(), "count", view.worlds().size()));
    }

    /**
     * One change to a farm world's definition, made the only way a record can be changed: rebuilt.
     *
     * <p>Every caller above rebuilds from every component rather than only the one it is changing, which
     * looks like repetition and is the point — a builder that started from the defaults would silently
     * reset the schedule when somebody moved the border, and the schedule is what decides when three
     * worlds are deleted.
     */
    private boolean change(CommandSender changer, String name,
                           java.util.function.UnaryOperator<WorldSet> rebuild,
                           java.util.function.Consumer<FarmWorldView> say) {
        if (!mayManage(changer)) {
            return false;
        }
        WorldSet existing = catalogue.setOf(name).orElse(null);
        if (existing == null) {
            messages.send(changer, "farmworlds.unknown", "name", String.valueOf(name));
            return false;
        }
        WorldSet changed;
        try {
            changed = rebuild.apply(existing);
        } catch (IllegalArgumentException refused) {
            messages.send(changer, "farmworlds.change-refused", "reason", String.valueOf(refused.getMessage()));
            return false;
        }
        // define(), not ensure-and-define: the worlds already exist and this is a change to what is
        // written down about them. Core's define replaces the entry under the same name.
        catalogue.define(changed);
        written();
        catalogue.byName(changed.name()).ifPresent(say);
        return true;
    }

    // ------------------------------------------------------------------------ throwing one away

    /**
     * Throws a farm world away and makes it again, now.
     *
     * <p><b>Nothing may call this without having asked.</b> Both entrances do: the menu through
     * {@code ConfirmScreen}, the command through the word {@code confirm}. It is also the one thing in the
     * module that is written to the log at a level an owner will see afterwards, because "who regenerated
     * the farm world an hour early" is a question that gets asked.
     *
     * <p>On the caller's thread deliberately, and that thread has to be the main one: creating, unloading
     * and deleting a world are main-thread operations in Paper and are not safe anywhere else. A command
     * and a menu click are both already there.
     */
    public boolean regenerate(CommandSender asker, String name) {
        if (!mayManage(asker)) {
            return false;
        }
        WorldSet set = catalogue.setOf(name).orElse(null);
        if (set == null) {
            messages.send(asker, "farmworlds.unknown", "name", String.valueOf(name));
            return false;
        }
        log.warn("{} is making the farm world '{}' again by hand.", nameOf(asker), set.name());
        messages.send(asker, "farmworlds.regenerating", "name", set.name());
        boolean ok = catalogue.regenerate(set);
        if (ok) {
            messages.send(asker, "farmworlds.regenerated", "name", set.name());
        } else {
            // Core has already said what went wrong, at the level that names the file. This is the half
            // the person who pressed the button can see, and it deliberately does not guess: a
            // half-regenerated farm world is recoverable and a wrong guess about which half is not.
            messages.send(asker, "farmworlds.regeneration-failed", "name", set.name());
        }
        return ok;
    }

    // ------------------------------------------------------------------------ the small print

    private boolean mayManage(CommandSender who) {
        if (who != null && access.mayManage(who::hasPermission)) {
            return true;
        }
        if (who != null) {
            messages.send(who, "farmworlds.not-yours");
        }
        return false;
    }

    /**
     * Writes the definitions out, off the server's threads.
     *
     * <p>Off-thread because that is what {@code FarmWorldState.flush} asks for, and it is a file write
     * either way. The message telling somebody what happened has already been sent by the time this
     * runs, which is the right order: a confirmation that waited for a disk write would be a command that
     * pauses.
     */
    private void written() {
        Scheduling.async(plugin, catalogue::flush);
    }

    /**
     * Declares the new farm world's own permission node.
     *
     * <p>So that it shows up in a permissions plugin's list the moment the farm world exists, rather than
     * only after the next restart. Closing one of several farm worlds is the first thing anybody wants to
     * do with more than one, and a node nothing has declared is one an admin has to know from the
     * documentation.
     */
    private void registerNodeFor(String name) {
        int added = PermissionNodes.register(server, List.of(name));
        if (added > 0) {
            log.info("{} is now a permission anybody can be refused.",
                    PermissionNodes.forWorld(name));
        }
    }

    private static String nameOf(CommandSender who) {
        return who == null ? "somebody" : who.getName();
    }

    @Override
    public String describe() {
        return "making, changing and throwing away farm worlds";
    }
}
