# Keycloak Snitch

Keycloak Snitch forwards selected Keycloak user events to one HTTP webhook. Install the JAR on each Keycloak node and enable the listener per realm. The receiver can be any service that implements the protocol below. The repository includes a small Go development receiver for testing.

## Install

Source repository: https://github.com/gi8lino/keycloak-snitch.

Build with JDK 21 and Maven 3.9 (`make build`). Copy `target/keycloak-snitch-0.1.0.jar` into the Keycloak 26.8.1 `providers/` directory before running `bin/kc.sh build` for an optimized installation. Start Keycloak with the webhook URL and secret configured. The JAR uses libraries supplied by Keycloak. Rebuild and test before upgrading Keycloak, which classifies this listener SPI as internal.

The listener ID remains `snitch` to keep existing Keycloak realm and SPI settings compatible.

In each realm's admin console, open **Realm settings → Events → Event listeners**, add **snitch**, keep any other desired listeners, and save. Event database storage is optional. Only user events are supported. Admin events and arbitrary event details are excluded.

### Configuration

Snitch provides six configuration options. Set the corresponding environment variables on the **Keycloak container**. Keycloak passes their values to Snitch through its SPI configuration mechanism.

#### `webhook-url`

- **Environment:** `KC_SPI_EVENTS_LISTENER__SNITCH__WEBHOOK_URL`
- **Default:** Required
- **Description:** Absolute webhook URL, for example `https://receiver.example/events`. Use HTTPS in production. Redirects are disabled.

#### `webhook-secret`

- **Environment:** `KC_SPI_EVENTS_LISTENER__SNITCH__WEBHOOK_SECRET`
- **Default:** Required
- **Description:** High-entropy shared secret used to sign requests. Configure the same secret on the receiver.

#### `event-types`

- **Environment:** `KC_SPI_EVENTS_LISTENER__SNITCH__EVENT_TYPES`
- **Default:** `LOGIN,LOGIN_ERROR,LOGOUT`
- **Description:** Comma-separated list of Keycloak user event types to forward.

#### `connect-timeout-ms`

- **Environment:** `KC_SPI_EVENTS_LISTENER__SNITCH__CONNECT_TIMEOUT_MS`
- **Default:** `1000` (1 second)
- **Description:** HTTP connection timeout in milliseconds. Must be positive.

#### `request-timeout-ms`

- **Environment:** `KC_SPI_EVENTS_LISTENER__SNITCH__REQUEST_TIMEOUT_MS`
- **Default:** `2000` (2 seconds)
- **Description:** HTTP request timeout in milliseconds. Must be positive.

#### `queue-size`

- **Environment:** `KC_SPI_EVENTS_LISTENER__SNITCH__QUEUE_SIZE`
- **Default:** `256`
- **Description:** Maximum number of events waiting per Keycloak instance, plus one in flight. Must be positive.

#### Alternative: `keycloak.conf`

You can also configure Snitch through `keycloak.conf`. Prefix each option with `spi-events-listener--snitch--`.

For example:

```properties
spi-events-listener--snitch--webhook-url=https://receiver.example/events
```

#### Example receiver

The development receiver accepts two environment variables:

- **`WEBHOOK_SECRET`** — Shared secret used to verify webhook signatures.
- **`LISTEN_ADDR`** — HTTP listening address. Defaults to `127.0.0.1:8081`. Docker Compose sets it to `:8081` so Keycloak can reach the receiver.

Missing required settings or invalid configuration cause Keycloak startup to fail. However, the webhook endpoint does not need to be reachable for Keycloak to start or authenticate users.

## Event contract

One UTF-8 JSON object per POST, with Content-Type application/json. All keys are present. The last five fields are strings or JSON null when absent. The realm ID is the native Keycloak ID, which may differ from its display name.

```json
{
  "schemaVersion": 1,
  "id": "bb685023-a081-4423-be47-0d40fe4aa59c",
  "source": "keycloak",
  "type": "LOGIN_ERROR",
  "timestamp": "2026-11-05T14:30:00Z",
  "realmId": "example",
  "clientId": "application",
  "userId": null,
  "ipAddress": "203.0.113.42",
  "error": "invalid_user_credentials"
}
```

The event ID comes from Keycloak, with a generated UUID fallback. Use it for deduplication. The timestamp is the event time in UTC. Type and error are native Keycloak values. The IP comes directly from the event and depends on correct trusted reverse-proxy configuration in Keycloak. Keycloak Snitch never reads forwarding headers.

### Signature verification

1. Read the raw request body before decoding JSON. Read `X-Snitch-Timestamp` as decimal Unix seconds (delivery time). The example receiver accepts up to 300 seconds of clock skew in either direction.
2. Compute HMAC-SHA256 using the UTF-8 bytes of the shared secret. Sign the ASCII timestamp, one ASCII period, then the exact body bytes with no added newline.
3. The `X-Snitch-Signature` header is `v1=` followed by the lowercase hexadecimal digest. Decode and compare the digest in constant time. Only then decode the JSON.
4. Return a 2xx status to acknowledge receipt. The development receiver accepts up to 64 KiB and prints one compact verified JSON object per line on stdout. Operational errors go to stderr.

For the secret `key`, timestamp `1700000000` and body `{}`, the header is `v1=9d713ed406bb7076d4123f0dc2c39d2df5c654ed4b0cd56b52c8b4c940bd63ae`. Timestamp checks limit replay age. A production receiver must deduplicate event IDs to prevent replays inside the accepted window.

## Delivery and transactions

The listener filters by type and snapshots only the documented fields. It registers an after-completion hook on the listener session. Commit offers the snapshot to a bounded queue without waiting for space. Rollback discards it. Keycloak 26.8.1 normally dispatches errors in a separate transaction, so a failed login can produce a committed LOGIN_ERROR even if the login transaction rolls back. [Keycloak listener SPI](https://www.keycloak.org/docs-api/latest/javadocs/org/keycloak/events/EventListenerProvider.html) · [26.8.1 event dispatch](https://github.com/keycloak/keycloak/blob/26.8.1/server-spi-private/src/main/java/org/keycloak/events/EventBuilder.java)

One factory-owned worker per instance serializes, signs and posts events using a shared HTTP client. Authentication threads never perform network I/O. Delivery is best effort with one application-level send attempt. Overflow drops the newest event. Timeouts, network failures and non-2xx replies lose events. Shutdown interrupts active delivery and discards pending events. Warnings give the reason and are rate-limited. There is no persistence, retry queue, cross-node ordering or guarantee that a receiver processed an event after a timeout.

## Local development

Requires Docker with Compose. Local unit tests also use Maven, JDK 21 and Go 1.25+. The smoke test uses Python 3.

```sh
make run                            # build and start the two services
docker compose logs -f receiver      # separate terminal
```

Open http://localhost:8080/realms/keycloak-snitch-dev/account/ and try username **demo**, first with a wrong password and then with **dev-password**. Watch for LOGIN_ERROR and LOGIN. Admin console credentials are **dev-admin / dev-admin-password**. All credentials and the HTTP transport in Compose are for local development only.

```sh
docker compose stop receiver
# Sign out of the account console and sign back in to exercise a fresh login.
docker compose start receiver
make stop
```

The example receiver can also run independently: from `examples/receiver`, run `WEBHOOK_SECRET=dev-only-shared-secret go run .`. Its default listen address is 127.0.0.1:8081. Override with LISTEN_ADDR.

### Automated checks

```sh
make build    # installable JAR
make test     # Java and Go unit tests
make smoke    # disposable real Keycloak and receiver, port 18080
make check    # unit tests and smoke test
make clean    # remove Maven outputs
```

Override KC_PORT if a local port is occupied. The smoke test imports the realm and uses its dedicated direct-access-grant client to obtain real tokens, verifies failed and successful login events in receiver stdout, checks refresh filtering, then stops the receiver and obtains another token. It removes its own Compose project afterward. Keep the password grant confined to the development client.

## Installing the JAR from a Kubernetes PVC

Build the JAR with `make build`, or extract it from the **keycloak-snitch-provider** workflow artifact. Place `keycloak-snitch-0.1.0.jar` in the **`providers/` directory at the root of the PVC**. For example, if a staging pod mounts the claim at `/mnt/keycloak-snitch`, copy the JAR to `/mnt/keycloak-snitch/providers/keycloak-snitch-0.1.0.jar` and make it readable by the Keycloak container user.

Merge this fragment into your existing Keycloak Deployment or StatefulSet. Substitute your claim and container names. The single-file mount keeps the rest of the providers directory visible.

```yaml
spec:
  template:
    spec:
      volumes:
        - name: keycloak-snitch-provider
          persistentVolumeClaim:
            claimName: keycloak-snitch-plugins
            readOnly: true
      containers:
        - name: keycloak
          volumeMounts:
            - name: keycloak-snitch-provider
              subPath: providers/keycloak-snitch-0.1.0.jar
              mountPath: /opt/keycloak/providers/keycloak-snitch-0.1.0.jar
              readOnly: true
```

Populate the PVC before starting the pods. With `kc.sh start`, Keycloak can build with the mounted provider at startup and needs write access to its installation for that step. With `start --optimized`, include the matching provider during the image's `kc.sh build` step. Configure the webhook and enable `snitch` in each target realm. For an upgrade, stage the new versioned JAR, update both paths, and roll the pods.

## Kubernetes and security

Bake the provider into a version-matched Keycloak image before the optimized build. Supply the SPI options to every replica, inject the shared secret from a Kubernetes Secret, restrict egress to the intended HTTPS endpoint, and configure TLS trust in Keycloak's JVM. Coordinate secret changes with the receiver. Keep clocks synchronized. Restrict access to receiver logs because IDs and IP addresses may identify people. A slow receiver can exhaust the per-pod queue. Choose capacity within the pod memory budget and plan for event loss during outages and rolling updates. The example receiver has no persistent deduplication or production access controls.

## License

Licensed under the [Apache License, Version 2.0](LICENSE).
