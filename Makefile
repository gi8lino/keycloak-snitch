.DEFAULT_GOAL := help

## Tool Versions

# renovate: datasource=github-releases depName=gi8lino/dev-tools
DEV_TOOLS_VERSION ?= v0.9.0
MVN ?= mvn
VERSIONS_MAVEN_PLUGIN_VERSION ?= 2.22.0

## End-to-end Compose Configuration

E2E_PROJECT ?= keycloak-snitch-e2e
E2E_KC_PORT ?= 18083
E2E_RECEIVER_PORT ?= 18091
E2E_REALM_FILE := test/e2e/realm/keycloak-snitch-e2e.json
E2E_REALM := $(shell sed -n 's/^[[:space:]]*"realm"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' $(E2E_REALM_FILE))
E2E_KC_REALM_URL := http://127.0.0.1:$(E2E_KC_PORT)/realms/$(E2E_REALM)/account/
E2E_COMPOSE := test/e2e/compose.yaml
E2E_DOCKER_COMPOSE = E2E_KC_PORT=$(E2E_KC_PORT) E2E_RECEIVER_PORT=$(E2E_RECEIVER_PORT) docker compose -p $(E2E_PROJECT) -f $(E2E_COMPOSE)

## Shared Development Tools

include bin/dev-tools.mk
include $(call dev-tools-module,tag)
include $(call dev-tools-module,help)

##@ Development

.PHONY: build
build: ## Clean, test, and build the installable Keycloak Snitch JAR.
	$(MVN) --batch-mode --no-transfer-progress clean verify

.PHONY: version
version: ## Update pom.xml revision (VERSION=x.y.z).
	@test -n "$(strip $(VERSION))" || { echo "Usage: make version VERSION=0.2.0" >&2; exit 2; }
	$(MVN) --batch-mode --no-transfer-progress \
		org.codehaus.mojo:versions-maven-plugin:$(VERSIONS_MAVEN_PLUGIN_VERSION):set-property \
		-Dproperty=revision -DnewVersion="$(VERSION)" -DgenerateBackupPoms=false

.PHONY: clean
clean: ## Remove Maven build outputs.
	$(MVN) clean

##@ End-to-end Compose

.PHONY: e2e-up
e2e-up: ## Build and start Keycloak Snitch and Hookglass.
	$(E2E_DOCKER_COMPOSE) up --detach --build
	@echo "Keycloak: $(E2E_KC_REALM_URL)"

.PHONY: e2e-logs
e2e-logs: ## Follow Keycloak and Hookglass logs.
	$(E2E_DOCKER_COMPOSE) logs --follow keycloak receiver

.PHONY: e2e-down
e2e-down: ## Stop and remove the isolated E2E containers.
	$(E2E_DOCKER_COMPOSE) down --remove-orphans

.PHONY: e2e-reset
e2e-reset: ## Stop the E2E stack and remove its volumes.
	$(E2E_DOCKER_COMPOSE) down --volumes --remove-orphans

.PHONY: e2e-test
e2e-test: ## Test real Keycloak login, refresh, and Hookglass outage flows.
	$(MAKE) e2e-reset
	$(MAKE) e2e-up
	E2E_PROJECT="$(E2E_PROJECT)" E2E_KC_PORT="$(E2E_KC_PORT)" E2E_RECEIVER_PORT="$(E2E_RECEIVER_PORT)" sh test/e2e/test.sh

##@ Testing

.PHONY: test
test: ## Run Java unit tests.
	$(MVN) --batch-mode --no-transfer-progress test

.PHONY: check
check: test ## Run unit tests and validate the E2E script's shell syntax.
	sh -n test/e2e/test.sh

.PHONY: verify
verify: check ## Run all local checks without starting Docker Compose.
