package de.raindancer.modules.hungergames.service;

/**
 * The request was well-formed, but the action it asks for cannot happen right now (HTTP 409): the named
 * player is not online, the round is in the wrong phase, a team is full. Thrown by the resolving helpers
 * in {@link ApiSupport} and by an endpoint that finds the game's state, rather than the request's shape,
 * is what refuses it. {@link HttpApiService} turns this into a {@code {"error": ...}} body.
 *
 * <p>Not a service, for the same reason as {@link ApiBadRequestException}: an exception carries nothing
 * that could be stale after a settings reload.
 */
public class ApiConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ApiConflictException(String message) {
        super(message);
    }
}
