package io.github.gi8lino.keycloak.snitch;

import java.util.Set;
import org.keycloak.events.Event;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventType;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.models.AbstractKeycloakTransaction;
import org.keycloak.models.KeycloakSession;

/**
 * Session listener that queues selected events only after their transaction commits.
 *
 * @param session active Keycloak session providing transaction completion callbacks
 * @param types native user-event types selected by the provider configuration
 * @param adminEvents whether administrative events are forwarded
 * @param delivery factory-owned asynchronous delivery resources
 */
record KeycloakSnitch(KeycloakSession session, Set<EventType> types, boolean adminEvents, Delivery delivery)
        implements EventListenerProvider {

    /** Snapshots selected events and defers queue submission until transaction commit. */
    @Override
    public void onEvent(Event event) {
        if (!types.contains(event.getType())) {
            return;
        }

        afterCommit(Payload.from(event));
    }

    /** Queues administrative metadata after commit when enabled. */
    @Override
    public void onEvent(AdminEvent event, boolean includeRepresentation) {
        if (adminEvents) {
            afterCommit(Payload.from(event));
        }
    }

    /** Submits detached snapshots only after successful transaction completion. */
    private void afterCommit(Payload snapshot) {
        session.getTransactionManager().enlistAfterCompletion(new AbstractKeycloakTransaction() {
            /** Enqueues the snapshot after commit without waiting for network delivery. */
            @Override
            protected void commitImpl() {
                delivery.offer(snapshot);
            }

            /** Discards the snapshot when the transaction rolls back. */
            @Override
            protected void rollbackImpl() {
            }
        });
    }

    /** Leaves shared delivery resources open; the provider factory owns their lifecycle. */
    @Override
    public void close() {
    }
}
