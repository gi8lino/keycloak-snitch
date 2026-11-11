# Keycloak Snitch: local end-to-end stack

This **development-only** Docker Compose project tests real Keycloak logins, the Snitch event listener, signature validation by [Hookglass](https://github.com/gi8lino/hookglass), refresh filtering, and an outage of the webhook receiver.

The stack builds Snitch using the repository's Keycloak `Dockerfile`, but imports its **own realm fixture** from [`realm/keycloak-snitch-e2e.json`](realm/keycloak-snitch-e2e.json). The E2E fixture is self-contained. Hookglass replaces the custom Go receiver, so no additional receiver source or Dockerfile is needed.

## Start

From the **Snitch repository root**:

```sh
make e2e-up       # Build and start Keycloak and Hookglass
make e2e-logs     # Follow Keycloak and Hookglass logs
make e2e-test     # Reset the E2E stack, start it, and verify the full flow
make e2e-down     # Stop and remove the E2E containers
make e2e-reset    # Stop and remove the E2E containers and volumes
```

The test requires Docker Compose, `curl`, and standard POSIX shell tools; it does not require Python or `jq`. After a successful test, the stack remains running with Hookglass restarted. `make e2e-up` prints the account URL of the imported `keycloak-snitch-e2e` realm.

## Endpoints and test users

| Service   | URL                                                          | Notes                               |
| --------- | ------------------------------------------------------------ | ----------------------------------- |
| Keycloak  | `http://127.0.0.1:18083/realms/keycloak-snitch-e2e/account/` | `keycloak-snitch-e2e` realm         |
| Hookglass | `http://127.0.0.1:18091/healthz`                             | Verifies Snitch webhooks on `/hmac` |

Keycloak administrator: `dev-admin` / `dev-admin-password`.
Test user: `demo` / `dev-password`.
Automated direct-grant client: `smoke`.

For manual logins, open the [demo account console](http://127.0.0.1:18083/realms/keycloak-snitch-e2e/account/). The `snitch` event listener is enabled only in the `keycloak-snitch-e2e` realm, not in the `master` admin realm. For administration, use the [admin console](http://127.0.0.1:18083/admin/master/console/).

The Keycloak port defaults to **18083**, different from Warden's E2E port **18080**. Hookglass uses **18091** on the host, so it can run alongside Warden's Hookglass receiver on **18090**. Both published ports bind only to `127.0.0.1`. Override ports or the Compose project name as needed:

```sh
E2E_RECEIVER_PORT=18191 make e2e-test E2E_KC_PORT=18183 E2E_PROJECT=my-snitch-e2e
```

## Hookglass HMAC verification

Hookglass runs at `http://receiver:8080` on the Compose network and loads [`hmac.yaml`](hmac.yaml). Snitch sends to `http://receiver:8080/hmac`, using the same `dev-only-shared-secret` configured as `WEBHOOK_SECRET` in Hookglass.

The receiver checks `X-Snitch-Signature` and `X-Snitch-Timestamp` against HMAC-SHA256 of the original `timestamp + "." + body` and a five-minute timestamp window. Missing or invalid signatures receive `401`; successfully verified requests are logged and receive `204`.

Hookglass uses Go's default structured text logger. Each accepted event appears as `event=webhook_received path=/hmac verified=true`, with the request body as an escaped JSON string. It does not log raw signature headers or credentials.

Inspect the events with:

```sh
docker compose -p keycloak-snitch-e2e -f test/e2e/compose.yaml logs -f receiver
```

## What `make e2e-test` verifies

1. Hookglass is healthy and rejects an unsigned request to `/hmac` with `401`.
2. A real invalid password grant returns `invalid_grant` and produces `LOGIN_ERROR`.
3. A successful password grant returns access and refresh tokens and produces `LOGIN`.
4. Refreshing the token succeeds without forwarding `REFRESH_TOKEN`.
5. A second successful login provides a FIFO marker. Hookglass records exactly
   `LOGIN_ERROR`, `LOGIN`, `LOGIN`, each with a distinct ID, the `smoke` client ID,
   and a nonempty IP address **after successful HMAC verification**.
6. Stopping Hookglass does not prevent a real login, and Keycloak logs that Snitch
   dropped the undeliverable event.
7. Hookglass is restarted for further inspection.

**Security:** All credentials are for local testing. Hookglass's `/echo` route is unauthenticated, and verified request bodies may contain sensitive information. Do not expose this stack to the public Internet or reuse its secrets in production.
