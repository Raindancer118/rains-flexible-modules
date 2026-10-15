package de.raindancer.modules.economy.service;

import de.raindancer.core.platform.network.NetworkCalls;
import de.raindancer.core.platform.network.NetworkRoles;
import de.raindancer.core.social.economy.Economy;
import de.raindancer.core.social.economy.LinkedEconomy;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.social.economy.NetworkEconomy;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * This server's view of the network's one bank, for when the economy module runs here without being it.
 *
 * <p>Who keeps the money is the server the {@value NetworkEconomy#ROLE} role has as its home — which stays put
 * while that server restarts — or, if the role has no home yet, whoever holds it. An owner moving the bank with
 * {@code /network bank here confirm} changes the answer, so {@link #refresh} is called now and then; everything
 * else reads what it found.
 */
public final class BankClient {

    /** What the bank said about a balance: whether the account exists at all, and what is in it. */
    public record Balance(boolean account, Money amount) {
    }

    /** What the bank's own {@code /pay} answered, as lines to show the payer — JSON components, one each. */
    public record Payment(List<String> lines, boolean confirm, boolean paid) {
    }

    private static final Duration WAIT = Duration.ofSeconds(6);

    private final NetworkCalls calls;
    private final NetworkRoles roles;
    private final String self;
    private final Function<String, String> serverName;
    private final Duration wait;
    private volatile Optional<String> keeper = Optional.empty();
    private volatile boolean reachable;

    public BankClient(NetworkCalls calls, NetworkRoles roles, String self, Function<String, String> serverName) {
        this(calls, roles, self, serverName, WAIT);
    }

    BankClient(NetworkCalls calls, NetworkRoles roles, String self, Function<String, String> serverName,
               Duration wait) {
        this.calls = calls;
        this.roles = roles;
        this.self = self;
        this.serverName = serverName;
        this.wait = wait;
    }

    /** Reads who keeps the money from the database; blocks, so not on the server thread. */
    public void refresh() {
        Optional<String> holder = roles.holder(NetworkEconomy.ROLE);
        keeper = roles.home(NetworkEconomy.ROLE).or(() -> holder);
        reachable = holder.filter(server -> !server.equals(self)).isPresent();
    }

    /** Another server keeps the money: this one asks it. */
    public boolean elsewhere() {
        return keeper.filter(server -> !server.equals(self)).isPresent();
    }

    /** This server is the one the network's money belongs on. */
    public boolean keepsIt() {
        return keeper.filter(self::equals).isPresent();
    }

    /** The bank is another server and is answering for the network right now. */
    public boolean reachable() {
        return reachable;
    }

    /**
     * The bank's server id while it is another server — who a question for the bank goes to. From what {@link #refresh}
     * last read, so it costs nothing on the server thread.
     */
    public Optional<String> bankServer() {
        return keeper.filter(server -> !server.equals(self));
    }

    /** What players know the bank's server as. */
    public String bankName() {
        return keeper.map(serverName).orElse("the bank server");
    }

    /**
     * What {@code economy} — the network's, on this server — has for a player. Blocks: not on the server thread.
     * Empty when the bank cannot be reached, so a balance is never shown as nothing for want of an answer.
     */
    public Optional<Balance> balance(Economy economy, UUID player) {
        if (roles.holder(NetworkEconomy.ROLE).filter(server -> !server.equals(self)).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Balance(economy.hasAccount(player), economy.balance(player)));
    }

    /**
     * A payment made by the bank under every rule a {@code /pay} there has; its lines come back to show. Empty when
     * no server holds the bank or it did not answer.
     */
    public CompletableFuture<Optional<Payment>> pay(UUID payer, String payerName, UUID target, Money amount,
                                                    boolean confirmed) {
        Optional<String> bank = roles.holder(NetworkEconomy.ROLE).filter(server -> !server.equals(self));
        if (bank.isEmpty()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return calls.ask(bank.get(), LinkedEconomy.PAY, Map.of("payer", payer.toString(), "payerName", payerName,
                        "target", target.toString(), "amount", String.valueOf(amount.minor()),
                        "confirmed", String.valueOf(confirmed)), wait)
                .thenApply(answer -> answer.filter(said -> said.containsKey("lines")).map(said -> new Payment(
                        Arrays.stream(said.get("lines").split("\n")).filter(line -> !line.isBlank()).toList(),
                        "true".equals(said.get("confirm")), "true".equals(said.get("paid")))));
    }

    /** The bank's richest list, with {@code asker}'s own place in it. Empty when the bank cannot be reached. */
    public CompletableFuture<Optional<BankTop.Top>> top(UUID asker) {
        Optional<String> bank = roles.holder(NetworkEconomy.ROLE).filter(server -> !server.equals(self));
        if (bank.isEmpty()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return calls.ask(bank.get(), BankTop.TOPIC, Map.of("player", asker.toString()), wait)
                .thenApply(answer -> answer.flatMap(BankTop::decode));
    }
}
