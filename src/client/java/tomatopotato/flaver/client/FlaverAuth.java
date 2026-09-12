package tomatopotato.flaver.client;

import com.mojang.authlib.exceptions.AuthenticationException;
import com.mojang.authlib.exceptions.AuthenticationUnavailableException;
import com.mojang.authlib.exceptions.InvalidCredentialsException;
import com.mojang.authlib.exceptions.InsufficientPrivilegesException;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import tomatopotato.flaver.Flaver;
import tomatopotato.flaver.backend.BackendClient;
import tomatopotato.flaver.backend.BackendException;
import tomatopotato.flaver.backend.FlaverConfig;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Signs the player in to the coordination backend.
 *
 * <p>No passwords and no new accounts: this reuses Minecraft's own server-join
 * handshake. The backend hands out a nonce, we pass it to Mojang as if it were a
 * server id, and the backend then asks Mojang whether that same nonce was joined.
 * A match proves we hold a live session for the account, and yields its real UUID.
 *
 * <p>The resulting token is cached in {@link FlaverConfig}, so this normally runs
 * once per install rather than once per launch.
 */
public final class FlaverAuth {
	/** joinServer blocks on network I/O, so it never runs on the render thread. */
	private static final Executor AUTH_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "flaver-auth");
		thread.setDaemon(true);
		return thread;
	});

	public enum State {
		SIGNED_OUT,
		IN_PROGRESS,
		READY,
		FAILED,
	}

	private static volatile State state = State.SIGNED_OUT;
	private static volatile String lastError;
	/** In-flight attempt, shared so concurrent callers do not each start a login. */
	private static CompletableFuture<BackendClient.Session> pending;

	private FlaverAuth() {
	}

	public static State state() {
		return state;
	}

	/** Human-readable reason the last attempt failed, for display in the UI. */
	public static String lastError() {
		return lastError;
	}

	public static boolean isReady() {
		return state == State.READY;
	}

	/**
	 * Returns a valid session, authenticating if necessary.
	 *
	 * <p>Safe to call from anywhere and as often as needed: a cached token short
	 * circuits it, and concurrent calls share one attempt.
	 */
	public static synchronized CompletableFuture<BackendClient.Session> ensureAuthenticated() {
		if (pending != null && !pending.isCompletedExceptionally()) {
			return pending;
		}

		state = State.IN_PROGRESS;
		lastError = null;

		FlaverConfig config = FlaverConfig.get();
		BackendClient backend = BackendClient.get();

		UUID localProfile = Minecraft.getInstance().getUser().getProfileId();

		CompletableFuture<BackendClient.Session> attempt;
		if (config.hasSessionToken()) {
			// Confirm the cached token is still good before trusting it; a rotated
			// TOKEN_SECRET or a 30-day expiry both surface here as a 401.
			attempt = backend.me()
					.thenCompose(me -> {
						// The cached token belongs to whoever was signed in last. If the
						// launcher has since switched to a different Minecraft account,
						// reusing it would host worlds and show friends under the wrong
						// identity, so start over as the current profile instead.
						if (!me.uuid().equals(localProfile)) {
							Flaver.LOGGER.info(
									"Stored Flaver session belongs to {} but this profile is {}, signing in again",
									me.username(), Minecraft.getInstance().getUser().getName());
							config.clearSession();
							return login();
						}
						return CompletableFuture.completedFuture(
								new BackendClient.Session(config.sessionToken(), me.uuid(), me.username()));
					})
					.exceptionallyCompose(throwable -> {
						if (unwrap(throwable) instanceof BackendException e && e.isUnauthorized()) {
							Flaver.LOGGER.info("Stored Flaver session was rejected, signing in again");
							config.clearSession();
							return login();
						}
						return CompletableFuture.failedFuture(throwable);
					});
		} else {
			attempt = login();
		}

		pending = attempt.whenComplete((session, throwable) -> {
			if (throwable != null) {
				state = State.FAILED;
				Throwable cause = unwrap(throwable);
				lastError = cause instanceof BackendException e ? e.getMessage() : cause.toString();
				Flaver.LOGGER.warn("Flaver sign-in failed: {}", lastError);
			} else {
				state = State.READY;
				lastError = null;
				Flaver.LOGGER.info("Signed in to Flaver as {} ({})", session.username(), session.uuid());
			}
		});

		return pending;
	}

	/** Discards any cached session so the next call re-authenticates from scratch. */
	public static synchronized void reset() {
		pending = null;
		state = State.SIGNED_OUT;
		lastError = null;
	}

	private static CompletableFuture<BackendClient.Session> login() {
		Minecraft minecraft = Minecraft.getInstance();
		User user = minecraft.getUser();
		BackendClient backend = BackendClient.get();
		FlaverConfig config = FlaverConfig.get();

		// The dev runtime runs with a fabricated profile, so Mojang can never
		// confirm the session. Fall back to the backend's dev route, which only
		// exists when DEV_AUTH is set and is absent from any deployment.
		//
		// Both checks are needed: isOfflineDeveloperMode() only reflects the
		// --offlineDeveloperMode launch flag, while isDevelopmentEnvironment() is
		// true for any Loom run config and false in a built jar.
		if (FabricLoader.getInstance().isDevelopmentEnvironment() || minecraft.isOfflineDeveloperMode()) {
			Flaver.LOGGER.info("Offline developer mode: using Flaver dev login for {}", user.getName());
			return backend.devLogin(user.getProfileId(), user.getName())
					.thenApply(session -> store(config, session));
		}

		return backend.challenge()
				.thenCompose(challenge -> proveSession(minecraft, user, challenge.serverId()))
				.thenCompose(serverId -> backend.verify(user.getName(), serverId))
				.thenApply(session -> store(config, session));
	}

	/**
	 * Asks Mojang to record that we "joined a server" whose id is the backend's
	 * nonce. Returns the nonce so the caller can have the backend verify it.
	 */
	private static CompletableFuture<String> proveSession(Minecraft minecraft, User user, String serverId) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				minecraft.services().sessionService().joinServer(user.getProfileId(), user.getAccessToken(), serverId);
				return serverId;
			} catch (AuthenticationUnavailableException e) {
				throw new BackendException("mojang_unavailable",
						"Mojang's session servers are unreachable. Check your connection and try again.", 0);
			} catch (InvalidCredentialsException e) {
				throw new BackendException("invalid_session",
						"Your Minecraft session has expired. Restart the game or the launcher and try again.", 0);
			} catch (InsufficientPrivilegesException e) {
				throw new BackendException("insufficient_privileges",
						"This account is not allowed to play multiplayer.", 0);
			} catch (AuthenticationException e) {
				throw new BackendException("mojang_auth_failed",
						"Could not verify your Minecraft account: " + e.getMessage(), 0);
			}
		}, AUTH_EXECUTOR);
	}

	private static BackendClient.Session store(FlaverConfig config, BackendClient.Session session) {
		UUID uuid = session.uuid();
		config.setSession(session.token(), uuid, session.username());
		return session;
	}

	private static Throwable unwrap(Throwable throwable) {
		Throwable current = throwable;
		while ((current instanceof java.util.concurrent.CompletionException
				|| current instanceof java.util.concurrent.ExecutionException)
				&& current.getCause() != null) {
			current = current.getCause();
		}
		return current;
	}
}
