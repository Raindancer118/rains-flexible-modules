package de.raindancer.modules.api;

import java.util.ArrayList;
import java.util.List;

/**
 * A module that does nothing but say what happened to it.
 *
 * <p>Every lifecycle test needs to know the order things were enabled in, and asking real modules
 * that would mean starting a server. The shared {@code journal} is what makes order assertable: one
 * list, written by every module in the test, so "b before a" is a statement about the list rather
 * than about two separate flags.
 */
final class FakeModule implements FlexModule {

    private final ModuleInfo info;
    private final List<String> journal;
    private final List<ModuleCommand> commands = new ArrayList<>();

    private RuntimeException failEnable;
    private RuntimeException failDisable;

    ModuleContext seen;
    int enableCalls;
    int disableCalls;

    FakeModule(ModuleInfo info, List<String> journal) {
        this.info = info;
        this.journal = journal;
    }

    static FakeModule named(String id, List<String> journal) {
        return new FakeModule(ModuleInfo.of(id, id, "1.0.0"), journal);
    }

    static FakeModule requiring(String id, List<String> journal, String... required) {
        return new FakeModule(ModuleInfo.of(id, id, "1.0.0").requiring(required), journal);
    }

    static FakeModule wanting(String id, List<String> journal, String... wanted) {
        return new FakeModule(ModuleInfo.of(id, id, "1.0.0").wanting(wanted), journal);
    }

    FakeModule failingToEnable(RuntimeException cause) {
        this.failEnable = cause;
        return this;
    }

    FakeModule failingToDisable(RuntimeException cause) {
        this.failDisable = cause;
        return this;
    }

    FakeModule offering(ModuleCommand command) {
        commands.add(command);
        return this;
    }

    @Override
    public ModuleInfo info() {
        return info;
    }

    @Override
    public List<ModuleCommand> commands() {
        return List.copyOf(commands);
    }

    @Override
    public void enable(ModuleContext context) {
        enableCalls++;
        seen = context;
        journal.add("enable:" + info.id());
        if (failEnable != null) {
            throw failEnable;
        }
    }

    @Override
    public void disable() {
        disableCalls++;
        journal.add("disable:" + info.id());
        if (failDisable != null) {
            throw failDisable;
        }
    }
}
