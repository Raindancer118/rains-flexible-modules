package de.raindancer.modules.api;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Works out what order modules start in, and who cannot start at all.
 *
 * <h2>Determinism first</h2>
 * The order depends only on the module ids and what they declare — never on classpath order, jar
 * timestamps or which thread got there first. Two servers with the same modules start them in the same
 * order, and so does the same server tomorrow. Anything else produces the worst class of bug there is:
 * one that appears on some boots and cannot be reproduced on the machine you are debugging on.
 *
 * <p>Concretely: candidates that are ready at the same time go in alphabetical order, and soft edges are
 * considered in a sorted order too.
 *
 * <h2>What a missing dependency costs</h2>
 * A module that requires something absent is skipped, and so is everything that required <em>it</em>,
 * transitively. Starting a module whose foundation is not there produces a {@code NullPointerException}
 * deep inside it, and nothing in that stack trace names the module that was actually missing.
 *
 * <h2>Wants give way</h2>
 * A {@code wants} edge is dropped whenever honouring it would make a cycle. Dropping it costs an
 * ordering preference; keeping it would cost both modules entirely. Only {@code requires} cycles are
 * fatal, and they are a declaration bug rather than a configuration one.
 */
public final class ModuleOrder {

    private ModuleOrder() {
    }

    public static ModulePlan plan(Collection<? extends FlexModule> modules) {
        Map<String, FlexModule> byId = new TreeMap<>();
        List<String> problems = new ArrayList<>();
        Map<String, String> skipped = new LinkedHashMap<>();

        for (FlexModule module : modules) {
            String id = module.info().id();
            FlexModule existing = byId.putIfAbsent(id, module);
            if (existing != null && existing != module) {
                problems.add("the module id '" + id + "' is already taken by " + existing.info()
                        + " — ignoring " + module.info());
            }
        }

        cascadeMissing(byId, skipped);
        markRequiredCycles(byId, skipped);
        // A cycle removes modules, which can leave a fourth module requiring one of them.
        cascadeMissing(byId, skipped);

        List<FlexModule> order = sort(byId, skipped);
        return new ModulePlan(order, skipped, problems);
    }

    /**
     * Skips anything whose required dependency is missing or already skipped, repeatedly, until nothing
     * changes — which is what makes it transitive rather than one level deep.
     */
    private static void cascadeMissing(Map<String, FlexModule> byId, Map<String, String> skipped) {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Map.Entry<String, FlexModule> entry : byId.entrySet()) {
                String id = entry.getKey();
                if (skipped.containsKey(id)) {
                    continue;
                }
                for (String needed : entry.getValue().info().requires()) {
                    if (!byId.containsKey(needed)) {
                        skipped.put(id, "requires '" + needed + "', which is not installed");
                        changed = true;
                        break;
                    }
                    if (skipped.containsKey(needed)) {
                        skipped.put(id, "requires '" + needed + "', which was skipped");
                        changed = true;
                        break;
                    }
                }
            }
        }
    }

    /**
     * Finds modules caught in a {@code requires} cycle and skips all of them.
     *
     * <p>Whatever is left over after a topological sort of the required edges alone is, by definition,
     * in a cycle or downstream of one. Naming the whole leftover set in the reason is more useful than
     * naming one arbitrary member of it, because a cycle has no natural first element to blame.
     */
    private static void markRequiredCycles(Map<String, FlexModule> byId, Map<String, String> skipped) {
        Set<String> alive = alive(byId, skipped);
        Map<String, Set<String>> after = new TreeMap<>();
        Map<String, Integer> waitingFor = new TreeMap<>();
        for (String id : alive) {
            after.put(id, new TreeSet<>());
            waitingFor.put(id, 0);
        }
        for (String id : alive) {
            for (String needed : byId.get(id).info().requires()) {
                if (alive.contains(needed) && after.get(needed).add(id)) {
                    waitingFor.merge(id, 1, Integer::sum);
                }
            }
        }

        Set<String> sorted = kahn(after, waitingFor);
        Set<String> stuck = new TreeSet<>(alive);
        stuck.removeAll(sorted);
        for (String id : stuck) {
            skipped.put(id, "is part of a dependency cycle involving " + stuck);
        }
    }

    /** The final order: required edges, plus every soft edge that does not close a loop. */
    private static List<FlexModule> sort(Map<String, FlexModule> byId, Map<String, String> skipped) {
        Set<String> alive = alive(byId, skipped);
        Map<String, Set<String>> after = new TreeMap<>();
        Map<String, Integer> waitingFor = new TreeMap<>();
        for (String id : alive) {
            after.put(id, new TreeSet<>());
            waitingFor.put(id, 0);
        }

        for (String id : alive) {
            for (String needed : byId.get(id).info().requires()) {
                if (alive.contains(needed)) {
                    link(after, waitingFor, needed, id);
                }
            }
        }
        // Sorted, so which soft edge gives way in a soft-only cycle is decided the same way every time.
        for (String id : alive) {
            for (String wanted : byId.get(id).info().wants()) {
                if (!alive.contains(wanted) || reaches(after, id, wanted)) {
                    continue;
                }
                link(after, waitingFor, wanted, id);
            }
        }

        List<FlexModule> order = new ArrayList<>(alive.size());
        for (String id : kahn(after, waitingFor)) {
            order.add(byId.get(id));
        }
        return order;
    }

    private static void link(Map<String, Set<String>> after, Map<String, Integer> waitingFor,
                             String first, String then) {
        if (after.get(first).add(then)) {
            waitingFor.merge(then, 1, Integer::sum);
        }
    }

    /** Whether {@code to} already comes after {@code from}, which is what would make a new edge a loop. */
    private static boolean reaches(Map<String, Set<String>> after, String from, String to) {
        Set<String> seen = new HashSet<>();
        Deque<String> pending = new ArrayDeque<>();
        pending.push(from);
        while (!pending.isEmpty()) {
            String at = pending.pop();
            if (!seen.add(at)) {
                continue;
            }
            if (at.equals(to)) {
                return true;
            }
            for (String next : after.getOrDefault(at, Set.of())) {
                pending.push(next);
            }
        }
        return false;
    }

    /** @return the ids in a stable topological order; leftovers are simply absent */
    private static Set<String> kahn(Map<String, Set<String>> after, Map<String, Integer> waitingFor) {
        // A sorted ready-set rather than a queue: this is the single line that makes the whole
        // ordering deterministic instead of merely correct.
        TreeSet<String> ready = new TreeSet<>();
        Map<String, Integer> remaining = new HashMap<>(waitingFor);
        remaining.forEach((id, count) -> {
            if (count == 0) {
                ready.add(id);
            }
        });

        Set<String> order = new LinkedHashSet<>();
        while (!ready.isEmpty()) {
            String next = ready.pollFirst();
            order.add(next);
            for (String dependent : after.getOrDefault(next, Set.of())) {
                if (remaining.merge(dependent, -1, Integer::sum) == 0) {
                    ready.add(dependent);
                }
            }
        }
        return order;
    }

    private static Set<String> alive(Map<String, FlexModule> byId, Map<String, String> skipped) {
        Set<String> alive = new TreeSet<>(byId.keySet());
        alive.removeAll(skipped.keySet());
        return alive;
    }
}
