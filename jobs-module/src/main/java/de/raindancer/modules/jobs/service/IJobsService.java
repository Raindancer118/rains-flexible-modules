package de.raindancer.modules.jobs.service;

import de.raindancer.modules.jobs.JobsSettings;

/** A service belonging to this module: does, and decides as little as possible. */
public interface IJobsService {

    void settings(JobsSettings settings);
}
