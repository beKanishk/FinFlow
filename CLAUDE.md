# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

FinFlow is a banking ledger Spring Boot app (Java 22, Gradle, Postgres). It depends on a sibling repo, **lib-commons** (at `../lib-commons`), for generic cross-app infrastructure (JWT auth, JPA audit base entity) that isn't FinFlow-specific.

## Commands

```bash
./gradlew build                 # build all modules, run tests
./gradlew :service:bootRun      # run the app (needs Postgres on localhost:5432, db "postgres", user/pass "postgres")
./gradlew :service:compileJava  # compile only (fast check after editing entities)
./gradlew test                  # run all tests
./gradlew :service:test --tests "finance.finflow.FinFlowApplicationTests"   # run a single test class
```

There's no linter configured. `spring.jpa.hibernate.ddl-auto=update` means schema changes apply automatically on startup — no manual migrations.

### lib-commons dependency

FinFlow consumes `lib-commons` as Maven artifacts resolved via `mavenLocal()` (see `com.lib-commons:module-authentication` and `com.lib-commons:module-jpa-audit` in `module-wallet/build.gradle` etc.). If you change lib-commons code, you must reinstall it locally before FinFlow will pick it up:

```bash
cd "../lib-commons" && ./mvnw -q install -DskipTests
```

## Architecture

### Multi-module structure and why it's shaped this way

FinFlow is split into Gradle submodules, each a single domain area. **`service` is a pure bootstrap module** — it holds only `FinFlowApplication` and `application.properties`, no business code. Dependencies only ever point *into* `service`, never out of it — this is deliberate: Gradle doesn't allow circular project dependencies, so once a domain module's repository needs an entity, that entity can't live in a module the domain module would have to depend on upward.

```
module-wallet      (User, Wallet, WalletStatus, UserRepository, WalletRepository)
      ^
      |
module-auth        (JpaAuthUserRepository, UserController, RegisterRequest, UserResponseDTO)
      ^
module-transaction (Transaction, TransactionType, TransactionStatus, IdempotencyRecord)
      ^
      |
module-ledger      (LedgerEntry, LedgerEntryType)
      ^
      |
   service         (FinFlowApplication, application.properties) -- bootstrap only, no business code
```

- **`module-wallet`** is the base domain module. `User` lives here (not in `module-auth`) because `Wallet` has a hard `@OneToOne` reference to `User` — whichever module defines `Wallet` needs the real `User` class on its compile classpath, not just an interface. Putting `User` in `module-auth` or `service` would force a circular dependency.
- **`module-auth`** holds the *mechanism* that operates on `User` (the lib-commons `AuthUserRepository` port adapter, the registration/profile controller) but doesn't own the entity itself — same separation lib-commons itself uses (its `module-authentication` only exposes a generic `AuthUser` interface, never a concrete entity).
- **`module-transaction`** depends on `module-wallet` because `Transaction.sourceWallet`/`destinationWallet` are real `Wallet` references.
- **`module-ledger`** depends on both `module-wallet` and `module-transaction` because `LedgerEntry` references both `Wallet` and `Transaction` directly.
- All `package finance.finflow.*` names stay identical across modules (`finance.finflow.module`, `.repository`, `.controller`, `.dto`) regardless of which physical Gradle module a file lives in — this matters because Spring Boot's component/entity/repository scanning in `FinFlowApplication` is package-based, not jar-based.

Each domain module is a plain library jar (no Spring Boot plugin applied) — only `service` applies `org.springframework.boot` since it's the only executable one. Because of this, the root `build.gradle`'s `subprojects {}` block explicitly imports the Spring Boot BOM (`spring-boot-dependencies`) via `io.spring.dependency-management`, so every module gets consistent dependency versions (e.g. for Lombok) even without the Boot plugin.

### Entity conventions (follow these for any new entity)

- Entities that need audit tracking extend lib-commons' `AbstractBaseEntity` (`com.modulejpaaudit.AbstractBaseEntity`), which provides `id` (Long, IDENTITY), `version`, `createdBy`/`updatedBy`, `createdAt`/`updatedAt` (Instant) — populated automatically via `AuditingEntityListener` + the `AuditorAware<String>` bean in lib-commons' `module-jpa-audit`, which reads the username off `SecurityContextHolder` (populated by lib-commons' `JwtAuthFilter`).
- Entities also expose a separate, public-facing `UUID` identifier via Hibernate's `@UuidGenerator` (e.g. `walletId`, `transactionId`, `ledgerId`), distinct from the internal `Long id` — never expose the internal `id` externally.
- Append-only/non-mutating tables (`IdempotencyRecord`) don't extend `AbstractBaseEntity` and manage their own `@Id`/`createdAt` via `@PrePersist` instead, since `updatedBy`/`version` don't make sense for a record that's never updated.
- Money fields are always `BigDecimal` with `precision = 19, scale = 4` — never `double`/`float`.
- Enums are stored via `@Enumerated(EnumType.STRING)`, never ordinal.
- FK relationships use `@ManyToOne`/`@OneToOne` with `@JoinColumn`, not bare `Long` FK columns — this preserves JPA navigation (`entry.getWallet().getCurrency()`, etc.).

### Auth flow (delegated to lib-commons)

FinFlow doesn't implement its own JWT/login logic — `module-auth`'s `JpaAuthUserRepository` is the **adapter** that implements lib-commons' generic `AuthUserRepository` port against FinFlow's real `User`/`UserRepository`. lib-commons' `AuthController` (`/auth/token`, `/auth/validate`, generic `/auth/register`) and `JwtAuthFilter` run inside FinFlow's own process (lib-commons is a library, not a separate service) — `FinFlowApplication`'s `scanBasePackages` explicitly includes `com.moduleauthentication` and `com.modulejpaaudit` so Spring picks up those lib-commons beans alongside FinFlow's own.

FinFlow's own `module-auth.UserController` adds endpoints lib-commons can't provide generically because they need app-specific fields: `POST /users/register` (username/password/name/email, unlike the generic library endpoint which only takes username/password), `GET /users/me`, `GET /users/{username}`.

Public (no-JWT-required) paths are controlled by `auth.public-paths` in `application.properties`, not hardcoded in lib-commons — this list must include any new unauthenticated endpoint (e.g. `/users/register` was added here, not in lib-commons' `AuthConfig`).
