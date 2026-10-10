package de.raindancer.modules.roles.rules;

/** The arithmetic of abilities, kept apart from the events so it can be checked without a server. */
public final class AbilityRule implements IRolesRule {

    public double less(double amount, int percent) {
        return Math.max(0, amount * (1 - percent / 100.0));
    }

    public double more(double amount, int percent) {
        return amount * (1 + percent / 100.0);
    }

    /** @param roll uniform in [0, 1) */
    public boolean happens(int percent, double roll) {
        return roll < percent / 100.0;
    }

    /** Experience with {@code percent} more; the fraction left over is a chance of one more point. */
    public int experience(int amount, int percent, double roll) {
        if (amount <= 0 || percent <= 0) {
            return amount;
        }
        double exact = amount * (1 + percent / 100.0);
        int whole = (int) Math.floor(exact);
        return roll < exact - whole ? whole + 1 : whole;
    }

    @Override
    public String describe() {
        return "what a role's abilities do to hunger, falls, damage, chances and experience";
    }
}
