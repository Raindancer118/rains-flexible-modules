package de.raindancer.modules.performance.model;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Which plugin a class came from, read from the plugins' jars. A class shaded into more than one jar
 * — the module wrapper every Rain standalone carries — belongs to nobody: charging it to whichever
 * jar was read last would be a guess dressed up as an answer.
 */
public final class ClassIndex {

    private final Map<String, String> owners = new HashMap<>();
    private final Set<String> shared = new HashSet<>();

    /** @param entries the jar's entry names, {@code de/x/Y.class} */
    public synchronized void add(String plugin, Collection<String> entries) {
        for (String entry : entries) {
            if (!entry.endsWith(".class")) {
                continue;
            }
            String name = entry.substring(0, entry.length() - ".class".length()).replace('/', '.');
            if (shared.contains(name)) {
                continue;
            }
            String before = owners.putIfAbsent(name, plugin);
            if (before != null && !before.equals(plugin)) {
                owners.remove(name);
                shared.add(name);
            }
        }
    }

    public synchronized Optional<String> pluginOf(String className) {
        String found = owners.get(className);
        if (found == null) {
            // A lambda or an inner class is the outer class's.
            int cut = className.indexOf('$');
            if (cut > 0) {
                found = owners.get(className.substring(0, cut));
            }
        }
        return Optional.ofNullable(found);
    }

    public synchronized int size() {
        return owners.size();
    }
}
