package io.github.gi8lino.keycloak.snitch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.keycloak.Config;
import org.keycloak.events.EventType;

class KeycloakSnitchFactoryTest {
    private static final Map<String, String> VALID_SETTINGS = Map.of(
            "webhook-url", "https://warden.example.com/events",
            "webhook-secret", "test-secret"
    );

    @Test
    void defaultsToLoginErrors() {
        KeycloakSnitchFactory factory = new KeycloakSnitchFactory();
        try {
            factory.init(scope(VALID_SETTINGS));

            KeycloakSnitch listener = (KeycloakSnitch) factory.create(null);
            assertEquals(Set.of(EventType.LOGIN_ERROR), listener.types());
        } finally {
            factory.close();
        }
    }

    @Test
    void acceptsExplicitEventTypes() {
        KeycloakSnitchFactory factory = new KeycloakSnitchFactory();
        try {
            Map<String, String> settings = new HashMap<>(VALID_SETTINGS);
            settings.put("event-types", "LOGIN_ERROR, LOGIN");
            factory.init(scope(settings));

            KeycloakSnitch listener = (KeycloakSnitch) factory.create(null);
            assertEquals(Set.of(EventType.LOGIN_ERROR, EventType.LOGIN), listener.types());
        } finally {
            factory.close();
        }
    }

    @Test
    void rejectsEmptyAndUnknownEventTypes() {
        assertInvalidEventTypes("", "empty entries");
        assertInvalidEventTypes("LOGIN_ERROR,", "empty entries");
        assertInvalidEventTypes("LOGIN_ERROR,,LOGIN", "empty entries");
        assertInvalidEventTypes("UNKNOWN_EVENT", "UNKNOWN_EVENT");
    }

    @Test
    void rejectsMissingOrInvalidWebhookConfiguration() {
        assertInvalid(Map.of("webhook-secret", "test-secret"), "webhook-url");
        assertInvalid(Map.of("webhook-url", "https://warden.example.com/events"), "webhook-secret");
        assertInvalid(Map.of(
                "webhook-url", "ftp://warden.example.com/events",
                "webhook-secret", "test-secret"
        ), "webhook-url");
    }

    private static void assertInvalidEventTypes(String configured, String message) {
        Map<String, String> settings = new HashMap<>(VALID_SETTINGS);
        settings.put("event-types", configured);
        assertInvalid(settings, message);
    }

    private static void assertInvalid(Map<String, String> settings, String message) {
        KeycloakSnitchFactory factory = new KeycloakSnitchFactory();
        try {
            IllegalArgumentException invalid = assertThrows(
                    IllegalArgumentException.class,
                    () -> factory.init(scope(settings))
            );
            assertTrue(invalid.getMessage().contains(message), invalid.getMessage());
        } finally {
            factory.close();
        }
    }

    private static Config.Scope scope(Map<String, String> settings) {
        return (Config.Scope) Proxy.newProxyInstance(
                Config.Scope.class.getClassLoader(),
                new Class<?>[]{Config.Scope.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "get" -> settings.getOrDefault(
                            (String) args[0], args.length > 1 ? (String) args[1] : null
                    );
                    case "getInt" -> settings.containsKey(args[0])
                            ? Integer.parseInt(settings.get(args[0]))
                            : args[1];
                    default -> throw new UnsupportedOperationException(method.getName());
                }
        );
    }
}
