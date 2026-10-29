# PayOps project status

Last updated: 2026-10-23

## Completed

- [COMPLETED] Phase 0.5 schema/design package retained: 13 entity tables plus `user_role`, PostgreSQL constraints/indexes/seed data, and `v_unbalanced_journal` in `sql/schema.sql`.
- [COMPLETED] Maven foundation in `pom.xml`: Spring Boot 4.1.1, Java 25, Spring MVC, Spring Data JPA, Bean Validation, PostgreSQL runtime driver, and Spring/JUnit testing support.
- [COMPLETED] Application entry point: `src/main/java/com/yourorg/payops/PayopsApplication.java`.
- [COMPLETED] Environment-based PostgreSQL configuration in `src/main/resources/application.properties`; Hibernate validates mappings and SQL initialization is disabled because the checked-in schema drops/recreates objects.
- [COMPLETED] Existing 13 entity classes, 13 repository interfaces, enum/model classes, audit bases, and exception architecture compile under Java 25.
- [COMPLETED] Four focused unit tests verify 400/404/409/422 exception response behavior (`GlobalExceptionHandlerTest`).
- [COMPLETED] Phase 2 core APIs: switch, merchant, payment initiation/event history, and account controllers.
- [COMPLETED] Payment initiation validates merchant and payment data, selects the merchant's active default switch or the first active switch by priority, and stores the initial event in the same transaction.
- [COMPLETED] Controller/payment tests cover validation, success, missing resources, duplicate keys, inactive merchant business rules, date range rule, and HTTP response statuses where exercised.
- [COMPLETED] Phase 3 accounting increment: journal header create/read and atomic balanced posting batch create/read routes; positive amounts, account/journal references, duplicate journal numbers, and BigDecimal balance comparison are enforced in the controller.
- [COMPLETED] Accounting controller tests cover journal create/conflict, missing journal/account, non-positive amounts, balanced/unbalanced batches, and transactional annotation.
- [COMPLETED] `README_RUN.md` has current requirements, database setup warning, and build/run commands.
- [COMPLETED] This status file and `HANDOFF.md` document the actual project state.

## Partial

- [PARTIAL] Entity validation: Bean Validation covers many required fields and sizes; several cross-field/range constraints rely only on PostgreSQL CHECK constraints.
- [PARTIAL] Exception handling: known validation, not-found, business-rule, and data-integrity errors map to `ApiError`; no general catch-all mapping is defined.
- [PARTIAL] Runtime verification: baseline `mvn clean compile` and `mvn test` pass (22 tests); post-accounting verification is pending. `PAYOPS_DB_URL`, `PAYOPS_DB_USERNAME`, and `PAYOPS_DB_PASSWORD` are unset. The schema was not applied and PostgreSQL schema validation was not attempted.

## Not started

- [PARTIAL] Phase 3: journal header and balanced posting batch operations are implemented. Reversal and an explicit finalized/posted lifecycle remain unsupported by the current schema.
- [NOT STARTED] Phase 4: settlement APIs and processing.
- [NOT STARTED] Phase 5: reconciliation APIs and matching behavior.
- [NOT STARTED] Phase 6: user/role APIs and authentication foundation.
- [NOT STARTED] Phase 7: Spring Security, JWT, password hashing, and authorization.
- [NOT STARTED] API integration tests against a prepared PostgreSQL database.
- [NOT STARTED] Database migration tooling; `schema.sql` is a development bootstrap script, not a migration.

## Known discrepancies and blocked design details

- `docs/DATA_DICTIONARY.md` calls `role.name` unique, but neither `sql/schema.sql` nor `Role.java` declares uniqueness. No change has been made pending confirmation of intended design.
- Java validation does not mirror every database check, including payment refund upper bound, settlement fee upper bound, several numeric ranges/counts, and the reconciliation result minimum-side rule.
- Posting reversal instructions mention marking a posting as reversed, but the existing `Posting` entity/table has no reversal marker or link. Reversal is not implemented and no field or schema change was made. The schema also has no journal lifecycle/status column; this increment exposes no finalization endpoint and only accepts balanced posting batches.
- The configuration expects a PostgreSQL database and schema. Startup cannot be verified until PostgreSQL credentials are configured and the schema has been applied to a safe development database.

## Current maturity

- Schema/design: substantial Phase 0.5 foundation.
- Java entities/repositories: present and compile; repository interfaces have derived query methods.
- Controllers/business logic: Phase 2 endpoints and the Phase 3 journal/posting increment are implemented; payment authorization, capture, failure, refund, settlement, and reconciliation operations are not.
- Runnable build: Maven compile/test works; app startup needs configured PostgreSQL credentials and the existing schema.
- Security: not implemented; no credentials, JWT secret, or authentication dependency is included.

## Next recommended work

Next, complete PostgreSQL-backed schema/startup verification against a safely prepared development database. Keep reversal blocked until its representation is approved; do not encode reversal in a description or silently alter schema. Then proceed to settlement ingestion and record workflows.

## Demo-readiness increment (2026-10-24)
- Frontend: overview KPIs computed from API data (volume, merchants, switches, files, runs, match rate, journal balance), status summaries, recent results; new Accounting page; click-through detail on Payments (full transaction trace), Settlement files (records), Reconciliation (results).
- scripts/seed-demo.ps1 loads demo data through existing REST APIs only. DEMO_SCRIPT.md has run steps and talking points.
- VERIFIED: Angular strict template type-check (ngc --noEmit) passes. NOT VERIFIED: backend start against PostgreSQL, seed script run, UI in a browser (sandbox has no Java 25/PostgreSQL; Node 22.22.2 is below the Angular CLI minimum so no bundle was built).
- Backend untouched.
