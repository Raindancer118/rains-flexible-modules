package de.raindancer.modules.economy.rules;

import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.economy.model.Auction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AuctionPickTest {

    private final AuctionRule rule = new AuctionRule();
    private final UUID ada = UUID.randomUUID();
    private final Auction first = Auction.listed(UUID.fromString("11111111-0000-0000-0000-000000000000"), ada, "Ada",
            new byte[0], "Diamond", Money.of(10), Money.ZERO, 60, 1_000);
    private final Auction second = Auction.listed(UUID.fromString("22222222-0000-0000-0000-000000000000"), ada, "Ada",
            new byte[0], "Emerald", Money.of(10), Money.ZERO, 60, 2_000);
    private final Auction bos = Auction.listed(UUID.fromString("33333333-0000-0000-0000-000000000000"), UUID.randomUUID(),
            "Bo", new byte[0], "Gold", Money.of(10), Money.ZERO, 60, 1_500);
    private final List<Auction> all = List.of(first, bos, second);

    @Test
    @DisplayName("an auction is found by the short id its chat lines and menus show")
    void byId() {
        assertThat(rule.pick(all, "22222222")).contains(second);
        assertThat(rule.pick(all, "3333")).as("a start of it is enough when only one fits").contains(bos);
    }

    @Test
    @DisplayName("by a seller's name, any case: their newest auction")
    void bySeller() {
        assertThat(rule.pick(all, "ada")).contains(second);
        assertThat(rule.pick(all, "Bo")).contains(bos);
    }

    @Test
    @DisplayName("nothing for nobody, and nothing for an id start that fits several")
    void nothing() {
        assertThat(rule.pick(all, "Cy")).isEmpty();
        assertThat(rule.pick(List.of(first, Auction.listed(UUID.fromString("11112222-0000-0000-0000-000000000000"), ada,
                "Ada", new byte[0], "x", Money.of(1), Money.ZERO, 60, 3)), "1111")).isEmpty();
        assertThat(rule.pick(all, "")).isEmpty();
    }
}
