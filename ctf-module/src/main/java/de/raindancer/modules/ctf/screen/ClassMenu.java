package de.raindancer.modules.ctf.screen;

import de.raindancer.core.ui.effect.Cues;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.modules.ctf.CtfServices;
import de.raindancer.modules.ctf.model.Mutator;
import de.raindancer.modules.ctf.model.Role;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** The classes, the one you will play next marked; one that cannot be played this round greyed with the reason. */
public final class ClassMenu extends Menu implements ICtfScreen {

    private final CtfServices services;

    public ClassMenu(CtfServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        return MiniMessage.miniMessage().deserialize("<dark_gray>Your class");
    }

    @Override
    protected void render() {
        Role current = services.rounds().pickOf(viewer.getUniqueId());
        Mutator mutator = services.rounds().round().map(round -> round.mutator()).orElse(Mutator.NONE);
        Role[] roles = Role.values();
        for (int index = 0; index < roles.length; index++) {
            Role role = roles[index];
            Optional<String> why = services.roles().whyNot(role, services.config().get().building(), mutator);
            List<String> lore = new ArrayList<>();
            role.lines().forEach(line -> lore.add("<gray>" + line));
            lore.add(role == current ? "<green>Your class." : "<yellow>Click to play it from your next life.");
            String name = (role == current ? "<green><bold>" : "<white>") + role.title();
            int band = index < 4 ? 1 : 2;
            int column = index < 4 ? 1 + index * 2 : 1 + (index - 4) * 2;
            band(band, column, why.isEmpty(), Icons.of(role.icon(), name, lore),
                    why.map(key -> services.messages().raw(key)).orElse(""), click -> {
                        services.rounds().pick(viewer, role);
                        services.core().effects().play(viewer.getUniqueId(), Cues.CLICK);
                        services.messages().send(viewer, "ctf.class.picked", "class", role.title());
                        refresh();
                    });
        }
    }

    @Override
    public String breadcrumb() {
        return "class";
    }

    @Override
    public String describe() {
        return "picking a class";
    }
}
