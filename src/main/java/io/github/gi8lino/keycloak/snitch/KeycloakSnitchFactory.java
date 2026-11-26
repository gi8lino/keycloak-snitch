package io.github.gi8lino.keycloak.snitch;

import java.net.URI;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;
import org.keycloak.Config;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventListenerProviderFactory;
import org.keycloak.events.EventType;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

/** Configures the shared delivery worker and creates a listener for each Keycloak session. */
public final class KeycloakSnitchFactory implements EventListenerProviderFactory {
    /** Forwards only failed logins unless other native event types are configured. */
    private static final String DEFAULT_EVENT_TYPES = "LOGIN_ERROR";
    /** Default connection-establishment timeout in milliseconds. */
    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 1_000;
    /** Default HTTP request timeout in milliseconds. */
    private static final int DEFAULT_REQUEST_TIMEOUT_MS = 2_000;
    /** Default number of events waiting for the shared delivery worker. */
    private static final int DEFAULT_QUEUE_SIZE = 256;

    /** Factory-owned resources shared by all session listeners. */
    private Delivery delivery;
    /** Configured native user-event types selected for forwarding. */
    private Set<EventType> types;

    /** Returns the SPI provider name used in realm and server configuration. */
    @Override
    public String getId() {
        return "snitch";
    }

    /** Validates native SPI settings and initializes the shared delivery resources. */
    @Override
    public void init(Config.Scope config) {
        String url = config.get("webhook-url");
        String secret = config.get("webhook-secret");

        URI endpoint;
        try {
            endpoint = URI.create(url);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Configure a valid webhook-url");
        }

        if (!isWebhookEndpoint(endpoint)) {
            throw new IllegalArgumentException("Configure an HTTP(S) webhook-url without credentials or fragment");
        }
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("Configure webhook-secret");
        }

        types = parseEventTypes(config.get("event-types", DEFAULT_EVENT_TYPES));

        delivery = new Delivery(
                endpoint,
                secret,
                Duration.ofMillis(positive(config, "connect-timeout-ms", DEFAULT_CONNECT_TIMEOUT_MS)),
                Duration.ofMillis(positive(config, "request-timeout-ms", DEFAULT_REQUEST_TIMEOUT_MS)),
                positive(config, "queue-size", DEFAULT_QUEUE_SIZE)
        );
    }

    /** Parses comma-separated native event names and rejects unknown or empty entries. */
    private static Set<EventType> parseEventTypes(String configured) {
        Set<EventType> selected = EnumSet.noneOf(EventType.class);

        for (String entry : configured.split(",", -1)) {
            String name = entry.trim();
            if (name.isEmpty()) {
                throw new IllegalArgumentException("event-types must not contain empty entries");
            }

            try {
                selected.add(EventType.valueOf(name));
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("Invalid event-types value: " + name, invalid);
            }
        }

        return selected;
    }

    /** Accepts absolute HTTP(S) destinations without embedded credentials or fragments. */
    private static boolean isWebhookEndpoint(URI endpoint) {
        return endpoint.getHost() != null
                && endpoint.getUserInfo() == null
                && endpoint.getFragment() == null
                && Set.of("http", "https").contains(endpoint.getScheme());
    }

    /** Reads a positive integer setting, using its fallback when omitted. */
    private static int positive(Config.Scope config, String key, int fallback) {
        int value = config.getInt(key, fallback);
        if (value <= 0) {
            throw new IllegalArgumentException(key + " must be positive");
        }
        return value;
    }

    /** Creates a session-scoped listener using the factory-owned worker. */
    @Override
    public EventListenerProvider create(KeycloakSession session) {
        return new KeycloakSnitch(session, types, delivery);
    }

    /** Requires no additional initialization after the Keycloak session factory starts. */
    @Override
    public void postInit(KeycloakSessionFactory factory) {
    }

    /** Releases shared delivery resources when Keycloak closes this provider factory. */
    @Override
    public void close() {
        if (delivery != null) {
            delivery.close();
        }
    }
}
