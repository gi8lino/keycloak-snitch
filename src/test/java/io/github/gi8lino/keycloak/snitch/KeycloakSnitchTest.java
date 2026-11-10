package io.github.gi8lino.keycloak.snitch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.keycloak.events.Event;
import org.keycloak.events.EventListenerProviderFactory;
import org.keycloak.events.EventType;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakTransaction;
import org.keycloak.models.KeycloakTransactionManager;
import org.keycloak.util.JsonSerialization;

class KeycloakSnitchTest {
    private static final long SNAPSHOT_EVENT_TIME_MS = 123;
    private static final int EXPECTED_PAYLOAD_FIELDS = 10;
    private static final int AUTO_ASSIGNED_PORT = 0;
    private static final int DEFAULT_HTTP_BACKLOG = 0;
    private static final int IMMEDIATE_SHUTDOWN_DELAY_SECONDS = 0;
    private static final long NO_RESPONSE_BODY = -1;
    private static final int HTTP_FAILURE_STATUS = 500;
    private static final int HTTP_SUCCESS_STATUS = 204;
    private static final long SLOW_HANDLER_WAIT_SECONDS = 10;
    private static final long DELIVERY_CONNECT_TIMEOUT_SECONDS = 1;
    private static final long DELIVERY_REQUEST_TIMEOUT_MS = 750;
    private static final int DELIVERY_QUEUE_CAPACITY = 1;
    private static final long EVENT_WAIT_SECONDS = 3;
    private static final long NO_EXTRA_EVENT_WAIT_MS = 100;
    private static final long SHUTDOWN_OBSERVATION_MS = 150;

    @Test
    void discoversProvider() {
        assertTrue(ServiceLoader.load(EventListenerProviderFactory.class).stream()
                .anyMatch(provider -> provider.type() == KeycloakSnitchFactory.class
                        && provider.get().getId().equals("snitch")));
    }

    @Test
    void snapshotHasFixedFieldsAndPreservesNulls() throws Exception {
        Event event = new Event();
        event.setType(EventType.LOGIN_ERROR);
        event.setTime(SNAPSHOT_EVENT_TIME_MS);
        event.setDetails(Map.of("password", "private"));

        Payload snapshot = Payload.from(event);
        event.setUserId("later");

        var json = JsonSerialization.mapper.readTree(JsonSerialization.writeValueAsBytes(snapshot));
        assertEquals(EXPECTED_PAYLOAD_FIELDS, json.size());
        assertTrue(json.get("userId").isNull());
        assertTrue(json.get("ipAddress").isNull());
        assertEquals("1970-01-01T00:00:00.123Z", json.get("timestamp").asText());
        assertEquals("LOGIN_ERROR", json.get("type").asText());
        UUID.fromString(json.get("id").asText());

        event.setId("native");
        assertEquals("native", Payload.from(event).id());
    }

    @Test
    void signatureMatchesIndependentVector() throws Exception {
        var key = new SecretKeySpec(new byte[]{'k', 'e', 'y'}, "HmacSHA256");

        assertEquals(
                "v1=9d713ed406bb7076d4123f0dc2c39d2df5c654ed4b0cd56b52c8b4c940bd63ae",
                Delivery.signature(key, "1700000000", new byte[]{'{', '}'})
        );
    }

    @Test
    void boundedQueueDropsExcessAndContinuesAfterTimeoutAnd500() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var received = new LinkedBlockingQueue<String>();

        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", AUTO_ASSIGNED_PORT), DEFAULT_HTTP_BACKLOG);
        var handlers = Executors.newCachedThreadPool();
        server.setExecutor(handlers);
        server.createContext("/", exchange -> {
            try (exchange) {
                String id = JsonSerialization.mapper.readTree(exchange.getRequestBody()).get("id").asText();
                received.add(id);

                if (id.equals("slow")) {
                    started.countDown();
                    try {
                        release.await(SLOW_HANDLER_WAIT_SECONDS, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }

                exchange.sendResponseHeaders(
                        id.equals("failure") ? HTTP_FAILURE_STATUS : HTTP_SUCCESS_STATUS,
                        NO_RESPONSE_BODY
                );
            }
        });
        server.start();

        var delivery = new Delivery(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                "secret",
                Duration.ofSeconds(DELIVERY_CONNECT_TIMEOUT_SECONDS),
                Duration.ofMillis(DELIVERY_REQUEST_TIMEOUT_MS),
                DELIVERY_QUEUE_CAPACITY
        );

        try {
            delivery.offer(payload("slow"));
            assertTrue(started.await(EVENT_WAIT_SECONDS, TimeUnit.SECONDS));

            delivery.offer(payload("failure"));
            delivery.offer(payload("overflow"));
            assertEquals("slow", received.poll());
            assertEquals("failure", received.poll(EVENT_WAIT_SECONDS, TimeUnit.SECONDS));

            delivery.offer(payload("next"));
            assertEquals("next", received.poll(EVENT_WAIT_SECONDS, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            delivery.close();
            server.stop(IMMEDIATE_SHUTDOWN_DELAY_SECONDS);
            handlers.shutdownNow();
        }

        delivery.offer(payload("after-close"));
        assertTrue(received.isEmpty());
    }

    @Test
    void forwardsOnlyCommittedSelectedEvents() throws Exception {
        var received = new LinkedBlockingQueue<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", AUTO_ASSIGNED_PORT), DEFAULT_HTTP_BACKLOG);
        server.createContext("/", exchange -> {
            try (exchange) {
                String id = JsonSerialization.mapper.readTree(exchange.getRequestBody()).get("id").asText();
                received.add(id);
                exchange.sendResponseHeaders(HTTP_SUCCESS_STATUS, NO_RESPONSE_BODY);
            }
        });
        server.start();

        var transaction = new AtomicReference<KeycloakTransaction>();
        KeycloakSession session = sessionCapturing(transaction);
        var delivery = new Delivery(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                "secret",
                Duration.ofSeconds(DELIVERY_CONNECT_TIMEOUT_SECONDS),
                Duration.ofSeconds(EVENT_WAIT_SECONDS),
                DELIVERY_QUEUE_CAPACITY
        );

        try {
            var listener = new KeycloakSnitch(session, Set.of(EventType.LOGIN_ERROR), delivery);

            listener.onEvent(event("filtered", EventType.LOGIN));
            assertNull(transaction.get());

            listener.onEvent(event("rolled-back", EventType.LOGIN_ERROR));
            KeycloakTransaction rollback = transaction.getAndSet(null);
            assertNotNull(rollback);
            rollback.begin();
            rollback.rollback();

            listener.onEvent(event("committed", EventType.LOGIN_ERROR));
            KeycloakTransaction commit = transaction.getAndSet(null);
            assertNotNull(commit);
            commit.begin();
            commit.commit();

            assertEquals("committed", received.poll(EVENT_WAIT_SECONDS, TimeUnit.SECONDS));
            assertNull(received.poll(NO_EXTRA_EVENT_WAIT_MS, TimeUnit.MILLISECONDS));
        } finally {
            delivery.close();
            server.stop(IMMEDIATE_SHUTDOWN_DELAY_SECONDS);
        }
    }

    @Test
    void shutdownDrainsQueuedEvents() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var received = new LinkedBlockingQueue<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", AUTO_ASSIGNED_PORT), DEFAULT_HTTP_BACKLOG);
        var handlers = Executors.newCachedThreadPool();
        server.setExecutor(handlers);
        server.createContext("/", exchange -> {
            try (exchange) {
                String id = JsonSerialization.mapper.readTree(exchange.getRequestBody()).get("id").asText();
                received.add(id);

                if (id.equals("first")) {
                    started.countDown();
                    try {
                        release.await(EVENT_WAIT_SECONDS, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }

                exchange.sendResponseHeaders(HTTP_SUCCESS_STATUS, NO_RESPONSE_BODY);
            }
        });
        server.start();

        var delivery = new Delivery(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                "secret",
                Duration.ofSeconds(DELIVERY_CONNECT_TIMEOUT_SECONDS),
                Duration.ofSeconds(EVENT_WAIT_SECONDS),
                DELIVERY_QUEUE_CAPACITY
        );

        try {
            delivery.offer(payload("first"));
            assertTrue(started.await(EVENT_WAIT_SECONDS, TimeUnit.SECONDS));
            delivery.offer(payload("second"));

            var closingStarted = new CountDownLatch(1);
            Thread closing = Thread.ofPlatform().start(() -> {
                closingStarted.countDown();
                delivery.close();
            });
            try {
                assertTrue(closingStarted.await(EVENT_WAIT_SECONDS, TimeUnit.SECONDS));
                assertEquals("first", received.poll(EVENT_WAIT_SECONDS, TimeUnit.SECONDS));
                assertNull(received.poll(SHUTDOWN_OBSERVATION_MS, TimeUnit.MILLISECONDS));
                assertTrue(closing.isAlive(), "shutdown should wait for deliveries");

                release.countDown();
                assertEquals("second", received.poll(EVENT_WAIT_SECONDS, TimeUnit.SECONDS));
                closing.join(TimeUnit.SECONDS.toMillis(EVENT_WAIT_SECONDS + 1));
                assertFalse(closing.isAlive(), "shutdown did not finish");
            } finally {
                release.countDown();
                closing.join(TimeUnit.SECONDS.toMillis(EVENT_WAIT_SECONDS + 1));
            }
        } finally {
            release.countDown();
            delivery.close();
            server.stop(IMMEDIATE_SHUTDOWN_DELAY_SECONDS);
            handlers.shutdownNow();
        }
    }

    private static KeycloakSession sessionCapturing(AtomicReference<KeycloakTransaction> captured) {
        KeycloakTransactionManager manager = (KeycloakTransactionManager) Proxy.newProxyInstance(
                KeycloakTransactionManager.class.getClassLoader(),
                new Class<?>[]{KeycloakTransactionManager.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("enlistAfterCompletion")) {
                        captured.set((KeycloakTransaction) args[0]);
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                }
        );

        return (KeycloakSession) Proxy.newProxyInstance(
                KeycloakSession.class.getClassLoader(),
                new Class<?>[]{KeycloakSession.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getTransactionManager")) {
                        return manager;
                    }
                    throw new UnsupportedOperationException(method.getName());
                }
        );
    }

    private static Event event(String id, EventType type) {
        Event event = new Event();
        event.setId(id);
        event.setType(type);
        return event;
    }

    private static Payload payload(String id) {
        Event event = new Event();
        event.setType(EventType.LOGIN);
        event.setId(id);

        return Payload.from(event);
    }
}
