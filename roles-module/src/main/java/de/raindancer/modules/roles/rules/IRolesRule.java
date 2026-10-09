package de.raindancer.modules.roles.rules;

/** A rule belonging to this module: decides, and does nothing else. No side effects, safe from any thread. */
public interface IRolesRule {

    String describe();
}
