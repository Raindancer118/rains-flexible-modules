package de.raindancer.modules.veintoggle.service;

import de.raindancer.core.ui.messages.Messages;
import org.bukkit.entity.Player;

/** {@link VeinUndoService.Notices} in this module's wording. */
public final class UndoNotices implements VeinUndoService.Notices {

    private final Messages messages;

    public UndoNotices(Messages messages) {
        this.messages = messages;
    }

    @Override
    public void outcome(Player undoer, VeinUndoService.Outcome outcome) {
        int total = outcome.restored() + outcome.inTheWay() + outcome.unpaid();
        if (total == 0) {
            messages.send(undoer, "veintoggle.undo-gone");
            return;
        }
        if (outcome.restored() == total) {
            messages.send(undoer, "veintoggle.undo-done");
            return;
        }
        if (outcome.restored() == 0) {
            messages.send(undoer, "veintoggle.undo-none");
        } else {
            messages.send(undoer, "veintoggle.undo-partly", "blocks", String.valueOf(outcome.restored()),
                    "total", String.valueOf(total));
        }
        if (outcome.inTheWay() > 0) {
            messages.send(undoer, "veintoggle.undo-in-the-way", "count", String.valueOf(outcome.inTheWay()));
        }
        if (outcome.unpaid() > 0) {
            messages.send(undoer, "veintoggle.undo-unpaid", "count", String.valueOf(outcome.unpaid()));
        }
    }

    @Override
    public void tooFar(Player undoer) {
        messages.send(undoer, "veintoggle.undo-too-far");
    }

    @Override
    public void bill(Player undoer, String amount, int items, long seconds) {
        messages.send(undoer, "veintoggle.undo-bill", "amount", amount, "items", String.valueOf(items),
                "seconds", String.valueOf(seconds));
    }

    @Override
    public void cannotPayAll(Player undoer, String paid, String wanted) {
        messages.send(undoer, "veintoggle.undo-paid-part", "paid", paid, "amount", wanted);
    }

    @Override
    public void noBill(Player undoer) {
        messages.send(undoer, "veintoggle.undo-no-bill");
    }

    @Override
    public void busy(Player undoer) {
        messages.send(undoer, "veintoggle.undo-busy");
    }

    @Override
    public void tookBack(Player collector, String undoer, int items) {
        messages.send(collector, "veintoggle.undo-took-back", "player", undoer, "items", String.valueOf(items));
    }

    @Override
    public void charged(Player collector, String undoer, String amount, int items) {
        messages.send(collector, "veintoggle.undo-charged", "player", undoer, "amount", amount,
                "items", String.valueOf(items));
    }
}
