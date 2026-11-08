# Keycloak Snitch: local end-to-end stack

This isolated, **development-only** Docker Compose project exercises real Keycloak
logins, Snitch's event listener, signature verification by the existing Go
receiver, refresh filtering, and an outage of the receiver.

It reuses the repository's `Dockerfile`, `examples/realm/` fixture, and
`examples/receiver/` implementation. There is no Python smoke harness, no
second realm fixture, and no duplicate Dockerfile.

## Commands

Run these from the **Snitch repository root**:

```sh
make e2e-up       # Build and start isolated Keycloak and receiver
make e2e-logs     # Follow Keycloak and receiver logs
make e2e-test     # Reset only the E2E project, start it, and test the full flow
make e2e-down     # Stop and remove E2E containers
make e2e-reset    # Stop, remove containers, and remove E2E volumes
```

The test requires Docker Compose and `curl`, plus standard POSIX shell tools.
No Python or `jq` is required. The successful test leaves the stack running
and restarts the receiver after the outage check.

## Endpoints and credentials

- Keycloak: <http://127.0.0.1:18083/>
- Demo account: <http://127.0.0.1:18083/realms/keycloak-snitch-dev/account/>
- Admin console: <http://127.0.0.1:18083/admin/master/console/>
- Admin: `dev-admin` / `dev-admin-password`
- Test user: `demo` / `dev-password`
- Automated test client: `smoke` (direct-access grants enabled)

Snitch events only flow from the **keycloak-snitch-dev** realm. The `master`
admin realm is not configured with the Snitch listener. Incoming signed events
are printed as one compact JSON object per line in the receiver logs.

The E2E Keycloak port defaults to **18083**, different from Warden's example
port **18080** and Snitch's ordinary development port **8080**. Override the
port or Compose project name with:

```sh
make e2e-test E2E_KC_PORT=18183 E2E_PROJECT=my-snitch-e2e
```

## What `make e2e-test` verifies

1. A real invalid password grant returns `invalid_grant` and emits `LOGIN_ERROR`.
2. A real correct password grant returns access and refresh tokens and emits `LOGIN`.
3. Refreshing the token succeeds but does **not** emit an event because
   `REFRESH_TOKEN` is not in the forwarding filter.
4. A second successful login acts as a FIFO marker. The receiver has exactly
   `LOGIN_ERROR`, `LOGIN`, `LOGIN`, each with a unique event ID, client ID, and
   nonempty IP address. The Go receiver records events **only after verifying
   their Snitch HMAC signatures**.
5. With the receiver stopped, a real login still succeeds and Keycloak logs
   that Snitch dropped an event.
6. The receiver is restarted, leaving the environment available for inspection.

Every service uses test-only credentials and HTTP inside the Compose network;
the published Keycloak port is bound to `127.0.0.1`. Never use this stack in
production.
