package de.raindancer.modules.guilds.service;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.guilds.GuildProgressSettings;
import de.raindancer.modules.guilds.GuildsSettings;
import de.raindancer.modules.guilds.model.Fixtures;
import de.raindancer.modules.guilds.model.Guild;
import de.raindancer.modules.guilds.model.GuildOutcome;
import de.raindancer.modules.guilds.model.GuildPermission;
import de.raindancer.modules.guilds.model.Perk;
import de.raindancer.modules.guilds.model.Rank;
import de.raindancer.modules.guilds.store.GuildBook;
import de.raindancer.modules.guilds.store.GuildsDatabase;
import de.raindancer.modules.guilds.store.ProgressBook;
import de.raindancer.modules.guilds.store.SqliteGuildStorage;
import de.raindancer.modules.guilds.store.SqliteProgressStorage;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static de.raindancer.modules.guilds.model.Fixtures.LEADER;
import static de.raindancer.modules.guilds.model.Fixtures.MEMBER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The vault's two promises from the security review: nothing goes in while a disband runs, and taking is asked fresh. */
class VaultTest {

    @TempDir
    Path folder;
    private Database database;
    private GuildBook book;
    private PerkService perks;
    private GuildService guilds;
    private BankService bank;
    private final AtomicLong clock = new AtomicLong(1_000_000_000L);

    private static ItemStack dirt(int amount) {
        ItemStack item = mock(ItemStack.class);
        when(item.getType()).thenReturn(Material.DIRT);
        when(item.getAmount()).thenReturn(amount);
        when(item.getMaxStackSize()).thenReturn(64);
        when(item.isEmpty()).thenReturn(amount <= 0);
        when(item.asQuantity(anyInt())).thenAnswer(call -> dirt(call.getArgument(0)));
        when(item.isSimilar(any())).thenReturn(false);
        return item;
    }

    @BeforeEach
    void setUp() {
        database = Database.open(folder.resolve("guilds.db"), GuildsDatabase.SCHEMA, () -> false);
        book = new GuildBook(new SqliteGuildStorage(database, problem -> { }));
        book.load();
        ProgressBook progress = new ProgressBook(new SqliteProgressStorage(database));
        progress.load();
        Server server = mock(Server.class);
        when(server.getOfflinePlayer(any(UUID.class))).thenAnswer(call -> {
            OfflinePlayer player = mock(OfflinePlayer.class);
            when(player.getUniqueId()).thenReturn(call.getArgument(0));
            when(player.getName()).thenReturn("someone");
            return player;
        });
        GuildNotifier notify = new GuildNotifier(server, new Messages(folder.resolve("messages.yml")));
        perks = new PerkService(book, progress, notify, TestVaults.in(folder),
                clock::get, GuildsSettings.DEFAULTS, GuildProgressSettings.DEFAULTS);
        guilds = new GuildService(book, notify, Log.of("test"), clock::get, who -> OptionalLong.empty(), GuildsSettings.DEFAULTS);
        bank = new BankService(book, notify, Log.of("test"), clock::get, Optional::empty, GuildsSettings.DEFAULTS);
        guilds.disbandGuard(perks::sealForDisband, perks::unseal);
        Guild elves = Fixtures.guild("Elves").withXp(1_000_000);
        assertThat(book.create(elves)).isEqualTo(GuildOutcome.SUCCESS);
        assertThat(perks.raise(LEADER, Perk.VAULT)).isEqualTo(GuildOutcome.SUCCESS);
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    private Guild elves() {
        return book.guildOf(LEADER).orElseThrow();
    }

    @Test
    @DisplayName("a sealed vault takes nothing; a disband that does not go through unseals it")
    void sealed() {
        assertThat(perks.sealForDisband(elves())).isEqualTo(GuildOutcome.SUCCESS);
        assertThat(perks.deposit(elves(), dirt(5))).as("between the check and the disband").isZero();
        perks.unseal(elves());
        assertThat(perks.deposit(elves(), dirt(5))).isEqualTo(5);
        assertThat(perks.sealForDisband(elves())).isEqualTo(GuildOutcome.VAULT_NOT_EMPTY);
    }

    @Test
    @DisplayName("a disband with something in the vault is refused; one that fails afterwards unseals")
    void disband() {
        perks.deposit(elves(), dirt(1));
        assertThat(guilds.disband(LEADER, elves().id(), false, bank)).isEqualTo(GuildOutcome.VAULT_NOT_EMPTY);
        assertThat(book.guildOf(LEADER)).isPresent();
    }

    @Test
    @DisplayName("an empty vault's disband that fails (the bank cannot pay out) leaves the vault usable")
    void failedDisbandUnseals() {
        book.update(elves().id(), guild -> guild.withBank(500));
        assertThat(guilds.disband(LEADER, elves().id(), false, bank)).isEqualTo(GuildOutcome.ECONOMY_DOWN);
        assertThat(perks.deposit(elves(), dirt(3))).as("unsealed again").isEqualTo(3);
    }

    @Test
    @DisplayName("taking asks the guild as it is now — a rank that lost the right cannot take with an old copy")
    void freshPermission() {
        perks.deposit(elves(), dirt(2));
        Guild drawn = elves();
        assertThat(drawn.may(MEMBER, GuildPermission.VAULT)).isTrue();
        book.update(drawn.id(), guild -> guild.withRank(guild.rank(Rank.MEMBER).orElseThrow().toggled(GuildPermission.VAULT)));
        assertThat(perks.takeArmour(drawn, MEMBER, de.raindancer.core.data.stash.ArmourPiece.values()[0])).isNull();
        assertThat(perks.take(drawn, MEMBER, 0, perks.vaultItems(elves()).getFirst())).as("with the old copy").isNull();
        book.update(drawn.id(), guild -> guild.withoutMember(MEMBER));
        assertThat(perks.take(drawn, MEMBER, 0, perks.vaultItems(elves()).getFirst())).as("no longer a member").isNull();
    }

    @Test
    @DisplayName("a vault whose file cannot be read is never taken for empty: no disband, no deposits")
    void unreadableFailsClosed() throws Exception {
        Path file = folder.resolve("vaults").resolve(PerkService.vaultOwner(elves()) + ".yml");
        java.nio.file.Files.createDirectories(file.getParent());
        java.nio.file.Files.writeString(file, "items: [unclosed\n  : : :\n");
        assertThat(perks.vaultItems(elves())).as("it loads empty").isEmpty();
        assertThat(perks.vaultEmpty(elves())).isFalse();
        assertThat(perks.sealForDisband(elves())).isEqualTo(GuildOutcome.VAULT_UNREADABLE);
        assertThat(perks.deposit(elves(), dirt(4))).as("would live only until the next restart").isZero();
        assertThat(guilds.disband(LEADER, elves().id(), false, bank)).isEqualTo(GuildOutcome.VAULT_UNREADABLE);
        assertThat(book.guildOf(LEADER)).isPresent();
    }

    @Test
    @DisplayName("a guild founded again under the same name gets a vault of its own")
    void refoundedVault() {
        Guild first = elves();
        Guild again = first.withLeader(LEADER);
        Guild refounded = new Guild(first.id(), first.name(), first.tag(), first.colour(), first.emblem(), first.motd(),
                first.founded() + 1, first.leader(), first.joinMode(), 0, 0, Optional.empty(), false, false,
                first.members(), first.ranks(), first.relations(), first.tagStyle(), first.nameStyle());
        assertThat(PerkService.vaultOwner(again)).isEqualTo(PerkService.vaultOwner(first));
        assertThat(PerkService.vaultOwner(refounded)).isNotEqualTo(PerkService.vaultOwner(first));
    }
}
