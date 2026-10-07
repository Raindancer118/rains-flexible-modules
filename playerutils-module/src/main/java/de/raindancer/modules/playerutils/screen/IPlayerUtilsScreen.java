package de.raindancer.modules.playerutils.screen;

public interface IPlayerUtilsScreen {

    void open();

    default String describe() {
        String name = getClass().getSimpleName();
        return name.isEmpty() ? getClass().getName() : name;
    }
}
