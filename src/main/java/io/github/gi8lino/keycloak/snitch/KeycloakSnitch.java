package io.github.gi8lino.keycloak.snitch;

import java.util.Set;
import org.keycloak.events.*;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.models.*;

/** Session listener that queues selected user events only after their transaction commits. */
record KeycloakSnitch(KeycloakSession session, Set<EventType> types, Delivery delivery) implements EventListenerProvider {
    @Override public void onEvent(Event event) {
        if (!types.contains(event.getType())) return;
        Payload snapshot = Payload.from(event);
        session.getTransactionManager().enlistAfterCompletion(new AbstractKeycloakTransaction() {
            @Override protected void commitImpl() { delivery.offer(snapshot); }
            @Override protected void rollbackImpl() {}
        });
    }
    /** Ignores administrative events. */
    @Override public void onEvent(AdminEvent event, boolean includeRepresentation) {}
    @Override public void close() {}
}
