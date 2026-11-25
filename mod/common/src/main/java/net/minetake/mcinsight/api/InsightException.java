package net.minetake.mcinsight.api;

/**
 * Failure that already carries its final error code, raised where the cause is known.
 *
 * <p>Collectors throw this instead of returning pre-built error objects so that a failure cannot be
 * silently nested inside a successful envelope by an intermediate layer.
 */
public final class InsightException extends RuntimeException {

    private final InsightError error;

    public InsightException(InsightError error, String message) {
        super(message != null && !message.isBlank() ? message : error.defaultMessage());
        this.error = error;
    }

    public InsightException(InsightError error) {
        this(error, error.defaultMessage());
    }

    public InsightError error() {
        return error;
    }
}
