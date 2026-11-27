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
    /** Stable SPI identifier used in realm and server configuration. */
    private static final String PROVIDER_ID = "snitch";
    /** SPI setting for the HTTP endpoint receiving selected events. */
    private static final String CONFIG_WEBHOOK_URL = "webhook-url";
    /** SPI setting for the shared request-signing secret. */
    private static final String CONFIG_WEBHOOK_SECRET = "webhook-secret";
    /** SPI setting for comma-separated native user-event names. */
    private static final String CONFIG_EVENT_TYPES = "event-types";
    /** Enables forwarding administrative event metadata. */
    private static final String CONFIG_ADMIN_EVENTS = "admin-events";
    /** SPI setting for the connection timeout in milliseconds. */
    private static final String CONFIG_CONNECT_TIMEOUT_MS = "connect-timeout-ms";
    /** SPI setting for the HTTP request timeout in milliseconds. */
    private static final String CONFIG_REQUEST_TIMEOUT_MS = "request-timeout-ms";
    /** SPI setting limiting the number of pending events. */
    private static final String CONFIG_QUEUE_SIZE = "queue-size";

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
    /** Whether administrative events are forwarded. */
    private boolean adminEvents;

    /** Returns the SPI provider name used in realm and server configuration. */
    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    /** Validates native SPI settings and initializes the shared delivery resources. */
    @Override
    public void init(Config.Scope config) {
        String url = config.get(CONFIG_WEBHOOK_URL);
        String secret = config.get(CONFIG_WEBHOOK_SECRET);

        URI endpoint;
        try {
            endpoint = URI.create(url);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Configure a valid " + CONFIG_WEBHOOK_URL);
        }

        if (!isWebhookEndpoint(endpoint)) {
            throw new IllegalArgumentException("Configure an HTTP(S) " + CONFIG_WEBHOOK_URL + " without credentials or fragment");
        }
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("Configure " + CONFIG_WEBHOOK_SECRET);
        }

        types = parseEventTypes(config.get(CONFIG_EVENT_TYPES, DEFAULT_EVENT_TYPES));
        adminEvents = parseAdminEvents(config.get(CONFIG_ADMIN_EVENTS, "false"));
        if (types.isEmpty() && !adminEvents) {
            throw new IllegalArgumentException("Configure at least one user event or enable admin-events");
        }

        delivery = new Delivery(
                endpoint,
                secret,
                Duration.ofMillis(positive(config, CONFIG_CONNECT_TIMEOUT_MS, DEFAULT_CONNECT_TIMEOUT_MS)),
                Duration.ofMillis(positive(config, CONFIG_REQUEST_TIMEOUT_MS, DEFAULT_REQUEST_TIMEOUT_MS)),
                positive(config, CONFIG_QUEUE_SIZE, DEFAULT_QUEUE_SIZE)
        );
    }

    /** Parses comma-separated native event names and rejects unknown or empty entries. */
    private static Set<EventType> parseEventTypes(String configured) {
        if (configured.isBlank()) {
            return Set.of();
        }

        Set<EventType> selected = EnumSet.noneOf(EventType.class);

        for (String entry : configured.split(",", -1)) {
            String name = entry.trim();
            if (name.isEmpty()) {
                throw new IllegalArgumentException(CONFIG_EVENT_TYPES + " must not contain empty entries");
            }

            try {
                selected.add(EventType.valueOf(name));
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("Invalid " + CONFIG_EVENT_TYPES + " value: " + name, invalid);
            }
        }

        return Set.copyOf(selected);
    }

    /** Parses the administrative event opt-in. */
    private static boolean parseAdminEvents(String configured) {
        return switch (configured) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException(CONFIG_ADMIN_EVENTS + " must be true or false");
        };
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
        return new KeycloakSnitch(session, types, adminEvents, delivery);
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
