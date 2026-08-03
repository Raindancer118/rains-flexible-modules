package de.raindancer.modules.api;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Wraps a module's command so it says something sensible when the module is not running.
 *
 * <h2>Why this is not an edge case</h2>
 * Commands are registered during Paper's bootstrap phase. Whether a module actually enables is decided
 * minutes later, in {@code onEnable}, and it may not: a required dependency may be absent, or its store
 * may fail to open. The command is registered either way — there is no way to unregister it, and no
 * chance to decide not to register it, because at bootstrap nothing is known yet.
 *
 * <p>So the state to design for is <em>command present, module absent</em>. Unguarded, that is a
 * {@link NullPointerException} in the console and a player looking at nothing at all. Guarded, it is one
 * red line naming the module and saying why it is not there — which is also exactly what the operator
 * needs in order to fix it.
 */
public final class ModuleCommands {

    private ModuleCommands() {
    }

    /**
     * The same command, refusing politely whenever its module is not running.
     *
     * <p>Suggestions are refused too: tab-completing a command that cannot run reads as a working
     * command, and the completion would run module code that is not initialised.
     */
    public static ModuleCommand guarded(ModuleRegistry registry, String moduleId,
                                        ModuleCommand command) {
        BasicCommand real = command.handler();
        BasicCommand guarded = new BasicCommand() {
            @Override
            public void execute(CommandSourceStack source, String[] args) {
                Optional<String> refusal = refusalFor(registry, moduleId);
                if (refusal.isPresent()) {
                    source.getSender().sendMessage(
                            Component.text(refusal.get(), NamedTextColor.RED));
                    return;
                }
                real.execute(source, args);
            }

            @Override
            public Collection<String> suggest(CommandSourceStack source, String[] args) {
                return refusalFor(registry, moduleId).isPresent()
                        ? List.of()
                        : real.suggest(source, args);
            }

            /**
             * Brigadier calls this while <em>resolving</em> the command, before {@code execute} is
             * reached. A module that failed halfway through starting has null fields, so its own
             * {@code canUse} is exactly as likely to throw as its {@code execute} — and a throw here
             * lands inside Paper's parser, where the player sees nothing at all.
             *
             * <p>Answers true when the module is not running, so the command still resolves and the
             * player is told what is actually wrong. False would give them "Unknown command", which is
             * a lie about which of the two problems they have.
             *
             * <p>When the module <em>is</em> running its answer is used, and an exception from it counts
             * as no. Failing closed matters here: swallowing it and answering yes would open a
             * moderation command to everybody on the server.
             */
            @Override
            public boolean canUse(CommandSender sender) {
                if (refusalFor(registry, moduleId).isPresent()) {
                    return true;
                }
                try {
                    return real.canUse(sender);
                } catch (Throwable broken) {
                    return false;
                }
            }

            @Override
            public String permission() {
                if (refusalFor(registry, moduleId).isPresent()) {
                    return null;
                }
                try {
                    return real.permission();
                } catch (Throwable broken) {
                    // canUse has already answered no, so nothing is being opened up by this.
                    return null;
                }
            }
        };
        return new ModuleCommand(command.name(), command.description(), command.aliases(), guarded);
    }

    /**
     * Why this module cannot answer, if it cannot.
     *
     * @return empty when the module is running — the only state in which a command should do its work
     */
    public static Optional<String> refusalFor(ModuleRegistry registry, String moduleId) {
        ModuleState state = registry.stateOf(moduleId);
        if (state == ModuleState.ENABLED) {
            return Optional.empty();
        }
        String because = registry.reasonFor(moduleId).map(reason -> ": " + reason).orElse("");
        return Optional.of(switch (state) {
            case ABSENT -> "The '" + moduleId + "' module is not installed on this server.";
            case NEW -> "The '" + moduleId + "' module has not started yet — try again in a moment.";
            case SKIPPED -> "The '" + moduleId + "' module was not started" + because + ".";
            case FAILED -> "The '" + moduleId + "' module failed to start" + because + ".";
            case DISABLED -> "The '" + moduleId + "' module is not running any more.";
            case ENABLED -> throw new IllegalStateException("handled above");
        });
    }
}
