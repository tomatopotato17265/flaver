package tomatopotato.flaver.backend;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import net.fabricmc.loader.api.FabricLoader;
import tomatopotato.flaver.Flaver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * Persistent mod settings, stored at {@code config/flaver.json}.
 *
 * <p>Holds the backend URL and the session token from the last successful login,
 * so a player authenticates once rather than on every launch.
 *
 * <p>Read and written from both the render thread and network threads, so every
 * accessor is synchronized.
 */
public final class FlaverConfig {
	public static final String DEFAULT_BACKEND_URL = "https://flaver-backend.tomatopotato17265.workers.dev";

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String FILE_NAME = "flaver.json";

	private static FlaverConfig instance;

	/** Mutable on-disk shape. Fields are package-private so Gson can populate them. */
	private String backendUrl = DEFAULT_BACKEND_URL;
	private String sessionToken;
	private String playerUuid;
	private String playerName;

	private transient Path path;

	private FlaverConfig() {
	}

	public static synchronized FlaverConfig get() {
		if (instance == null) {
			instance = load(FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME));
		}
		return instance;
	}

	private static FlaverConfig load(Path path) {
		FlaverConfig config = null;

		if (Files.exists(path)) {
			try {
				String json = Files.readString(path, StandardCharsets.UTF_8);
				config = GSON.fromJson(json, FlaverConfig.class);
			} catch (IOException | JsonSyntaxException e) {
				// A corrupt config should not stop the game from starting; losing the
				// cached token only costs one extra login.
				Flaver.LOGGER.warn("Could not read {}, starting from defaults", path, e);
			}
		}

		if (config == null) {
			config = new FlaverConfig();
		}
		if (config.backendUrl == null || config.backendUrl.isBlank()) {
			config.backendUrl = DEFAULT_BACKEND_URL;
		}
		// Trailing slashes would produce "…/api//me" once joined with a path.
		while (config.backendUrl.endsWith("/")) {
			config.backendUrl = config.backendUrl.substring(0, config.backendUrl.length() - 1);
		}

		config.path = path;
		return config;
	}

	public synchronized void save() {
		try {
			Files.createDirectories(this.path.getParent());
			// Write beside the target and move into place, so an interrupted save
			// cannot leave a truncated config behind.
			Path tmp = this.path.resolveSibling(FILE_NAME + ".tmp");
			Files.writeString(tmp, GSON.toJson(this), StandardCharsets.UTF_8);
			Files.move(tmp, this.path, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			Flaver.LOGGER.error("Could not write {}", this.path, e);
		}
	}

	public synchronized String backendUrl() {
		return this.backendUrl;
	}

	public synchronized String sessionToken() {
		return this.sessionToken;
	}

	public synchronized boolean hasSessionToken() {
		return this.sessionToken != null && !this.sessionToken.isBlank();
	}

	public synchronized UUID playerUuid() {
		try {
			return this.playerUuid == null ? null : UUID.fromString(this.playerUuid);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	public synchronized String playerName() {
		return this.playerName;
	}

	/** Records a successful login and persists it. */
	public synchronized void setSession(String token, UUID uuid, String name) {
		this.sessionToken = token;
		this.playerUuid = uuid == null ? null : uuid.toString();
		this.playerName = name;
		save();
	}

	/** Forgets the cached token after the backend rejects it. */
	public synchronized void clearSession() {
		this.sessionToken = null;
		save();
	}
}
