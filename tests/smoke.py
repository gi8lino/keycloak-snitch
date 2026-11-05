"""Exercise real direct-grant login and refresh in a disposable Compose project."""
import json
import os
import subprocess
import time
import uuid
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import urlopen

SMOKE_PROJECT_ID_LENGTH = 8
DEFAULT_KC_PORT = "18080"
TOKEN_REQUEST_TIMEOUT_SECONDS = 6
READY_REQUEST_TIMEOUT_SECONDS = 2
KEYCLOAK_STARTUP_TIMEOUT_SECONDS = 120
EVENTS_TIMEOUT_SECONDS = 15
OUTAGE_WARNING_TIMEOUT_SECONDS = 10
POLL_INTERVAL_SECONDS = 0.3
EXPECTED_EVENT_COUNT = 3
HTTP_OK = 200
HTTP_BAD_REQUEST = 400
KEYCLOAK_LOG_TAIL_LINES = 80

project = "keycloak-snitch-smoke-" + uuid.uuid4().hex[:SMOKE_PROJECT_ID_LENGTH]
base = "http://127.0.0.1:" + \
    os.environ.get("KC_PORT", DEFAULT_KC_PORT) + "/realms/keycloak-snitch-dev"
os.environ.setdefault("KC_PORT", DEFAULT_KC_PORT)


def compose(*args, capture=False):
    return subprocess.run(["docker", "compose", "-p", project, *args], check=True,
                          capture_output=capture, text=True).stdout


def token(**params):
    body = urlencode(dict(client_id="smoke", **params)).encode()
    with urlopen(base + "/protocol/openid-connect/token", body, timeout=TOKEN_REQUEST_TIMEOUT_SECONDS) as response:
        return json.load(response)


def login(password):
    return token(grant_type="password", username="demo", password=password)


def events():
    lines = compose("logs", "--no-log-prefix",
                    "receiver", capture=True).splitlines()
    return [json.loads(line) for line in lines if line.startswith("{")]


def wait_for(check, seconds):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        try:
            if check():
                return
        except (URLError, TimeoutError, ConnectionError):
            pass
        time.sleep(POLL_INTERVAL_SECONDS)
    raise RuntimeError("Timed out waiting for " + check.__name__)


def ready():
    with urlopen(base + "/.well-known/openid-configuration", timeout=READY_REQUEST_TIMEOUT_SECONDS) as response:
        return response.status == HTTP_OK


try:
    compose("up", "-d", "--build")
    wait_for(ready, KEYCLOAK_STARTUP_TIMEOUT_SECONDS)
    try:
        login("wrong")
        raise AssertionError("Invalid password accepted")
    except HTTPError as error:
        assert error.code == HTTP_BAD_REQUEST and json.load(
            error)["error"] == "invalid_grant"
    tokens = login("dev-password")
    assert tokens["access_token"]
    assert token(grant_type="refresh_token",
                 refresh_token=tokens["refresh_token"])["access_token"]
    # A second LOGIN is a FIFO marker after the filtered REFRESH_TOKEN.
    assert login("dev-password")["access_token"]
    wait_for(lambda: len(events()) >= EXPECTED_EVENT_COUNT, EVENTS_TIMEOUT_SECONDS)
    received = events()
    assert [event["type"] for event in received] == [
        "LOGIN_ERROR", "LOGIN", "LOGIN"], received
    assert all(event["clientId"] == "smoke" and event["ipAddress"]
               for event in received)
    assert len({event["id"] for event in received}) == EXPECTED_EVENT_COUNT
    compose("stop", "receiver")
    receiver_state = json.loads(
        compose("ps", "-a", "--format", "json", "receiver", capture=True))
    assert receiver_state["ExitCode"] == 0, receiver_state
    assert login("dev-password")["access_token"]
    wait_for(lambda: "Keycloak Snitch dropped event" in compose(
        "logs", "keycloak", capture=True), OUTAGE_WARNING_TIMEOUT_SECONDS)
    print("Verified LOGIN_ERROR, LOGIN, refresh filtering and login during receiver outage.")
except BaseException:
    compose("logs", "--tail", str(KEYCLOAK_LOG_TAIL_LINES), "keycloak")
    raise
finally:
    compose("down", "-v")
