package de.raindancer.modules.wallsroads.rules;

import java.util.UUID;

/** Whether somebody may open or shut a gate. */
public final class GateRule {

    /**
     * @param openToEveryone what the wall's owner decided: a town gate people walk through, or a
     *                       keep's gate only its own people work
     */
    public boolean mayOperate(boolean openToEveryone, UUID wallOwner, UUID player, boolean mayManageAny) {
        return openToEveryone || mayManageAny || (wallOwner != null && wallOwner.equals(player));
    }
}
