package de.raindancer.modules.economy.model;

import de.raindancer.core.social.economy.Money;
import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AutoSalesTest {

    private static final UUID ADA = UUID.nameUUIDFromBytes("ada".getBytes());
    private static final UUID BO = UUID.nameUUIDFromBytes("bo".getBytes());

    @Test
    @DisplayName("sales add up per player until they are paid out, in one go")
    void addsUp() {
        AutoSales sales = new AutoSales();
        sales.add(ADA, Material.COBBLESTONE, 1, new Money(5));
        sales.add(ADA, Material.COBBLESTONE, 2, new Money(10));
        sales.add(ADA, Material.RAW_IRON, 1, new Money(30));
        sales.add(BO, Material.COAL, 4, new Money(8));

        assertThat(sales.owed(ADA)).isEqualTo(new Money(45));

        Map<UUID, AutoSales.Batch> paid = sales.drain();
        assertThat(paid.get(ADA).total()).isEqualTo(new Money(45));
        assertThat(paid.get(ADA).counts()).containsEntry(Material.COBBLESTONE, 3).containsEntry(Material.RAW_IRON, 1);
        assertThat(paid.get(ADA).items()).isEqualTo(4);
        assertThat(paid.get(BO).total()).isEqualTo(new Money(8));

        assertThat(sales.owed(ADA)).as("drained").isEqualTo(Money.ZERO);
        assertThat(sales.drain()).isEmpty();
    }

    @Test
    @DisplayName("one player can be paid out alone, when they leave")
    void one() {
        AutoSales sales = new AutoSales();
        sales.add(ADA, Material.COAL, 1, new Money(2));
        sales.add(BO, Material.COAL, 1, new Money(2));
        assertThat(sales.drain(ADA)).hasValueSatisfying(batch -> assertThat(batch.total()).isEqualTo(new Money(2)));
        assertThat(sales.drain(ADA)).isEmpty();
        assertThat(sales.owed(BO)).isEqualTo(new Money(2));
    }

    @Test
    @DisplayName("what everybody is still owed counts against the server's budget too")
    void owedByAll() {
        AutoSales sales = new AutoSales();
        sales.add(ADA, Material.COAL, 1, new Money(2));
        sales.add(BO, Material.COAL, 1, new Money(3));
        assertThat(sales.owedByAll()).isEqualTo(new Money(5));
        sales.drain(ADA);
        assertThat(sales.owedByAll()).isEqualTo(new Money(3));
    }
}
