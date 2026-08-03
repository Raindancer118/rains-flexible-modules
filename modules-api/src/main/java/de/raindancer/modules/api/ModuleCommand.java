package de.raindancer.modules.api;

import io.papermc.paper.command.brigadier.BasicCommand;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * A command a module brings, declared rather than registered.
 *
 * <p>The split is forced by Paper: the {@code COMMANDS} lifecycle event fires during the bootstrap
 * phase, long before any module is enabled, and a handler registered in {@code onEnable} never runs at
 * all — no warning, no exception, the command simply does not exist. So a module <em>says</em> what its
 * commands are and the host registers them at the only moment that works.
 *
 * <p>Which means the handler is built before the module is: it must not capture anything that only
 * exists once the module is running, and should look its module up when it is actually run. See
 * {@link ModuleCommands#guarded}, which does exactly that and answers politely when the module turned
 * out not to be there.
 */
public record ModuleCommand(String name, String description, List<String> aliases,
                            BasicCommand handler) {

    public ModuleCommand {
        name = Ids.checkCommandName(name);
        description = Ids.required(description, "command description");
        Objects.requireNonNull(handler, "a command needs something to run");
        aliases = checkedAliases(name, aliases);
    }

    public static ModuleCommand of(String name, String description, BasicCommand handler) {
        return new ModuleCommand(name, description, List.of(), handler);
    }

    /** Other names for the same command. Accumulates, so it may be called more than once. */
    public ModuleCommand aliased(String... more) {
        List<String> all = new ArrayList<>(aliases);
        if (more != null) {
            all.addAll(List.of(more));
        }
        return new ModuleCommand(name, description, all, handler);
    }

    /** The name first, then the aliases — every word this command answers to. */
    public List<String> names() {
        List<String> all = new ArrayList<>(aliases.size() + 1);
        all.add(name);
        all.addAll(aliases);
        return List.copyOf(all);
    }

    private static List<String> checkedAliases(String name, List<String> aliases) {
        if (aliases == null || aliases.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String alias : aliases) {
            Ids.checkCommandName(alias);
            if (alias.equals(name)) {
                throw new IllegalArgumentException(
                        "'" + alias + "' is the command's own name, not an alias for it");
            }
            if (!unique.add(alias)) {
                throw new IllegalArgumentException(
                        "'" + alias + "' is listed twice as an alias of /" + name);
            }
        }
        return List.copyOf(unique);
    }
}
