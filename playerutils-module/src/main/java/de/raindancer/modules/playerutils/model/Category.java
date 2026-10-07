package de.raindancer.modules.playerutils.model;

/** Where an action sits on the player page and in the help. */
public enum Category {
    VITALS("Vitals"),
    MOVEMENT("Movement"),
    BODY("Body"),
    POWER("Power"),
    INFO("Information");

    private final String title;

    Category(String title) {
        this.title = title;
    }

    public String title() {
        return title;
    }
}
