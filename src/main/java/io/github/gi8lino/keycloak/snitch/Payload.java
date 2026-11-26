package io.github.gi8lino.keycloak.snitch;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;
import org.keycloak.events.Event;

/**
 * Immutable snapshot of the fields in a version 1 webhook.
 *
 * @param schemaVersion version of the JSON event contract
 * @param id native event identifier, or a generated UUID when unavailable
 * @param source event producer identifier, always keycloak
 * @param type unchanged native Keycloak user-event type
 * @param timestamp event time formatted as a UTC ISO-8601 instant
 * @param realmId native realm identifier, or null when absent
 * @param clientId client identifier reported by Keycloak, or null when absent
 * @param userId native user identifier, or null when absent
 * @param ipAddress client IP reported by Keycloak, without header-based inference
 * @param error native event error code, or null when absent
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
record Payload(
        int schemaVersion,
        String id,
        String source,
        String type,
        String timestamp,
        String realmId,
        String clientId,
        String userId,
        String ipAddress,
        String error
) {
    /** Version of the stable JSON event contract sent to webhook receivers. */
    private static final int SCHEMA_VERSION = 1;

    /** Copies the event while its Keycloak session is still active. */
    static Payload from(Event event) {
        String id = event.getId();
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }

        String timestamp = Instant.ofEpochMilli(event.getTime()).toString();

        return new Payload(
                SCHEMA_VERSION,
                id,
                "keycloak",
                event.getType().name(),
                timestamp,
                event.getRealmId(),
                event.getClientId(),
                event.getUserId(),
                event.getIpAddress(),
                event.getError()
        );
    }
}
