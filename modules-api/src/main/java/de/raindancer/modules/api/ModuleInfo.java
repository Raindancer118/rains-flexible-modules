package de.raindancer.modules.api;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * What a module calls itself, and what it needs before it can run.
 *
 * <p>Built up rather than constructed, so a module that has nothing to declare declares nothing:
 *
 * <pre>{@code
 * ModuleInfo.of("moderation", "Moderation", "1.0.0")
 *         .describedAs("Punishments, vanish, invsee and the screens for them")
 *         .by("Raindancer118")
 *         .wanting("farm-world");
 * }</pre>
 *
 * <h2>requires versus wants</h2>
 * {@code requires} is a refusal to start without it: the module is skipped, and so is anything that
 * required <em>it</em>. {@code wants} is only an ordering wish — the other module goes first if it is
 * there, and nothing happens if it is not. Almost everything should be a want. A require is right when
 * the module would throw on its first line without the other one, and wrong when it would merely offer
 * less.
 *
 * <p>Declaring the same module in both is a contradiction rather than emphasis, so it is refused.
 */
public record ModuleInfo(String id, String name, String version, String description, String author,
                         Set<String> requires, Set<String> wants) {

    public ModuleInfo {
        id = Ids.checkModuleId(id);
        name = Ids.required(name, "name");
        version = Ids.required(version, "version");
        description = description == null ? "" : description.strip();
        author = author == null ? "" : author.strip();
        requires = sorted(requires, id);
        wants = sorted(wants, id);

        Set<String> both = new TreeSet<>(requires);
        both.retainAll(wants);
        if (!both.isEmpty()) {
            throw new IllegalArgumentException(
                    "module '" + id + "' both requires and merely wants " + both
                            + " — it has to be one or the other");
        }
    }

    /** A module with nothing else to say about itself. */
    public static ModuleInfo of(String id, String name, String version) {
        return new ModuleInfo(id, name, version, "", "", Set.of(), Set.of());
    }

    public ModuleInfo describedAs(String description) {
        return new ModuleInfo(id, name, version, description, author, requires, wants);
    }

    public ModuleInfo by(String author) {
        return new ModuleInfo(id, name, version, description, author, requires, wants);
    }

    /** Modules this one cannot run without. Accumulates, so it may be called more than once. */
    public ModuleInfo requiring(String... ids) {
        return new ModuleInfo(id, name, version, description, author, plus(requires, ids), wants);
    }

    /** Modules this one would like to go first if they are there. Accumulates. */
    public ModuleInfo wanting(String... ids) {
        return new ModuleInfo(id, name, version, description, author, requires, plus(wants, ids));
    }

    /** Both kinds together — everything this module has named, for whatever reason. */
    public Set<String> dependencies() {
        Set<String> all = new TreeSet<>(requires);
        all.addAll(wants);
        return Collections.unmodifiableSet(all);
    }

    /** Whether this module refuses to run without that one. */
    public boolean needs(String moduleId) {
        return requires.contains(moduleId);
    }

    @Override
    public String toString() {
        return name + " " + version + " (" + id + ")";
    }

    private static Set<String> plus(Set<String> existing, String... more) {
        Set<String> all = new LinkedHashSet<>(existing);
        if (more != null) {
            Collections.addAll(all, more);
        }
        return all;
    }

    /**
     * Sorted and unmodifiable, which is what makes two identically-declared modules equal and stops a
     * module handing out a set somebody else can add to.
     */
    private static Set<String> sorted(Set<String> ids, String owner) {
        if (ids == null || ids.isEmpty()) {
            return Set.of();
        }
        Set<String> checked = new TreeSet<>();
        for (String id : ids) {
            Ids.checkModuleId(id);
            if (id.equals(owner)) {
                throw new IllegalArgumentException(
                        "module '" + owner + "' cannot depend on itself");
            }
            checked.add(id);
        }
        return Collections.unmodifiableSet(checked);
    }
}
