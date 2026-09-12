package tomatopotato.flaver.backend;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import tomatopotato.flaver.Flaver;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Async wrapper over the coordination backend's HTTP API — one method per endpoint.
 *
 * <p>Every method returns a {@link CompletableFuture} and performs no blocking
 * work on the caller's thread, because all of these are reached from the render
 * thread. Callers that need to touch game state with a result must hop back via
 * {@code Minecraft#execute}.
 *
 * <p>Failures arrive as a {@link BackendException} wrapped in the future.
 */
public final class BackendClient {
	private static final Gson GSON = new Gson();
	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

	private static BackendClient instance;

	private final HttpClient http;
	private final FlaverConfig config;

	private BackendClient(FlaverConfig config) {
		this.config = config;
		AtomicInteger counter = new AtomicInteger();
		this.http = HttpClient.newBuilder()
				.connectTimeout(CONNECT_TIMEOUT)
				// Daemon threads: a pending request must never keep the JVM alive
				// when the player quits the game.
				.executor(Executors.newCachedThreadPool(runnable -> {
					Thread thread = new Thread(runnable, "flaver-http-" + counter.incrementAndGet());
					thread.setDaemon(true);
					return thread;
				}))
				.build();
	}

	public static synchronized BackendClient get() {
		if (instance == null) {
			instance = new BackendClient(FlaverConfig.get());
		}
		return instance;
	}

	// -----------------------------------------------------------------------
	// Response shapes
	// -----------------------------------------------------------------------

	public record Challenge(String serverId, int expiresIn) {
	}

	public record Session(String token, UUID uuid, String username) {
	}

	public record Me(UUID uuid, String username, long createdAt) {
	}

	/**
	 * A registered world. {@code tunnelHostname} is populated only when the
	 * backend is willing to disclose it: for your own servers, or for a friend's
	 * server that is currently online and fetched via {@link #friendServers}.
	 */
	public record ServerInfo(String serverId, String name, boolean online, String mcVersion, String tunnelHostname) {
	}

	public record Friend(UUID uuid, String username, boolean online, List<ServerInfo> servers, long since) {
	}

	public record FriendRequest(UUID uuid, String username, long createdAt) {
	}

	public record FriendsList(List<Friend> friends, List<FriendRequest> incoming, List<FriendRequest> outgoing) {
	}

	/** {@code status} is either {@code "requested"} or {@code "accepted"}. */
	public record RequestResult(String status, UUID uuid, String username) {
	}

	private record ServersResponse(List<ServerInfo> servers) {
	}

	// -----------------------------------------------------------------------
	// Auth
	// -----------------------------------------------------------------------

	public CompletableFuture<Challenge> challenge() {
		return send("POST", "/api/auth/challenge", null, false, Challenge.class);
	}

	public CompletableFuture<Session> verify(String username, String serverId) {
		JsonObject body = new JsonObject();
		body.addProperty("username", username);
		body.addProperty("serverId", serverId);
		return send("POST", "/api/auth/verify", body, false, Session.class);
	}

	/**
	 * Dev-only login that skips Mojang proof. Exists because the Fabric dev
	 * runtime is offline-mode, where {@code hasJoined} can never succeed. The
	 * deployed backend returns 404 for this route.
	 */
	public CompletableFuture<Session> devLogin(UUID uuid, String username) {
		JsonObject body = new JsonObject();
		body.addProperty("uuid", uuid.toString());
		body.addProperty("username", username);
		return send("POST", "/api/auth/dev", body, false, Session.class);
	}

	public CompletableFuture<Me> me() {
		return send("GET", "/api/me", null, true, Me.class);
	}

	// -----------------------------------------------------------------------
	// Servers and presence
	// -----------------------------------------------------------------------

	public CompletableFuture<ServerInfo> createServer(String name) {
		JsonObject body = new JsonObject();
		body.addProperty("name", name);
		return send("POST", "/api/servers", body, true, ServerInfo.class);
	}

	public CompletableFuture<List<ServerInfo>> ownServers() {
		return send("GET", "/api/servers", null, true, ServersResponse.class)
				.thenApply(ServersResponse::servers);
	}

	public CompletableFuture<Void> deleteServer(String serverId) {
		return send("DELETE", "/api/servers/" + encode(serverId), null, true, null).thenApply(ignored -> null);
	}

	/** Heartbeat. Also how a freshly started tunnel's hostname reaches the backend. */
	public CompletableFuture<Void> heartbeat(String serverId, String tunnelHostname, String mcVersion) {
		JsonObject body = new JsonObject();
		body.addProperty("serverId", serverId);
		body.addProperty("tunnelHostname", tunnelHostname);
		body.addProperty("mcVersion", mcVersion);
		return send("POST", "/api/presence", body, true, null).thenApply(ignored -> null);
	}

	/** Clean shutdown, so friends see the world drop at once instead of in five minutes. */
	public CompletableFuture<Void> clearPresence(String serverId) {
		return send("DELETE", "/api/presence/" + encode(serverId), null, true, null).thenApply(ignored -> null);
	}

	// -----------------------------------------------------------------------
	// Friends
	// -----------------------------------------------------------------------

	/** The whole friends screen in one round trip. Never includes tunnel hostnames. */
	public CompletableFuture<FriendsList> friends() {
		return send("GET", "/api/friends", null, true, FriendsList.class);
	}

	public CompletableFuture<RequestResult> requestFriend(String username) {
		JsonObject body = new JsonObject();
		body.addProperty("username", username);
		return send("POST", "/api/friends/request", body, true, RequestResult.class);
	}

	public CompletableFuture<Void> acceptFriend(UUID uuid) {
		return friendAction("accept", uuid);
	}

	public CompletableFuture<Void> rejectFriend(UUID uuid) {
		return friendAction("reject", uuid);
	}

	public CompletableFuture<Void> cancelFriendRequest(UUID uuid) {
		return friendAction("cancel", uuid);
	}

	private CompletableFuture<Void> friendAction(String action, UUID uuid) {
		JsonObject body = new JsonObject();
		body.addProperty("uuid", uuid.toString());
		return send("POST", "/api/friends/" + action, body, true, null).thenApply(ignored -> null);
	}

	public CompletableFuture<Void> removeFriend(UUID uuid) {
		return send("DELETE", "/api/friends/" + uuid, null, true, null).thenApply(ignored -> null);
	}

	/**
	 * Discovery. The only call that yields another player's tunnel hostname, and
	 * only for a confirmed friend whose world is online right now.
	 */
	public CompletableFuture<List<ServerInfo>> friendServers(UUID friendUuid) {
		return send("GET", "/api/friends/" + friendUuid + "/servers", null, true, ServersResponse.class)
				.thenApply(ServersResponse::servers);
	}

	// -----------------------------------------------------------------------
	// Transport
	// -----------------------------------------------------------------------

	/**
	 * Issues one request.
	 *
	 * @param authed    attach the stored session token; fails fast if there is none
	 * @param type      response type to parse, or {@code null} for calls whose body is ignored
	 */
	private <T> CompletableFuture<T> send(String method, String path, JsonObject body, boolean authed, Class<T> type) {
		HttpRequest request;
		try {
			request = build(method, path, body, authed);
		} catch (BackendException e) {
			return CompletableFuture.failedFuture(e);
		}

		return this.http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
				.handle((response, throwable) -> {
					if (throwable != null) {
						// Connection refused, DNS failure, timeout: the player is
						// offline or the backend is down. Same remedy either way.
						Throwable cause = throwable.getCause() == null ? throwable : throwable.getCause();
						Flaver.LOGGER.warn("{} {} failed: {}", method, path, cause.toString());
						throw new BackendException("network_error",
								"Could not reach the Flaver backend: " + cause.getMessage(), 0);
					}
					return parse(method, path, response, type);
				});
	}

	private HttpRequest build(String method, String path, JsonObject body, boolean authed) {
		HttpRequest.Builder builder = HttpRequest.newBuilder()
				.uri(URI.create(this.config.backendUrl() + path))
				.timeout(REQUEST_TIMEOUT)
				.header("Accept", "application/json")
				.header("User-Agent", "Flaver/" + Flaver.MOD_ID);

		if (authed) {
			String token = this.config.sessionToken();
			if (token == null || token.isBlank()) {
				throw new BackendException("no_session", "Not signed in to Flaver yet", 401);
			}
			builder.header("Authorization", "Bearer " + token);
		}

		if (body == null) {
			builder.method(method, HttpRequest.BodyPublishers.noBody());
		} else {
			builder.header("Content-Type", "application/json")
					.method(method, HttpRequest.BodyPublishers.ofString(GSON.toJson(body)));
		}

		return builder.build();
	}

	private <T> T parse(String method, String path, HttpResponse<String> response, Class<T> type) {
		int status = response.statusCode();
		String text = response.body();

		if (status < 200 || status >= 300) {
			String code = "http_" + status;
			String message = "Request failed with HTTP " + status;
			try {
				JsonObject error = GSON.fromJson(text, JsonObject.class);
				if (error != null) {
					if (error.has("error")) {
						code = error.get("error").getAsString();
					}
					if (error.has("message")) {
						message = error.get("message").getAsString();
					}
				}
			} catch (JsonParseException ignored) {
				// Not a JSON error body — keep the generic message.
			}
			Flaver.LOGGER.debug("{} {} -> {} {}", method, path, status, code);
			throw new BackendException(code, message, status);
		}

		if (type == null) {
			return null;
		}

		try {
			T parsed = GSON.fromJson(text, type);
			if (parsed == null) {
				throw new BackendException("bad_response", "Backend returned an empty body", status);
			}
			return parsed;
		} catch (JsonParseException e) {
			throw new BackendException("bad_response", "Could not parse the backend response", status);
		}
	}

	private static String encode(String segment) {
		return java.net.URLEncoder.encode(segment, java.nio.charset.StandardCharsets.UTF_8);
	}
}
