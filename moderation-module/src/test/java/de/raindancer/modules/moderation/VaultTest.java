package de.raindancer.modules.moderation;

import de.raindancer.core.testkit.TestItems;
import de.raindancer.modules.moderation.model.ArmourPiece;
import de.raindancer.modules.moderation.model.Vault;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An operator's vault: what goes in, where it lands, and that nothing is ever counted twice or lost.
 */
class VaultTest {

    private static ItemStack dirt(int amount) {
        return TestItems.of(Material.DIRT, amount);
    }

    @Nested
    @DisplayName("putting things in")
    class Depositing {

        @Test
        @DisplayName("similar stacks are topped up before a new one is started")
        void merges() {
            Vault vault = new Vault(10);

            assertThat(vault.deposit(dirt(30))).isEqualTo(30);
            assertThat(vault.deposit(dirt(40))).isEqualTo(40);

            assertThat(vault.items()).extracting(ItemStack::getAmount).containsExactly(64, 6);
        }

        @Test
        @DisplayName("a named stack does not merge into an unnamed one")
        void onlySimilarMerges() {
            Vault vault = new Vault(10);
            vault.deposit(dirt(10));
            ItemStack named = TestItems.of(Material.DIRT, meta -> meta.customName(Component.text("Special")));

            vault.deposit(named);

            assertThat(vault.items()).hasSize(2);
        }

        @Test
        @DisplayName("a full vault takes what fits and says how much that was")
        void respectsCapacity() {
            Vault vault = new Vault(1);
            vault.deposit(dirt(60));

            assertThat(vault.deposit(dirt(10))).as("only four more fit on the one stack").isEqualTo(4);
            assertThat(vault.deposit(TestItems.of(Material.STONE, 1))).isZero();
            assertThat(vault.items()).extracting(ItemStack::getAmount).containsExactly(64);
        }

        @Test
        @DisplayName("the stack handed in is never changed — the caller takes away what was accepted")
        void doesNotTouchTheArgument() {
            Vault vault = new Vault(10);
            ItemStack given = dirt(12);

            vault.deposit(given);
            given.setAmount(1);

            assertThat(given.getAmount()).isEqualTo(1);
            assertThat(vault.items().getFirst().getAmount()).isEqualTo(12);
        }

        @Test
        @DisplayName("nothing is nothing")
        void emptyIsRefused() {
            Vault vault = new Vault(10);

            assertThat(vault.deposit(null)).isZero();
            assertThat(vault.deposit(TestItems.air())).isZero();
            assertThat(vault.isEmpty()).isTrue();
        }
    }

    @Nested
    @DisplayName("armour")
    class Armour {

        @Test
        @DisplayName("a piece of armour goes on its own stand first, and into the vault once that is taken")
        void armourHasItsOwnSlots() {
            Vault vault = new Vault(10);

            vault.deposit(TestItems.of(Material.DIAMOND_HELMET));
            vault.deposit(TestItems.of(Material.IRON_HELMET));
            vault.deposit(TestItems.of(Material.ELYTRA));

            assertThat(vault.armour(ArmourPiece.HEAD)).get().extracting(ItemStack::getType)
                    .isEqualTo(Material.DIAMOND_HELMET);
            assertThat(vault.armour(ArmourPiece.CHEST)).get().extracting(ItemStack::getType)
                    .isEqualTo(Material.ELYTRA);
            assertThat(vault.items()).extracting(ItemStack::getType).containsExactly(Material.IRON_HELMET);
            assertThat(vault.hasArmour()).isTrue();
        }

        @Test
        @DisplayName("each piece knows its slot; anything else is not armour")
        void pieces() {
            assertThat(ArmourPiece.of(Material.TURTLE_HELMET)).contains(ArmourPiece.HEAD);
            assertThat(ArmourPiece.of(Material.NETHERITE_CHESTPLATE)).contains(ArmourPiece.CHEST);
            assertThat(ArmourPiece.of(Material.CHAINMAIL_LEGGINGS)).contains(ArmourPiece.LEGS);
            assertThat(ArmourPiece.of(Material.LEATHER_BOOTS)).contains(ArmourPiece.FEET);
            assertThat(ArmourPiece.of(Material.MACE)).isEmpty();
            assertThat(ArmourPiece.of(Material.CARVED_PUMPKIN)).isEmpty();
            assertThat(ArmourPiece.of(null)).isEmpty();
        }

        @Test
        @DisplayName("swapping a stand gives back what was on it")
        void swap() {
            Vault vault = new Vault(10);
            vault.deposit(TestItems.of(Material.DIAMOND_BOOTS));

            ItemStack before = vault.swapArmour(ArmourPiece.FEET, TestItems.of(Material.IRON_BOOTS));

            assertThat(before.getType()).isEqualTo(Material.DIAMOND_BOOTS);
            assertThat(vault.armour(ArmourPiece.FEET)).get().extracting(ItemStack::getType)
                    .isEqualTo(Material.IRON_BOOTS);
            assertThat(vault.swapArmour(ArmourPiece.FEET, null).getType()).isEqualTo(Material.IRON_BOOTS);
            assertThat(vault.hasArmour()).isFalse();
        }

        @Test
        @DisplayName("a stand only takes its own kind")
        void wrongPieceIsRefused() {
            Vault vault = new Vault(10);

            assertThat(vault.swapArmour(ArmourPiece.HEAD, TestItems.of(Material.DIAMOND_BOOTS)))
                    .as("refused, so handed straight back").extracting(ItemStack::getType)
                    .isEqualTo(Material.DIAMOND_BOOTS);
            assertThat(vault.armour(ArmourPiece.HEAD)).isEmpty();
        }
    }

    @Nested
    @DisplayName("taking things out")
    class Taking {

        @Test
        @DisplayName("an entry comes out once, when it is still what the screen showed")
        void takeOnce() {
            Vault vault = new Vault(10);
            vault.deposit(dirt(5));
            vault.deposit(TestItems.of(Material.STONE, 3));
            ItemStack shown = vault.items().get(1);

            assertThat(vault.take(1, shown)).extracting(ItemStack::getType).isEqualTo(Material.STONE);
            assertThat(vault.take(1, shown)).as("already gone — a double click must not pay twice").isNull();
            assertThat(vault.items()).extracting(ItemStack::getType).containsExactly(Material.DIRT);
        }

        @Test
        @DisplayName("a stale screen takes nothing")
        void staleIndex() {
            Vault vault = new Vault(10);
            vault.deposit(dirt(5));
            ItemStack shown = vault.items().getFirst();
            vault.deposit(dirt(5));

            assertThat(vault.take(0, shown)).as("the stack changed since it was drawn").isNull();
            assertThat(vault.take(7, shown)).isNull();
            assertThat(vault.items()).extracting(ItemStack::getAmount).containsExactly(10);
        }

        @Test
        @DisplayName("what is handed out is a copy")
        void copies() {
            Vault vault = new Vault(10);
            vault.deposit(dirt(5));

            vault.items().getFirst().setAmount(64);
            vault.armour(ArmourPiece.HEAD).ifPresent(piece -> piece.setAmount(2));

            assertThat(vault.items().getFirst().getAmount()).isEqualTo(5);
        }

        @Test
        @DisplayName("every change moves the version on; a refusal does not")
        void versions() {
            Vault vault = new Vault(1);
            long start = vault.contents().version();

            vault.deposit(dirt(64));
            long afterDeposit = vault.contents().version();
            vault.deposit(dirt(1));

            assertThat(afterDeposit).isGreaterThan(start);
            assertThat(vault.contents().version()).isEqualTo(afterDeposit);
        }
    }
}
