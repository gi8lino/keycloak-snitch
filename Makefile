.DEFAULT_GOAL := help

# renovate: datasource=github-releases depName=gi8lino/dev-tools
DEV_TOOLS_VERSION ?= v0.9.0
MVN ?= mvn
VERSIONS_MAVEN_PLUGIN_VERSION ?= 2.22.0

E2E_PROJECT ?= keycloak-snitch-e2e
E2E_KC_PORT ?= 18083
E2E_COMPOSE := examples/e2e/compose.yaml
E2E_DOCKER_COMPOSE = E2E_KC_PORT=$(E2E_KC_PORT) docker compose -p $(E2E_PROJECT) -f $(E2E_COMPOSE)

include bin/dev-tools.mk
include $(call dev-tools-module,tag)
include $(call dev-tools-module,help)

.PHONY: build version test check run stop clean e2e-up e2e-logs e2e-test e2e-down e2e-reset

build: ## Clean, verify, and build the installable Keycloak Snitch JAR
	$(MVN) --batch-mode --no-transfer-progress clean verify

version: ## Update pom.xml revision (VERSION=x.y.z)
	@test -n "$(strip $(VERSION))" || { echo "Usage: make version VERSION=0.2.0" >&2; exit 2; }
	$(MVN) --batch-mode --no-transfer-progress \
		org.codehaus.mojo:versions-maven-plugin:$(VERSIONS_MAVEN_PLUGIN_VERSION):set-property \
		-Dproperty=revision -DnewVersion="$(VERSION)" -DgenerateBackupPoms=false

test: ## Run Java and Go unit tests
	$(MVN) test
	cd examples/receiver && go test ./...

check: ## Run unit tests and the Compose end-to-end test
	$(MAKE) test
	$(MAKE) e2e-test

run: ## Build and start the local development environment
	docker compose up -d --build

stop: ## Stop the local development environment
	docker compose down

e2e-up: ## Build and start the isolated Keycloak Snitch E2E stack
	$(E2E_DOCKER_COMPOSE) up -d --build

e2e-logs: ## Follow E2E Keycloak and receiver logs
	$(E2E_DOCKER_COMPOSE) logs -f keycloak receiver

e2e-test: ## Reset and test real Keycloak login, refresh, and receiver outage flows
	$(MAKE) e2e-reset
	$(MAKE) e2e-up
	E2E_PROJECT="$(E2E_PROJECT)" E2E_KC_PORT="$(E2E_KC_PORT)" sh examples/e2e/test.sh

e2e-down: ## Stop and remove the isolated E2E containers
	$(E2E_DOCKER_COMPOSE) down

e2e-reset: ## Remove the E2E stack and any associated volumes
	$(E2E_DOCKER_COMPOSE) down -v --remove-orphans

clean: ## Remove Maven build outputs
	$(MVN) clean
