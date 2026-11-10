package io.github.gi8lino.keycloak.snitch;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;
import org.keycloak.events.Event;

/** Immutable snapshot of the fields in a version 1 webhook. */
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
