package de.raindancer.modules.hungergames.service;

import de.raindancer.core.social.economy.Economies;
import de.raindancer.core.social.economy.Economy;
import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Fees;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.social.team.Team;
import de.raindancer.core.social.team.TeamColour;
import de.raindancer.core.social.team.TeamId;
import de.raindancer.modules.hungergames.HungerGamesSettings;
import de.raindancer.modules.hungergames.model.GamePhase;
import de.raindancer.modules.hungergames.model.Participant;
import de.raindancer.modules.hungergames.model.Winner;
import de.raindancer.modules.hungergames.rules.PrizeRules;
import de.raindancer.modules.hungergames.store.EntryGate;
import de.raindancer.modules.hungergames.store.EntryLedger;
import de.raindancer.modules.hungergames.store.GameEvents;
import de.raindancer.modules.hungergames.store.GameSession;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The entry fee and the prize pot of a round.
 *
 * <p>Registering as a tribute costs {@code economy.entry-fee}; the fees form the pot, which {@link EntryLedger}
 * keeps on disk. A tribute who leaves before the round starts, or a round that is cancelled or ends with
 * nobody winning, gets the fees back. When somebody wins, the pot minus the house cut is paid by place
 * ({@link PrizeRules}). With the fee at 0 none of this touches the economy, and no economy is needed.
 *
 * <p>Somebody added by name who has never joined has no account to charge: they pay when they first join, and
 * are taken off the list if they cannot. A payout the treasury refuses is logged and the winner told — the
 * house cut already stayed with the server, so what is refused is held by nobody and staff settle it by hand.
 */
public final class EntryFeeService implements IHungerGamesService, GameEvents, EntryGate {

    static final String ENTRY = "hungergames.entry";
    static final String PRIZE = "hungergames.prize";

    /** Tells a player something, if they are online. */
    @FunctionalInterface
    public interface Notifier {
        void tell(UUID player, String key, Object... pairs);
    }

    private final EntryLedger ledger;
    private final GameSession session;
    private final Notifier notifier;
    private final Consumer<String> log;
    private final PrizeRules rules = new PrizeRules();
    private volatile HungerGamesSettings settings;

    public EntryFeeService(EntryLedger ledger, GameSession session, Notifier notifier, Consumer<String> log,
                           HungerGamesSettings settings) {
        this.ledger = ledger;
        this.session = session;
        this.notifier = notifier;
        this.log = log;
        this.settings = settings;
    }

    @Override
    public void settings(HungerGamesSettings updated) {
        this.settings = updated;
    }

    // ---------------------------------------------------------------------------- registering

    @Override
    public Optional<String> admit(UUID uuid, String name) {
        Money written = Fees.amount(settings.entryFee());
        if (!written.isPositive() || isPlaceholder(uuid, name)) {
            return Optional.empty();
        }
        return charge(uuid, written);
    }

    @Override
    public void identityClaimed(UUID placeholder, UUID real) {
        Money written = Fees.amount(settings.entryFee());
        if (!written.isPositive()) {
            return;
        }
        charge(real, written).ifPresent(reason -> {
            notifier.tell(real, "hungergames.entry-refused-joined", "reason", reason);
            log.accept(real + " was taken off the tribute list on joining: " + reason);
            session.whitelistRemove(real);
        });
    }

    private static boolean isPlaceholder(UUID uuid, String name) {
        return name != null && AccountNames.derivedId(name).equals(uuid);
    }

    /** @return why the fee could not be taken, or empty when it was (or was already) */
    private Optional<String> charge(UUID uuid, Money written) {
        if (ledger.has(uuid)) {
            return Optional.empty();
        }
        EconomyResult result = Fees.charge(uuid, written, "Hunger Games entry fee", ENTRY);
        if (!result.succeeded()) {
            return Optional.of(switch (result.outcome()) {
                case NOT_ENOUGH -> "the entry fee of " + Fees.format(result.amount()) + " cannot be paid";
                case UNAVAILABLE -> "there is no economy to take the entry fee of "
                        + Fees.format(result.amount()) + " from";
                default -> "the entry fee of " + Fees.format(result.amount()) + " could not be taken ("
                        + result.outcome() + ")";
            });
        }
        if (result.amount().isPositive() && !ledger.paid(uuid, result.amount())) {
            Fees.refund(uuid, result.amount(), "Hunger Games entry fee returned", ENTRY);
            return Optional.of("the entry could not be saved, so the fee was returned");
        }
        return Optional.empty();
    }

    // ---------------------------------------------------------------------------- refunds

    private void refund(UUID uuid, String why) {
        ledger.take(uuid).ifPresent(taken -> {
            EconomyResult result = Fees.refund(uuid, taken, "Hunger Games entry fee returned: " + why, ENTRY);
            if (result.succeeded()) {
                notifier.tell(uuid, "hungergames.entry-refunded", "amount", Fees.format(taken));
            } else {
                ledger.putBack(uuid, taken);
                log.accept("Could not refund " + Fees.format(taken) + " to " + uuid + " (" + result.outcome()
                        + "); kept in the pot.");
            }
        });
    }

    private void refundAll(String why) {
        ledger.entries().keySet().forEach(uuid -> refund(uuid, why));
        ledger.clear();
    }

    @Override
    public void whitelistChanged(UUID player, boolean added) {
        if (!added && session.phase().isPreGame()) {
            refund(player, "left before the round started");
        }
    }

    @Override
    public void phaseChanged(GamePhase oldPhase, GamePhase newPhase) {
        if (newPhase != GamePhase.NOT_INITIALIZED || oldPhase == GamePhase.NOT_INITIALIZED) {
            return;
        }
        if (oldPhase != GamePhase.FINISHED) {
            refundAll("the round was cancelled");
            return;
        }
        chargeAgain();
    }

    /** The same tributes play on after a round: each pays again, and whoever cannot is taken off the list. */
    private void chargeAgain() {
        Money written = Fees.amount(settings.entryFee());
        if (!written.isPositive()) {
            return;
        }
        for (Participant each : List.copyOf(session.participants().all())) {
            if (isPlaceholder(each.uuid(), each.lastKnownName())) {
                continue;
            }
            charge(each.uuid(), written).ifPresent(reason -> {
                notifier.tell(each.uuid(), "hungergames.entry-refused-joined", "reason", reason);
                log.accept(each.lastKnownName() + " was taken off the tribute list for the next round: " + reason);
                session.whitelistRemove(each.uuid());
            });
        }
    }

    // ---------------------------------------------------------------------------- the pot

    @Override
    public void participantEliminated(UUID participant, UUID killer, int remainingAlive) {
        if (!ledger.isEmpty()) {
            ledger.fell(participant);
        }
    }

    @Override
    public void participantRevived(UUID participant) {
        if (!ledger.isEmpty()) {
            ledger.revived(participant);
        }
    }

    @Override
    public void winnerDeclared(Winner winner) {
        Money pot = ledger.pot();
        if (!pot.isPositive()) {
            ledger.clear();
            return;
        }
        if (winner instanceof Winner.None) {
            refundAll("nobody won");
            return;
        }
        HungerGamesSettings live = settings;
        List<List<UUID>> places = rules.places(session.participants().all(), ledger.fallen(), winner);
        Map<UUID, Money> prizes = rules.payouts(pot, live.houseCutPercent(), live.prizeSplit(), places);
        Optional<Economy> economy = Economies.current();
        prizes.forEach((uuid, amount) -> {
            EconomyResult result = economy.isEmpty()
                    ? EconomyResult.failed(EconomyResult.Outcome.UNAVAILABLE, amount, Money.ZERO)
                    : economy.get().deposit(uuid, amount, "Hunger Games prize", PRIZE);
            if (result.succeeded()) {
                notifier.tell(uuid, "hungergames.prize-won", "amount", Fees.format(amount));
            } else {
                log.accept("Prize of " + Fees.format(amount) + " for " + uuid + " was refused ("
                        + result.outcome() + ") and is not paid.");
                notifier.tell(uuid, "hungergames.prize-refused", "amount", Fees.format(amount));
            }
        });
        ledger.clear();
    }

    // ---------------------------------------------------------------------------- the rest of GameEvents

    @Override
    public void teamCreated(Team team) {
    }

    @Override
    public void teamDeleted(Team team) {
    }

    @Override
    public void teamColourChanged(Team team, TeamColour oldColour, TeamColour newColour) {
    }

    @Override
    public void teamMembershipChanged(UUID player, TeamId oldTeam, TeamId newTeam, MembershipCause cause) {
    }

    @Override
    public void kill(UUID killer, UUID victim, int killerTotalKills) {
    }

    @Override
    public String describe() {
        return "the entry fee, the prize pot and who is paid from it";
    }
}
