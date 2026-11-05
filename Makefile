.DEFAULT_GOAL := help

# renovate: datasource=github-releases depName=gi8lino/dev-tools
DEV_TOOLS_VERSION ?= v0.9.0
MVN ?= mvn

include bin/dev-tools.mk
include $(call dev-tools-module,tag)
include $(call dev-tools-module,help)

.PHONY: build test check smoke run stop clean

build: ## Build the installable Keycloak Snitch JAR
	$(MVN) package

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
