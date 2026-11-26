package io.github.gi8lino.keycloak.snitch;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.keycloak.util.JsonSerialization;

/** Owns the bounded queue and HTTP client shared by listeners on this Keycloak instance. */
final class Delivery implements AutoCloseable {
    /** Header containing the delivery timestamp used for replay protection. */
    private static final String HEADER_TIMESTAMP = "X-Snitch-Timestamp";
    /** Header containing the versioned HMAC signature of the request. */
    private static final String HEADER_SIGNATURE = "X-Snitch-Signature";
    /** Identifies the first version of the request-signing protocol. */
    private static final String SIGNATURE_PREFIX = "v1=";

    /** Serializes network delivery on one shared background thread. */
    private static final int DELIVERY_WORKERS = 1;
    /** Unused idle timeout in seconds because the fixed core worker does not time out. */
    private static final long WORKER_KEEP_ALIVE_SECONDS = 0;
    /** Minimum interval in milliseconds between drop warnings during an outage. */
    private static final long WARNING_INTERVAL_MS = 1_000;
    /** Maximum seconds allowed for queued deliveries to finish before cancellation. */
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 3;
    /** Inclusive lower bound of accepted HTTP response status codes. */
    private static final int HTTP_SUCCESS_MIN = 200;
    /** Inclusive upper bound of accepted HTTP response status codes. */
    private static final int HTTP_SUCCESS_MAX = 299;
    /** Reports delivery failures without including event payloads or secrets. */
    private static final System.Logger LOG = System.getLogger("io.github.gi8lino.keycloak.snitch");

    /** Runs HTTP delivery tasks with a bounded queue and rejection instead of blocking. */
    private final ThreadPoolExecutor worker;
    /** Configured webhook destination; redirects are never followed. */
    private final URI endpoint;
    /** Shared HMAC key derived from the configured UTF-8 secret. */
    private final SecretKeySpec key;
    /** Reuses connections across deliveries for this provider factory. */
    private final HttpClient client;
    /** Maximum duration of an individual HTTP request. */
    private final Duration timeout;
    /** Wall-clock milliseconds of the last emitted warning, shared across callers. */
    private final AtomicLong lastWarning = new AtomicLong();

    /** Creates shared delivery resources with connection, request, and pending-queue limits. */
    Delivery(URI endpoint, String secret, Duration connect, Duration timeout, int capacity) {
        this.endpoint = endpoint;
        this.timeout = timeout;
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");

        this.client = HttpClient.newBuilder()
                .connectTimeout(connect)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        this.worker = new ThreadPoolExecutor(
                DELIVERY_WORKERS,
                DELIVERY_WORKERS,
                WORKER_KEEP_ALIVE_SECONDS,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(capacity),
                Thread.ofPlatform().daemon().name("keycloak-snitch-delivery").factory()
        );
    }

    /** Attempts to enqueue a snapshot without waiting for the receiver or queue space. */
    void offer(Payload event) {
        try {
            worker.execute(() -> send(event));
        } catch (RejectedExecutionException full) {
            warn(worker.isShutdown() ? "worker stopped" : "queue full");
        }
    }

    /** Attempts one signed POST on the worker and reports failures without retrying. */
    private void send(Payload event) {
        try {
            byte[] body = JsonSerialization.writeValueAsBytes(event);
            String timestamp = Long.toString(Instant.now().getEpochSecond());

            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header(HEADER_TIMESTAMP, timestamp)
                    .header(HEADER_SIGNATURE, signature(key, timestamp, body))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();

            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            if (status < HTTP_SUCCESS_MIN || status > HTTP_SUCCESS_MAX) {
                warn("receiver HTTP " + status);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            warn("shutdown");
        } catch (Exception failure) {
            warn(failure.getClass().getSimpleName());
        }
    }

    /** Signs the delivery timestamp, a period, and the exact request bytes. */
    static String signature(SecretKeySpec key, String timestamp, byte[] body) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(key);
        mac.update((timestamp + ".").getBytes(StandardCharsets.US_ASCII));

        return SIGNATURE_PREFIX + HexFormat.of().formatHex(mac.doFinal(body));
    }

    /** Rate-limits drop warnings so receiver outages do not flood Keycloak logs. */
    private void warn(String reason) {
        long now = System.currentTimeMillis();
        long previous = lastWarning.get();

        if (now - previous >= WARNING_INTERVAL_MS && lastWarning.compareAndSet(previous, now)) {
            LOG.log(System.Logger.Level.WARNING, "Keycloak Snitch dropped event(s): " + reason);
        }
    }

    /** Stops accepting events, briefly drains the queue, and shuts down HTTP resources. */
    @Override
    public void close() {
        worker.shutdown();

        try {
            if (!worker.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                stopPendingDeliveries();
            }
        } catch (InterruptedException interrupted) {
            stopPendingDeliveries();
            Thread.currentThread().interrupt();
        } finally {
            client.shutdownNow();
        }
    }

    /** Interrupts active delivery and discards queued work when graceful shutdown ends. */
    private void stopPendingDeliveries() {
        if (!worker.shutdownNow().isEmpty()) {
            warn("pending queue discarded at shutdown");
        }
    }
}
