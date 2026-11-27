package io.github.gi8lino.keycloak.snitch;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;
import org.keycloak.events.Event;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.events.admin.AuthDetails;

/**
 * Immutable envelope containing exactly one Keycloak event category.
 *
 * @param schemaVersion version of the JSON event contract
 * @param id native event identifier or generated UUID
 * @param source event producer, always keycloak
 * @param kind event category, user or admin
 * @param timestamp event time as a UTC ISO-8601 instant
 * @param user native user event data, absent for administrative events
 * @param admin administrative metadata, absent for user events
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
record Payload(
        int schemaVersion,
        String id,
        String source,
        String kind,
        String timestamp,
        UserEvent user,
        AdministrativeEvent admin
) {
    /** Rejects inconsistent event categories at construction time. */
    Payload {
        if (!validKind(kind, user, admin)) {
            throw new IllegalArgumentException("Event category does not match payload");
        }
    }

    private static final String SOURCE = "keycloak";
    private static final String KIND_USER = "user";
    private static final String KIND_ADMIN = "admin";

    /** Checks that exactly one category matches the payload. */
    private static boolean validKind(String kind, UserEvent user, AdministrativeEvent admin) {
        return switch (kind) {
            case KIND_USER -> user != null && admin == null;
            case KIND_ADMIN -> admin != null && user == null;
            case null, default -> false;
        };
    }

    /** Version of the JSON event contract. */
    private static final int SCHEMA_VERSION = 2;

    /**
     * Native user event snapshot.
     * @param type native event name
     * @param realmId event realm
     * @param clientId client identifier
     * @param userId associated user identifier
     * @param ipAddress address reported by Keycloak
     * @param error native error code
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    record UserEvent(
            String type,
            String realmId,
            String clientId,
            String userId,
            String ipAddress,
            String error
    ) {
        /** Snapshots native user event metadata. */
        static UserEvent from(Event event) {
            return new UserEvent(
                    event.getType().name(),
                    event.getRealmId(),
                    event.getClientId(),
                    event.getUserId(),
                    event.getIpAddress(),
                    event.getError()
            );
        }
    }

    /**
     * Administrative operation snapshot without resource representations.
     * @param realmId affected realm
     * @param operationType native operation
     * @param resourceType affected resource category
     * @param resourcePath affected resource path
     * @param actor acting administrator, when available
     * @param error native error code
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    record AdministrativeEvent(
            String realmId,
            String operationType,
            String resourceType,
            String resourcePath,
            Actor actor,
            String error
    ) {
        /** Snapshots native administrative metadata. */
        static AdministrativeEvent from(AdminEvent event) {
            return new AdministrativeEvent(
                    event.getRealmId(),
                    event.getOperationType() == null ? null : event.getOperationType().name(),
                    event.getResourceTypeAsString(),
                    event.getResourcePath(),
                    Actor.from(event.getAuthDetails()),
                    event.getError()
            );
        }
    }

    /**
     * Administrator identity snapshot.
     * @param realmId authentication realm
     * @param clientId native client identifier
     * @param userId administrator identifier
     * @param ipAddress address reported by Keycloak
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    record Actor(
            String realmId,
            String clientId,
            String userId,
            String ipAddress
    ) {
        /** Copies the authentication details, when provided by Keycloak. */
        static Actor from(AuthDetails auth) {
            if (auth == null) {
                return null;
            }
            return new Actor(auth.getRealmId(), auth.getClientId(), auth.getUserId(), auth.getIpAddress());
        }
    }

    /** Copies a user event while its Keycloak session is active. */
    static Payload from(Event event) {
        return new Payload( SCHEMA_VERSION, eventId(event.getId()),
                SOURCE, KIND_USER,
                Instant.ofEpochMilli(event.getTime()).toString(), UserEvent.from(event), null);
    }

    /** Copies an administrative event while its Keycloak session is active. */
    static Payload from(AdminEvent event) {
        return new Payload(SCHEMA_VERSION, eventId(event.getId()),
                SOURCE,
                KIND_ADMIN,
                Instant.ofEpochMilli(event.getTime()).toString(), null, AdministrativeEvent.from(event));
    }

    /** Preserves native IDs and generates an ID when missing. */
    private static String eventId(String id) {
        return id == null || id.isBlank() ? UUID.randomUUID().toString() : id;
    }
}
