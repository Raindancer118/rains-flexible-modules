package de.raindancer.modules.jobs.rules;

/** A rule belonging to this module: decides, and does nothing else. No side effects, safe from any thread. */
public interface IJobsRule {

    String describe();
}
