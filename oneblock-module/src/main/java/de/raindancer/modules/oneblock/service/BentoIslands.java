package de.raindancer.modules.oneblock.service;

import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.modules.oneblock.model.Invite;
import de.raindancer.modules.oneblock.model.IslandFacts;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import world.bentobox.bentobox.BentoBox;
import world.bentobox.bentobox.api.addons.GameModeAddon;
import world.bentobox.bentobox.api.commands.CompositeCommand;
import world.bentobox.bentobox.api.commands.island.team.IslandTeamCommand;
import world.bentobox.bentobox.database.objects.Island;
import world.bentobox.bentobox.database.objects.TeamInvite;
import world.bentobox.bentobox.managers.RanksManager;
import world.bentobox.bentobox.util.teleport.SafeSpotTeleport;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * {@link IslandHost} on BentoBox. The only class of this module that names a BentoBox type — it is never loaded on a
 * server without BentoBox, see {@link IslandHosts}.
 */
final class BentoIslands implements IslandHost {

    private final Server server;
    private final LogChannel log;
    private final Supplier<String> gameMode;

    BentoIslands(Server server, LogChannel log, Supplier<String> gameMode) {
        this.server = server;
        this.log = log;
        this.gameMode = gameMode;
    }

    private Optional<GameModeAddon> addon() {
        BentoBox bento = BentoBox.getInstance();
        if (bento == null) {
            return Optional.empty();
        }
        String wanted = gameMode.get();
        return bento.getAddonsManager().getGameModeAddons().stream()
                .filter(each -> each.getDescription().getName().equalsIgnoreCase(wanted)).findFirst();
    }

    private Optional<CompositeCommand> playerCommand() {
        return addon().flatMap(GameModeAddon::getPlayerCommand);
    }

    private Optional<IslandTeamCommand> team() {
        return playerCommand().flatMap(command -> command.getSubCommand("team"))
                .filter(IslandTeamCommand.class::isInstance).map(IslandTeamCommand.class::cast);
    }

    @Override
    public boolean ready() {
        return addon().isPresent();
    }

    @Override
    public Optional<IslandFacts> facts(UUID inviter, UUID invitee) {
        Optional<GameModeAddon> addon = addon();
        if (addon.isEmpty()) {
            return Optional.empty();
        }
        World world = addon.get().getOverWorld();
        Island island = BentoBox.getInstance().getIslands().getIsland(world, inviter);
        if (island == null) {
            return Optional.empty();
        }
        // The island's own setting for who may use the invite command; owner-only when the command is missing.
        int teamInviteRank = team().map(command -> island.getRankCommand(command.getInviteCommand().getUsage()))
                .orElse(RanksManager.OWNER_RANK);
        return Optional.of(new IslandFacts(island.getUniqueId(), island.getRank(inviter), teamInviteRank,
                island.getMemberSet().size(),
                BentoBox.getInstance().getIslands().getMaxMembers(island, RanksManager.MEMBER_RANK),
                island.getMemberSet().contains(invitee), island.isBanned(invitee)));
    }

    @Override
    public void offerTeam(Invite invite) {
        Optional<IslandTeamCommand> team = team();
        Optional<Island> island = BentoBox.getInstance().getIslands().getIslandById(invite.islandId());
        if (team.isEmpty() || island.isEmpty()) {
            log.warn("Could not hand BentoBox the invite of {} to {}: no team command or island.", invite.from().name(),
                    invite.to().name());
            return;
        }
        team.get().addInvite(TeamInvite.Type.TEAM, invite.from().id(), invite.to().id(), island.get());
    }

    @Override
    public void withdrawTeam(Invite invite) {
        team().ifPresent(command -> {
            // getInvite, not isInvited: the latter throws once BentoBox has dropped the invite itself.
            UUID invitee = invite.to().id();
            TeamInvite current = command.getInvite(invitee);
            if (current != null && invite.from().id().equals(current.getInviter())) {
                command.removeInvite(invitee);
            }
        });
    }

    @Override
    public void join(Invite invite) {
        Player player = server.getPlayer(invite.to().id());
        Optional<CompositeCommand> command = playerCommand();
        if (player == null || command.isEmpty()) {
            return;
        }
        // BentoBox keeps one invite per player; another inviter may have replaced this one meanwhile.
        offerTeam(invite);
        player.performCommand(command.get().getLabel() + " team accept");
    }

    @Override
    public void visit(UUID playerId, String islandId) {
        Player player = server.getPlayer(playerId);
        if (player == null) {
            return;
        }
        BentoBox.getInstance().getIslands().getIslandById(islandId).ifPresent(island ->
                new SafeSpotTeleport.Builder(BentoBox.getInstance()).entity(player).island(island).build());
    }

    @Override
    public boolean inGameWorld(UUID playerId) {
        Player player = server.getPlayer(playerId);
        return player != null && addon().map(mode -> mode.inWorld(player.getWorld())).orElse(false);
    }

    @Override
    public void home(UUID playerId) {
        Player player = server.getPlayer(playerId);
        playerCommand().ifPresent(command -> {
            if (player != null) {
                player.performCommand(command.getLabel());
            }
        });
    }
}
