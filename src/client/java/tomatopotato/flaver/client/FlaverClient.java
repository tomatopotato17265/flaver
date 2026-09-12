package tomatopotato.flaver.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import tomatopotato.flaver.Flaver;
import tomatopotato.flaver.backend.FlaverConfig;

public class FlaverClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// Touch the config early so a malformed file is reported at startup rather
		// than the first time a player opens the friends screen.
		FlaverConfig config = FlaverConfig.get();
		Flaver.LOGGER.info("Flaver using backend {}", config.backendUrl());

		// Sign in once the client is fully up: the user profile and session service
		// are not dependable during mod initialization.
		ClientLifecycleEvents.CLIENT_STARTED.register(client -> FlaverAuth.ensureAuthenticated());
	}
}
