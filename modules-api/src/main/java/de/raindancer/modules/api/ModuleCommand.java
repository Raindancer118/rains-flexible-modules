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
                            BasicCommand handler, List<String> options, String permission,
                            boolean audited) {

    public ModuleCommand {
        name = Ids.checkCommandName(name);
        description = Ids.required(description, "command description");
        Objects.requireNonNull(handler, "a command needs something to run");
        aliases = checkedAliases(name, aliases);
        options = options == null ? List.of() : List.copyOf(options);
        permission = permission == null || permission.isBlank() ? null : permission.trim();
    }

    public static ModuleCommand of(String name, String description, BasicCommand handler) {
        return new ModuleCommand(name, description, List.of(), handler, List.of(), null, false);
    }

    /** Other names for the same command. Accumulates, so it may be called more than once. */
    public ModuleCommand aliased(String... more) {
        List<String> all = new ArrayList<>(aliases);
        if (more != null) {
            all.addAll(List.of(more));
        }
        return new ModuleCommand(name, description, all, handler, options, permission, audited);
    }

    /**
     * The usage lines this command takes — {@code "pack <creature> [how many]"}.
     *
     * <p>Declared here rather than written into a book somewhere, because the book is generated from
     * exactly this. A command whose options live in two places has options that disagree by March,
     * and the copy a player reads is always the stale one.
     *
     * <p>Accumulates, so it may be called more than once.
     */
    public ModuleCommand taking(String... more) {
        List<String> all = new ArrayList<>(options);
        if (more != null) {
            all.addAll(List.of(more));
        }
        return new ModuleCommand(name, description, aliases, handler, all, permission, audited);
    }

    /**
     * The permission a reader needs before this appears in the directory.
     *
     * <p>Absent, not greyed: a book listing every staff command to every player teaches the whole
     * moderation vocabulary to somebody who can run none of it.
     */
    public ModuleCommand needing(String node) {
        return new ModuleCommand(name, description, aliases, handler, options, node, audited);
    }

    /**
     * Marks this command as one worth a line in Core's audit journal every time it runs.
     *
     * <p>Opt-in, not the default: a journal that logged every {@code /home} would drown the handful
     * of lines somebody actually goes looking for — a moderator's tools, not a player's everyday ones.
     * The recording itself is the host's job ({@link ModuleCommands#guarded}), which is the one place
     * every command already passes through; this is only the flag that tells it to, read back through
     * {@link #audited()}.
     */
    public ModuleCommand auditUsage() {
        return new ModuleCommand(name, description, aliases, handler, options, permission, true);
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
