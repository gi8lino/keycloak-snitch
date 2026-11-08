# Keycloak Snitch

Keycloak Snitch sends Keycloak login events to an HTTP webhook. It runs as a Keycloak event listener and works with receivers such as [Keycloak Warden](https://github.com/gi8lino/keycloak-warden).

## Try it locally

Use the **E2E Docker Compose stack** to start Keycloak with Snitch and
[Hookglass](https://github.com/gi8lino/hookglass), which validates HMAC-signed
webhooks before logging them:

```sh
make e2e-up       # Build and start Keycloak and Hookglass; print the realm URL
make e2e-logs     # Follow Keycloak and verified webhook logs
make e2e-test     # Reset and verify real logins, token refresh, and an outage
make e2e-down     # Stop the stack
```

Open the [E2E demo account](http://127.0.0.1:18083/realms/keycloak-snitch-e2e/account/)
(or the account URL printed by `make e2e-up`) and sign in as `demo`:

- A wrong password produces `LOGIN_ERROR`.
- Password `dev-password` produces `LOGIN`.

The test realm, the `smoke` OAuth client, and the `snitch` listener are defined in
[`examples/e2e/realm/keycloak-snitch-e2e.json`](examples/e2e/realm/keycloak-snitch-e2e.json).
The Keycloak admin console is at
[http://127.0.0.1:18083/admin/master/console/](http://127.0.0.1:18083/admin/master/console/)
with `dev-admin` / `dev-admin-password`. Override the host port with
`make e2e-up E2E_KC_PORT=18183`.

See the [E2E guide](examples/e2e/README.md) for the full workflow. These are
**development-only** credentials and HTTP endpoints; do not use them in production.

## Install

1. Download the JAR from [Releases](https://github.com/gi8lino/keycloak-snitch/releases) or build it with `make build` (JDK 21 and Maven required).
2. Copy `keycloak-snitch-<version>.jar` into Keycloak's `/opt/keycloak/providers/` directory on **every Keycloak node**.
3. For an optimized installation, run `bin/kc.sh build` with the JAR in place before starting Keycloak.
4. In each realm, go to **Realm settings → Events → Event listeners**, add `snitch`, and save.

Use a Snitch build compatible with your Keycloak version. The event listener SPI is internal to Keycloak and may change between releases. Snitch forwards user events, not admin events.

## Configuration

Set the following environment variables **on Keycloak**:

```dotenv
KC_SPI_EVENTS_LISTENER__SNITCH__WEBHOOK_URL=https://warden.example.com/events
KC_SPI_EVENTS_LISTENER__SNITCH__WEBHOOK_SECRET=replace-with-a-strong-shared-secret
KC_SPI_EVENTS_LISTENER__SNITCH__EVENT_TYPES=LOGIN_ERROR
```

The webhook URL and shared secret are required. Configure the same secret on the receiver. HTTPS is recommended for production; redirects are not followed.

Optional settings (defaults in parentheses):

- `KC_SPI_EVENTS_LISTENER__SNITCH__EVENT_TYPES` — Events to forward (`LOGIN,LOGIN_ERROR,LOGOUT`).
- `KC_SPI_EVENTS_LISTENER__SNITCH__CONNECT_TIMEOUT_MS` — Connection timeout (`1000` ms).
- `KC_SPI_EVENTS_LISTENER__SNITCH__REQUEST_TIMEOUT_MS` — Request timeout (`2000` ms).
- `KC_SPI_EVENTS_LISTENER__SNITCH__QUEUE_SIZE` — Pending events per Keycloak instance (`256`, plus one in flight).

Timeouts and queue size must be positive. You can also use `keycloak.conf` with the `spi-events-listener--snitch--` prefix, for example:

```properties
spi-events-listener--snitch--webhook-url=https://warden.example.com/events
```

A missing or invalid required setting prevents Keycloak from starting. A temporarily unavailable receiver does not prevent Keycloak logins.

## Webhook format

Snitch sends one JSON object per HTTP POST (`Content-Type: application/json`):

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

All fields are present; `realmId`, `clientId`, `userId`, `ipAddress`, and `error` may be `null`. The `id` identifies an event and can be used for deduplication. The IP address is provided by Keycloak, so configure trusted proxies correctly if Keycloak runs behind one.

### Verify the signature

Every request includes:

- `X-Snitch-Timestamp` — Unix delivery time in seconds.
- `X-Snitch-Signature` — `v1=` followed by a lowercase hexadecimal HMAC-SHA256 digest.

Calculate the HMAC using the shared secret and the **raw request body** (before decoding JSON):

```text
HMAC-SHA256(secret, timestamp + "." + rawBody)
```

Compare the decoded digest in constant time, reject old timestamps, and deduplicate event IDs. The included Hookglass configuration accepts a five-minute timestamp window and bodies up to 64 KiB. Return a `2xx` HTTP status to acknowledge delivery.

## Delivery behavior

Snitch queues events after their Keycloak transaction commits and sends them asynchronously, so webhook latency does not block logins. Rolled-back events are discarded.

**Delivery is best effort:** there is no persistent queue or retry. A full queue, timeout, receiver error, or shutdown can lose an event. Monitor Keycloak warnings and size the queue for your load.

## Kubernetes

The recommended approach is to bake the JAR into a Keycloak image matching your Keycloak version, before the optimized build.

Alternatively, mount the JAR from a PVC into `/opt/keycloak/providers/` using a single-file `subPath` mount. Put the file at `providers/keycloak-snitch-<version>.jar` on the PVC, then mount it at the same path under `/opt/keycloak/providers/`. The file must be readable by Keycloak, and optimized images must be built with the provider installed.

Store the shared secret in a Kubernetes Secret, use HTTPS for external webhooks, and configure the same settings on every Keycloak replica.

## Development

```sh
make build                  # Build and test the JAR
make test                   # Java unit tests
make check                  # Java tests and E2E shell syntax
make verify                 # All local checks (no Docker)
make e2e-test               # Real Keycloak / Hookglass integration test
make e2e-logs               # Follow E2E Keycloak and Hookglass logs
make e2e-down               # Stop the E2E stack
make version VERSION=0.2.0  # Set the next JAR version
make clean                  # Remove build outputs
```

The E2E test requires Docker Compose and `curl`; no Python or `jq` is needed. Tagged releases publish a versioned JAR to GitHub Releases.

## License

Licensed under the [Apache License, Version 2.0](LICENSE).
