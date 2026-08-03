package de.raindancer.modules.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What order to start modules in, who is being left out, and what else went wrong.
 *
 * @param order    the modules that can run, in the order they must be started
 * @param skipped  module id to the reason it is not in {@code order} — a required dependency that is
 *                 missing, was itself skipped, or a cycle
 * @param problems things worth saying out loud that did not stop any single module from running, such as
 *                 two jars each bringing a module with the same id
 */
public record ModulePlan(List<FlexModule> order, Map<String, String> skipped,
                         List<String> problems) {

    public ModulePlan {
        order = List.copyOf(order);
        skipped = Collections.unmodifiableMap(new LinkedHashMap<>(skipped));
        problems = List.copyOf(problems);
    }

    public boolean isSkipped(String moduleId) {
        return skipped.containsKey(moduleId);
    }

    public Optional<String> reasonFor(String moduleId) {
        return Optional.ofNullable(skipped.get(moduleId));
    }
}
