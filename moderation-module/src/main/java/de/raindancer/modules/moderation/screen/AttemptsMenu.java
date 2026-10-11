package de.raindancer.modules.moderation.screen;

import de.raindancer.core.moderation.punishment.BlockedAttempts;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.platform.util.Wrapping;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.modules.moderation.ModerationServices;
import de.raindancer.modules.moderation.command.AttemptsCommand;
import de.raindancer.modules.moderation.model.LogQuery;
import de.raindancer.modules.moderation.service.LogSearchService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * What one player tried while punished — joining while banned, talking while muted, building while frozen —
 * newest first. The window form of {@code /attempts player:<them>}; read off the server's threads first.
 */
public final class AttemptsMenu extends ModerationList<BlockedAttempts.Attempt> {

    public static final int MOST = 450;

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(LogSearchService.ZONE);

    private final UUID subject;
    private final String subjectName;
    private volatile List<BlockedAttempts.Attempt> attempts;

    public AttemptsMenu(ModerationServices services, Player viewer, Menu parent, UUID subject, String subjectName) {
        super(services, viewer, parent);
        this.subject = subject;
        this.subjectName = subjectName;
    }

    @Override
    public void open() {
        if (attempts != null) {
            super.open();
            return;
        }
        LogQuery query = LogQuery.parse(new String[]{"player:" + subject}, LogSearchService.ZONE, Instant.now());
        Scheduling.async(services().plugin(), () -> {
            attempts = List.copyOf(services().logSearch().attempts(query, MOST).items());
            Scheduling.entity(services().plugin(), viewer, super::open);
        });
    }

    @Override
    protected Component title() {
        return Component.text(subjectName + " tried while punished");
    }

    @Override
    public String breadcrumb() {
        return "Attempts";
    }

    @Override
    protected List<BlockedAttempts.Attempt> entries() {
        return attempts == null ? List.of() : attempts;
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.COBWEB, "<gray>Nothing tried while punished");
    }

    @Override
    protected ItemStack icon(BlockedAttempts.Attempt attempt) {
        Material material = switch (attempt.kind()) {
            case JOIN -> Material.IRON_DOOR;
            case CHAT -> Material.PAPER;
            case COMMAND -> Material.COMMAND_BLOCK;
            case BUILD -> Material.BRICKS;
        };
        List<String> lore = new ArrayList<>();
        lore.add("<gray>" + escaped(AttemptsCommand.punishmentWords(attempt)));
        if (!attempt.detail().isBlank()) {
            lore.addAll(Wrapping.wrap(escaped(attempt.detail()), 40, "<white>"));
        }
        lore.add("");
        lore.add("<dark_gray>" + WHEN.format(attempt.at()) + " (Berlin)");
        if (attempt.address() != null) {
            lore.add("<dark_gray>address #" + attempt.address());
        }
        return Icons.of(material, "<yellow>Tried to " + escaped(AttemptsCommand.kindWord(services(), attempt.kind())),
                lore);
    }

    @Override
    protected void onClick(BlockedAttempts.Attempt attempt, InventoryClickEvent event) {
        // Reading only.
    }

    private static String escaped(String text) {
        return MiniMessage.miniMessage().escapeTags(text);
    }

    @Override
    public String describe() {
        return "what somebody tried to do while punished";
    }
}
