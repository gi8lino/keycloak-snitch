#!/bin/sh
# Exercise real Keycloak -> Snitch -> HMAC-verifying receiver events.
set -eu

root=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)
project=${E2E_PROJECT:-keycloak-snitch-e2e}
compose_file="$root/examples/e2e/compose.yaml"
realm_url="http://127.0.0.1:${E2E_KC_PORT:-18083}/realms/keycloak-snitch-dev"
token_url="$realm_url/protocol/openid-connect/token"
response_file=$(mktemp)
success=false

compose() {
  docker compose -p "$project" -f "$compose_file" "$@"
}

cleanup() {
  if [ "$success" != true ]; then
    printf '\n--- End-to-end service logs ---\n' >&2
    compose logs --tail 100 >&2 || true
  fi
  rm -f "$response_file"
}
trap cleanup 0
trap 'exit 1' 1 2 15

wait_keycloak() {
  attempt=0
  while [ "$attempt" -lt 120 ]; do
    if curl --silent --fail --max-time 3 --output /dev/null \
      "$realm_url/.well-known/openid-configuration"; then
      printf 'Ready: Keycloak\n'
      return 0
    fi
    attempt=$((attempt + 1))
    sleep 1
  done
  printf 'Timed out waiting for Keycloak\n' >&2
  return 1
}

request_token() {
  status=$(curl --silent --show-error --max-time 10 \
    --output "$response_file" --write-out '%{http_code}' \
    --data-urlencode 'client_id=smoke' "$@" "$token_url")
}

assert_token() {
  if [ "$status" != 200 ] || ! grep -Eq '"access_token"[[:space:]]*:[[:space:]]*"[^\"]+"' "$response_file"; then
    printf 'Expected successful %s (HTTP %s)\n' "$1" "$status" >&2
    cat "$response_file" >&2
    return 1
  fi
}

login() {
  request_token --data-urlencode 'grant_type=password' \
    --data-urlencode 'username=demo' --data-urlencode "password=$1"
}

receiver_events() {
  compose logs --no-log-prefix receiver 2>&1 | grep '^{' || true
}

wait_events() {
  attempt=0
  while [ "$attempt" -lt 30 ]; do
    received=$(receiver_events)
    count=$(printf '%s\n' "$received" | grep -c '^{' || true)
    if [ "$count" -ge 3 ]; then
      return 0
    fi
    attempt=$((attempt + 1))
    sleep 1
  done
  printf 'Timed out waiting for the three signed login events\n' >&2
  return 1
}

wait_delivery_warning() {
  attempt=0
  while [ "$attempt" -lt 15 ]; do
    if compose logs --no-log-prefix keycloak 2>&1 | grep -Fq 'Keycloak Snitch dropped event'; then
      return 0
    fi
    attempt=$((attempt + 1))
    sleep 1
  done
  printf 'Keycloak did not log the dropped event during receiver outage\n' >&2
  return 1
}

wait_keycloak

login wrong-password
if [ "$status" != 400 ] || ! grep -Eq '"error"[[:space:]]*:[[:space:]]*"invalid_grant"' "$response_file"; then
  printf 'Wrong password was not rejected with invalid_grant (HTTP %s)\n' "$status" >&2
  cat "$response_file" >&2
  exit 1
fi
printf 'Verified LOGIN_ERROR via real password grant\n'

login dev-password
assert_token 'login'
refresh_token=$(sed -n 's/.*"refresh_token"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$response_file")
if [ -z "$refresh_token" ]; then
  printf 'Login response did not include a refresh token\n' >&2
  exit 1
fi

request_token --data-urlencode 'grant_type=refresh_token' \
  --data-urlencode "refresh_token=$refresh_token"
assert_token 'refresh'

# This second LOGIN is a FIFO marker after the filtered REFRESH_TOKEN.
login dev-password
assert_token 'second login'
wait_events
received=$(receiver_events)
count=$(printf '%s\n' "$received" | grep -c '^{' || true)
types=$(printf '%s\n' "$received" | sed -n 's/.*"type"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | paste -sd, -)
ids=$(printf '%s\n' "$received" | sed -n 's/.*"id"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')
unique_ids=$(printf '%s\n' "$ids" | sed '/^$/d' | sort -u | wc -l | tr -d '[:space:]')
clients=$(printf '%s\n' "$received" | grep -Ec '"clientId"[[:space:]]*:[[:space:]]*"smoke"' || true)
ips=$(printf '%s\n' "$received" | grep -Ec '"ipAddress"[[:space:]]*:[[:space:]]*"[^"]+"' || true)
if [ "$count" -ne 3 ] || [ "$types" != 'LOGIN_ERROR,LOGIN,LOGIN' ] ||
  [ "$unique_ids" -ne 3 ] || [ "$clients" -ne 3 ] || [ "$ips" -ne 3 ]; then
  printf 'Unexpected signed events (count=%s types=%s unique IDs=%s clients=%s IPs=%s):\n%s\n' \
    "$count" "$types" "$unique_ids" "$clients" "$ips" "$received" >&2
  exit 1
fi
printf 'Verified signed LOGIN_ERROR, LOGIN, LOGIN; refresh was filtered\n'

compose stop receiver
login dev-password
assert_token 'login during receiver outage'
wait_delivery_warning
printf 'Verified successful login and delivery warning during receiver outage\n'

# Restore the receiver so the stack remains useful for inspecting test events.
compose start receiver
success=true
printf '\nEnd-to-end OK: real logins, refresh filtering, HMAC-verified events, and receiver outage.\n'
printf 'Inspect the stack with: make e2e-logs\n'
