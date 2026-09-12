package tomatopotato.flaver.backend;

/**
 * A structured failure from the coordination backend.
 *
 * <p>Carries the machine-readable {@code error} code from the response body so
 * callers can react to specific cases — telling a player their friend has not
 * installed the mod, say — rather than matching on message text.
 */
public class BackendException extends RuntimeException {
	private final String code;
	private final int status;

	public BackendException(String code, String message, int status) {
		super(message);
		this.code = code;
		this.status = status;
	}

	public String code() {
		return this.code;
	}

	public int status() {
		return this.status;
	}

	/** True when the session token was missing, invalid or expired. */
	public boolean isUnauthorized() {
		return this.status == 401;
	}

	@Override
	public String toString() {
		return "BackendException[" + this.status + " " + this.code + ": " + getMessage() + "]";
	}
}
