package de.raindancer.modules.hungergames.service;

/**
 * A request was malformed (HTTP 400): a missing field, a value that will not parse, a body larger than
 * the transport allows. Thrown by {@link ApiRequest}'s {@code require*} helpers so a handler can unpack
 * its parameters without a branch for every one of them, and by validation deeper in an endpoint that
 * finds the shape of the request wrong rather than the state of the game. {@link HttpApiService} turns
 * this into a {@code {"error": ...}} body.
 *
 * <p>Not a service: an exception is thrown and caught within a single request, and nothing about it
 * survives, or needs to survive, a settings reload.
 */
public class ApiBadRequestException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ApiBadRequestException(String message) {
        super(message);
    }
}
