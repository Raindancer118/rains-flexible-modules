package de.raindancer.modules.oneblock.service;

import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.modules.oneblock.model.Invite;
import de.raindancer.modules.oneblock.model.IslandFacts;
import de.raindancer.modules.oneblock.model.IslandView;
import de.raindancer.modules.oneblock.model.Progress;
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

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.Set;
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
    /** Who last handed BentoBox an invite for each player — asking BentoBox reads its database and logs when it is gone. */
    private final java.util.Map<UUID, UUID> offeredBy = new java.util.concurrent.ConcurrentHashMap<>();

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
        offeredBy.put(invite.to().id(), invite.from().id());
    }

    @Override
    public void withdrawTeam(Invite invite) {
        UUID invitee = invite.to().id();
        // Only BentoBox's invite that came from this inviter: another may have replaced it since, and that one stays.
        if (offeredBy.remove(invitee, invite.from().id())) {
            team().ifPresent(command -> command.removeInvite(invitee));
        }
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
    public Optional<IslandView> view(UUID playerId) {
        Player player = server.getPlayer(playerId);
        Optional<GameModeAddon> addon = addon();
        if (player == null || addon.isEmpty()) {
            return Optional.empty();
        }
        var islands = BentoBox.getInstance().getIslands();
        Optional<Island> here = addon.get().inWorld(player.getWorld()) ? islands.getIslandAt(player.getLocation())
                : Optional.empty();
        Island island = here.orElseGet(() -> islands.getIsland(addon.get().getOverWorld(), playerId));
        if (island == null) {
            return Optional.empty();
        }
        UUID owner = island.getOwner();
        Set<UUID> members = island.getMemberSet();
        int online = (int) members.stream().filter(member -> server.getPlayer(member) != null).count();
        return Optional.of(new IslandView(here.isPresent(), members.contains(playerId),
                owner == null ? null : BentoBox.getInstance().getPlayers().getName(owner), island.getRank(playerId),
                members.size(), islands.getMaxMembers(island, RanksManager.MEMBER_RANK), online,
                progress(addon.get(), island)));
    }

    private volatile boolean progressBroken;

    /**
     * AOneBlock's count for the island. Read by reflection: AOneBlock is a BentoBox addon with a class loader of its
     * own, which this plugin cannot link against. A change on its side costs the phase lines, said once in the log.
     */
    private Optional<Progress> progress(GameModeAddon addon, Island island) {
        if (progressBroken) {
            return Optional.empty();
        }
        try {
            Object counted = call(addon, "getOneBlocksIsland", Island.class, island);
            if (counted == null) {
                return Optional.empty();
            }
            Object phases = call(addon, "getOneBlockManager", null, null);
            // The phase from the count, not the island's own phase name: that one is empty until the first block breaks.
            int count = ((Number) call(counted, "getBlockNumber", null, null)).intValue();
            Object current = phases.getClass().getMethod("getPhase", int.class).invoke(phases, count);
            if (current == null) {
                return Optional.empty();
            }
            String phase = plain((String) call(current, "getPhaseName", null, null));
            double done = ((Number) call(phases, "getPercentageDone", counted.getClass(), counted)).doubleValue();
            Object next = call(phases, "getNextPhase", current.getClass(), current);
            String nextName = next == null ? "" : plain((String) call(next, "getPhaseName", null, null));
            int toNext = next == null ? 0 : ((Number) call(next, "getBlockNumberValue", null, null)).intValue() - count;
            return Optional.of(new Progress(phase, count, (int) Math.floor(done), nextName, Math.max(0, toNext)));
        } catch (ReflectiveOperationException | RuntimeException changed) {
            progressBroken = true;
            log.warn("AOneBlock's phase could not be read ({}); the sidebar goes on without it.", changed.toString());
            return Optional.empty();
        }
    }

    private static Object call(Object target, String name, Class<?> parameter, Object argument)
            throws ReflectiveOperationException {
        Method method = parameter == null ? target.getClass().getMethod(name) : target.getClass().getMethod(name, parameter);
        return parameter == null ? method.invoke(target) : method.invoke(target, argument);
    }

    /** Phase names may carry colour codes of their own; the sidebar colours them. */
    private static String plain(String text) {
        return text == null ? "" : text.replaceAll("(?i)[&\u00a7][0-9a-fk-orx]", "").strip();
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
