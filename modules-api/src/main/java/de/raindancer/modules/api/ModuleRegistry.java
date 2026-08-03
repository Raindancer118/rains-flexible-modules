package de.raindancer.modules.api;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * The modules one host has, and what has happened to each of them.
 *
 * <h2>The rule this class exists to keep</h2>
 * <b>One module going wrong takes down that module and whatever required it — nothing else.</b> A host
 * with six modules and one that throws must come up with five working features and one honest error in
 * the console. Anything else and the shared-code idea is a liability: a bug in the least important module
 * becomes a plugin that will not start.
 *
 * <p>Which is why every call into a module is wrapped, including the innocuous-looking ones. Asking a
 * module for its commands runs its code, and code can throw.
 *
 * <h2>Failing means unwinding</h2>
 * A module that threw halfway through {@code enable} has usually registered a listener already. Leaving
 * it in place is worse than not having the module at all, because the events keep arriving at an object
 * that never finished initialising. So the session is unwound on every path out.
 *
 * <p>Not thread-safe: a plugin's lifecycle happens on the server thread and pretending otherwise would
 * invite modules to be enabled from somewhere else.
 */
public final class ModuleRegistry {

    private record Running(FlexModule module, ModuleSession session) {
    }

    private final List<FlexModule> declared = new ArrayList<>();
    private final Map<String, ModuleState> states = new LinkedHashMap<>();
    private final Map<String, String> reasons = new LinkedHashMap<>();

    /** Rebuilt whenever the plan is; kept apart so recomputing cannot duplicate them. */
    private final List<String> planProblems = new ArrayList<>();
    private final List<String> commandProblems = new ArrayList<>();
    /** Things that happened once and are never recomputed: failures to start, failures to stop. */
    private final List<String> runtimeProblems = new ArrayList<>();

    private final List<Running> running = new ArrayList<>();

    private ModulePlan plan;
    private List<ModuleCommand> commands;
    private boolean started;

    /**
     * Adds a module. Before {@link #enableAll} only — a module arriving after the others have started
     * would have missed the bootstrap phase, so its commands could never be registered.
     */
    public void add(FlexModule module) {
        Objects.requireNonNull(module, "a registry cannot hold a null module");
        if (started) {
            throw new IllegalStateException(
                    "modules are already running; " + module.info().id() + " is too late to be added");
        }
        declared.add(module);
        states.putIfAbsent(module.info().id(), ModuleState.NEW);
        forget();
    }

    public void addAll(Iterable<? extends FlexModule> modules) {
        for (FlexModule module : modules) {
            add(module);
        }
    }

    /** Something worth telling the operator that the registry did not work out for itself. */
    public void problem(String problem) {
        if (problem != null && !problem.isBlank()) {
            runtimeProblems.add(problem);
        }
    }

    public List<FlexModule> declared() {
        return List.copyOf(declared);
    }

    /** The order they will be started in, and who is being left out. */
    public ModulePlan plan() {
        if (plan == null) {
            plan = ModuleOrder.plan(declared);
            planProblems.clear();
            planProblems.addAll(plan.problems());
        }
        return plan;
    }

    /**
     * Every command the runnable modules bring, in plan order, with collisions dropped.
     *
     * <p>Available before anything is enabled, because Paper asks for commands during bootstrap. A module
     * that later fails to start therefore leaves a registered command behind — see
     * {@link ModuleCommands#guarded} for the only decent way to answer it.
     */
    public List<ModuleCommand> commands() {
        if (commands != null) {
            return commands;
        }
        List<ModuleCommand> collected = new ArrayList<>();
        Set<String> taken = new HashSet<>();
        commandProblems.clear();

        for (FlexModule module : plan().order()) {
            String id = module.info().id();
            List<ModuleCommand> offered;
            try {
                offered = module.commands();
            } catch (Throwable caught) {
                commandProblems.add("module '" + id + "' failed while declaring its commands: "
                        + describe(caught));
                continue;
            }
            if (offered == null) {
                continue;
            }
            for (ModuleCommand command : offered) {
                if (command == null) {
                    continue;
                }
                Optional<String> clash = command.names().stream().filter(taken::contains).findFirst();
                if (clash.isPresent()) {
                    commandProblems.add("module '" + id + "' wanted /" + command.name()
                            + ", but '" + clash.get() + "' is already taken — dropping it");
                    continue;
                }
                taken.addAll(command.names());
                // Guarded here rather than left to each module to remember. A module cannot check
                // whether it is running — by the time its handler is called it either is, or it is a
                // half-built object whose every field is null. So the registry, which does know, wraps
                // every command it hands out. A review found this missing and it was invisible to the
                // whole suite: every test asked what was collected, none asked what running one did.
                collected.add(ModuleCommands.guarded(this, id, command));
            }
        }
        commands = List.copyOf(collected);
        return commands;
    }

    /**
     * Starts everything that can be started.
     *
     * @param sessions builds a module its context — allowed to throw, which fails that module only
     */
    public void enableAll(Function<FlexModule, ModuleSession> sessions) {
        Objects.requireNonNull(sessions, "something has to build the contexts");
        if (started) {
            throw new IllegalStateException("these modules have already been started once");
        }
        started = true;

        ModulePlan current = plan();
        current.skipped().forEach((id, reason) -> {
            states.put(id, ModuleState.SKIPPED);
            reasons.put(id, reason);
        });

        for (FlexModule module : current.order()) {
            String id = module.info().id();
            Optional<String> blocker = module.info().requires().stream()
                    .filter(needed -> states.get(needed) != ModuleState.ENABLED)
                    .findFirst();
            if (blocker.isPresent()) {
                states.put(id, ModuleState.SKIPPED);
                reasons.put(id, "requires '" + blocker.get() + "', which did not start");
                continue;
            }

            ModuleSession session;
            try {
                session = sessions.apply(module);
            } catch (Throwable caught) {
                fail(id, caught, "could not be given a place to run");
                continue;
            }
            if (session == null) {
                states.put(id, ModuleState.FAILED);
                reasons.put(id, "the host built no session for it");
                runtimeProblems.add("module '" + id + "' was given no session by its host");
                continue;
            }

            try {
                module.enable(session.context());
                states.put(id, ModuleState.ENABLED);
                running.add(new Running(module, session));
            } catch (Throwable caught) {
                fail(id, caught, "failed to start");
                unwindQuietly(id, session);
            }
        }
    }

    /** Stops everything that started, newest first. Safe before {@code enableAll} and safe twice. */
    public void disableAll() {
        for (int at = running.size() - 1; at >= 0; at--) {
            Running one = running.get(at);
            String id = one.module().info().id();
            try {
                one.module().disable();
            } catch (Throwable caught) {
                runtimeProblems.add("module '" + id + "' failed to stop cleanly: " + describe(caught));
            }
            unwindQuietly(id, one.session());
            states.put(id, ModuleState.DISABLED);
        }
        running.clear();
    }

    public ModuleState stateOf(String moduleId) {
        return states.getOrDefault(moduleId, ModuleState.ABSENT);
    }

    public boolean isEnabled(String moduleId) {
        return stateOf(moduleId) == ModuleState.ENABLED;
    }

    /** Why a module is not running, when there is something to say. */
    public Optional<String> reasonFor(String moduleId) {
        return Optional.ofNullable(reasons.get(moduleId));
    }

    /** The module with this id, whether or not it is running. */
    public Optional<FlexModule> get(String moduleId) {
        return declared.stream().filter(module -> module.info().id().equals(moduleId)).findFirst();
    }

    /** Only the running one, for a module asking after another module. */
    public Optional<FlexModule> enabled(String moduleId) {
        return isEnabled(moduleId) ? get(moduleId) : Optional.empty();
    }

    /** What is running, in the order it was started. */
    public List<FlexModule> enabled() {
        return running.stream().map(Running::module).toList();
    }

    /** Everything worth saying out loud, in the order it was noticed. */
    public List<String> problems() {
        List<String> all = new ArrayList<>(planProblems);
        all.addAll(commandProblems);
        all.addAll(runtimeProblems);
        return List.copyOf(all);
    }

    private void fail(String id, Throwable caught, String what) {
        states.put(id, ModuleState.FAILED);
        reasons.put(id, describe(caught));
        runtimeProblems.add("module '" + id + "' " + what + ": " + describe(caught));
    }

    private void unwindQuietly(String id, ModuleSession session) {
        try {
            session.unwind();
        } catch (Throwable caught) {
            runtimeProblems.add("module '" + id + "' could not be unwound: " + describe(caught));
        }
    }

    /** A message even when there is no message — an empty reason is worse than a class name. */
    private static String describe(Throwable caught) {
        String kind = caught.getClass().getSimpleName();
        String message = caught.getMessage();
        return message == null || message.isBlank() ? kind : kind + ": " + message;
    }

    private void forget() {
        plan = null;
        commands = null;
    }
}
