package io.github.gi8lino.keycloak.snitch;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.keycloak.events.*;
import org.keycloak.util.JsonSerialization;
import static org.junit.jupiter.api.Assertions.*;

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

    @Test void discoversProvider() {
        assertTrue(ServiceLoader.load(EventListenerProviderFactory.class).stream()
                .anyMatch(p -> p.type() == KeycloakSnitchFactory.class && p.get().getId().equals("snitch")));
    }

    @Test void snapshotHasFixedFieldsAndPreservesNulls() throws Exception {
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

    @Test void signatureMatchesIndependentVector() throws Exception {
        var key = new SecretKeySpec(new byte[]{'k','e','y'}, "HmacSHA256");
        assertEquals("v1=9d713ed406bb7076d4123f0dc2c39d2df5c654ed4b0cd56b52c8b4c940bd63ae",
                Delivery.signature(key, "1700000000", new byte[]{'{','}'}));
    }

    @Test void boundedQueueDropsExcessAndContinuesAfterTimeoutAnd500() throws Exception {
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
                    try { release.await(SLOW_HANDLER_WAIT_SECONDS, TimeUnit.SECONDS); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                exchange.sendResponseHeaders(id.equals("failure") ? HTTP_FAILURE_STATUS : HTTP_SUCCESS_STATUS, NO_RESPONSE_BODY);
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

    private static Payload payload(String id) {
        Event event = new Event();
        event.setType(EventType.LOGIN);
        event.setId(id);
        return Payload.from(event);
    }
}
