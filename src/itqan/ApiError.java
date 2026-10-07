package itqan;

/** An error whose message is shown to the user as-is. */
public final class ApiError extends RuntimeException {
    final int status;

    public ApiError(int status, String message) {
        super(message);
        this.status = status;
    }
}
