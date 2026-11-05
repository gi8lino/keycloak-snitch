.DEFAULT_GOAL := help

# renovate: datasource=github-releases depName=gi8lino/dev-tools
DEV_TOOLS_VERSION ?= v0.9.0
MVN ?= mvn
VERSIONS_MAVEN_PLUGIN_VERSION ?= 2.22.0

include bin/dev-tools.mk
include $(call dev-tools-module,tag)
include $(call dev-tools-module,help)

.PHONY: build version test check smoke run stop clean

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

check: test smoke ## Run unit tests and the Keycloak smoke test

smoke: ## Test real logins in a disposable Keycloak instance
	python3 tests/smoke.py

run: ## Build and start the local development environment
	docker compose up -d --build

stop: ## Stop the local development environment
	docker compose down

clean: ## Remove Maven build outputs
	$(MVN) clean
