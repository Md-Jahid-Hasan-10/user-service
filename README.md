# user-service

> The identity and registration service of the banking platform: it owns the customer profile, the BCrypt password hash, the role and the account status, issues the JWT, and publishes the registration and e-mail-verification events. HTTP port **8083** (Actuator/metrics on **18083**).

- [What It Does](#what-it-does)
- [Architecture At A Glance](#architecture-at-a-glance)
- [HTTP API](#http-api)
- [Error Response Shape](#error-response-shape)
- [Database](#database)
- [Kafka Topics](#kafka-topics)
- [Configuration](#configuration)
- [Behaviour Rules](#behaviour-rules)
- [Running It](#running-it)
- [API Quick Reference (curl examples)](#api-quick-reference-curl-examples)
- [Configuration Reference (quick table)](#configuration-reference-quick-table)
- [Troubleshooting](#troubleshooting)
- [Notes and Known Limitations](#notes-and-known-limitations)

## What It Does

`user-service` is the identity and registration service of the banking system. It owns two tables: `users` (the customer profile, the BCrypt password hash, the role and the account status) and `email_verification_tokens` (single-use, SHA-256-hashed e-mail verification tokens with an expiry). A third table, `outbox_events`, is the transactional outbox used to publish events reliably.

It exposes the three public auth endpoints — register, verify e-mail, login — that the API gateway (`api-gateway`, port 8080) routes from `/api/auth/**`. It also exposes one internal read endpoint, `/internal/users/{id}`, which other services use to resolve a user. That internal path is never routed through the gateway: the gateway has a `blocked-internal` route (`Path=/internal/**` → `uri: no://op`) and its `JwtAuthenticationFilter` answers **404** for any `/internal` path.

This service **issues** the JWT (`sub` = user id, `username` = e-mail, `roles` = role) and **never validates** tokens. The gateway validates every token on every other route and turns the claims into the `X-User-Id`, `X-User-Email` and `X-User-Roles` request headers. The service's own Spring Security filter chain is stateless, CSRF-disabled and `permitAll` for every request — JWT validation happens at the gateway only.

Registration is event-driven. The service writes a `UserRegistered` event and an `EmailVerificationRequested` event to the `user.events` Kafka topic through a transactional outbox. The `notification-service` (port 8087) consumes `EmailVerificationRequested`, decrypts the verification token and delivers the e-mail. The service also owns a small Redis-backed rate limiter that limits how many registration e-mails one address can trigger per hour.

It does **not** own bank accounts (that is `account-service`, port 8081), ledger balances (that is `core-banking-service`, port 8084), transfers (`fund-transfer-service`, port 8082), or the sending of e-mail itself (`notification-service`, port 8087).

## Architecture At A Glance

```
   client ──POST /api/auth/register──▶ api-gateway (8080) ──▶ user-service (8083)
                                                                  │
                                                     users / email_verification_tokens
                                                                  │
                                                        outbox_events (transactional)
                                                                  │
                                                        outbox relay (1 s poll)
                                                                  ▼
                                                        Kafka topic  user.events
                                                                  │
                                                                  ▼
                                                     notification-service (8087)
                                                     decrypts token, sends the mail
```

```
   client ──POST /api/auth/verify──▶ api-gateway ──▶ user-service ──▶ email_verification_tokens
   client ──POST /api/auth/login───▶ api-gateway ──▶ user-service ──▶ JWT (signed, 1 h)
   service ──GET /internal/users/{id}──▶ user-service (never routed via the gateway)
```

| Depends on | Depended on by |
|---|---|
| PostgreSQL `user_db` (127.0.0.1:5433) | `api-gateway` (`/api/auth/**`) |
| Redis (127.0.0.1:6379) — rate limiter | `notification-service` (`user.events` consumer) |
| Kafka (localhost:9092) — producer only | `fund-transfer-service`, `core-banking-service` (resolve a user id) |
| Eureka (`discovery-service`, port 8761) | |
| `notification-service` — legacy direct e-mail path | |

## HTTP API

The service listens on port **8083**. All four endpoints are unauthenticated at the service level; the gateway applies the authentication and rate limits.

### `POST /api/auth/register`

Registers a customer. Always answers **202 Accepted** with the same generic message, whether or not the address was actually registered.

Request body — `com.banking.user.dto.RegisterRequest`:

| Field | Type | Required | Validation | Constraint message |
|---|---|---|---|---|
| `fullName` | `String` | yes | `@NotBlank`, `@Size(max = 100)` | `Full name is required` / `Full name must be at most 100 characters` |
| `email` | `String` | yes | `@NotBlank`, `@Email` | `Email is required` / `Email format is invalid` |
| `password` | `String` | yes | `@NotBlank`, `@Size(min = 8, max = 64)` | `Password is required` / `Password must be 8 to 64 characters` |

Response body — `com.banking.user.dto.MessageResponse`:

| Field | Type | Value |
|---|---|---|
| `message` | `String` | Always `If this email can be registered, we have sent you a message. Please check your inbox.` |

The message is the constant `AuthServiceImpl.REGISTER_MESSAGE` for **every** outcome (new user, existing user, concurrent insert) — this is deliberate anti-enumeration. The response time is also floored at `app.registration.min-response-millis` plus a random 0–59 ms.

Status codes:

| Code | When |
|---|---|
| **202 Accepted** | The request was processed (regardless of whether the address was registered) |
| **400 Bad Request** | Validation failed — the message is `<field>: <defaultMessage>` for each field, joined with `; ` |
| **400 Bad Request** | The body is not readable JSON — message `Malformed request body` |
| **500 Internal Server Error** | Anything else — message `Something went wrong` |

### `POST /api/auth/verify`

Consumes a verification token and activates the user.

Request body — `com.banking.user.dto.VerifyEmailRequest`:

| Field | Type | Required | Validation | Constraint message |
|---|---|---|---|---|
| `token` | `String` | yes | `@NotBlank`, `@Size(max = 200)` | `token is required` / `token is too long` |

Response body — `MessageResponse`:

| Field | Type | Value |
|---|---|---|
| `message` | `String` | `Email verified. You can now log in.` |

Status codes:

| Code | When |
|---|---|
| **200 OK** | The token was valid and unused; the user is now `ACTIVE` |
| **400 Bad Request** | The token is unknown, already used or expired — message `Invalid or expired verification link` |
| **400 Bad Request** | Validation failed — `<field>: <defaultMessage>` |
| **400 Bad Request** | The body is not readable JSON — message `Malformed request body` |

### `POST /api/auth/login`

Exchanges e-mail and password for a JWT.

Request body — `com.banking.user.dto.LoginRequest`:

| Field | Type | Required | Validation | Constraint message |
|---|---|---|---|---|
| `email` | `String` | yes | `@NotBlank`, `@Email` | `Email is required` / `Email format is invalid` |
| `password` | `String` | yes | `@NotBlank` | `Password is required` |

Response body — `com.banking.user.dto.AuthResponse`:

| Field | Type | Value |
|---|---|---|
| `token` | `String` | The signed JWT |
| `tokenType` | `String` | Always `Bearer` |
| `expiresIn` | `long` | `jwtService.getExpirationMs() / 1000` → **3600** (seconds) |
| `userId` | `Long` | `user.getId()` |
| `fullName` | `String` | `user.getFullName()` |
| `email` | `String` | `user.getEmail()` |
| `role` | `String` | `user.getRole().name()` → `CUSTOMER` or `ADMIN` |

Status codes:

| Code | When |
|---|---|
| **200 OK** | The credentials are correct and the user is `ACTIVE` |
| **401 Unauthorized** | Unknown e-mail or wrong password, or the user is `BLOCKED` — message `Invalid email or password` |
| **403 Forbidden** | The user exists but is `PENDING_VERIFICATION` — message `Please verify your email address before logging in` |
| **400 Bad Request** | Validation failed — `<field>: <defaultMessage>` |
| **400 Bad Request** | The body is not readable JSON — message `Malformed request body` |

### `GET /internal/users/{id}`

Resolves a user by id. Used by other services; never routed through the gateway.

Path variable `id` — type `Long`.

Response body — `com.banking.user.dto.UserResponse`:

| Field | Type | Value |
|---|---|---|
| `id` | `Long` | `user.getId()` |
| `fullName` | `String` | `user.getFullName()` |
| `email` | `String` | `user.getEmail()` |
| `role` | `String` | `user.getRole().name()` |
| `status` | `String` | `user.getStatus().name()` |

`passwordHash` is never exposed — `UserMapper.toResponse` does not copy it.

Status codes:

| Code | When |
|---|---|
| **200 OK** | The user exists |
| **404 Not Found** | The user does not exist — message `User not found: <id>` |
| **404 Not Found** | The path does not exist — message `Not found` |
| **500 Internal Server Error** | A non-numeric `{id}` raises `MethodArgumentTypeMismatchException`, which is not handled specially and falls through to the catch-all — message `Something went wrong` |

### Error Response Shape

Every error is produced by `com.banking.user.exception.GlobalExceptionHandler` (`@RestControllerAdvice`) as `com.banking.user.exception.ErrorResponse`:

| Field | Type | Meaning |
|---|---|---|
| `timestamp` | `LocalDateTime` | `LocalDateTime.now()` when the error was built |
| `status` | `int` | The HTTP status value |
| `error` | `String` | `HttpStatus.getReasonPhrase()`, e.g. `Bad Request` |
| `message` | `String` | The exact message listed in the tables above |

Example — a 401 from `POST /api/auth/login`:

```json
{
  "timestamp": "2026-11-02T10:15:30.123456",
  "status": 401,
  "error": "Unauthorized",
  "message": "Invalid email or password"
}
```

## Database

**Database name:** `user_db` — `spring.datasource.url: ${DB_URL:jdbc:postgresql://127.0.0.1:5433/user_db}`.
Created by `postgres/init/01-create-databases.sql` (`CREATE DATABASE user_db;`) in the PostgreSQL 16 container `banking-postgres`, published on host port **5433**. The schema is managed by Hibernate with `spring.jpa.hibernate.ddl-auto: update`.

### Table `users`

Entity `com.banking.user.entity.User` (`@Entity @Table(name = "users")`).

| Column | Type | Null | Default / Notes |
|---|---|---|---|
| `id` | `bigint` | no | PK, generated by default as identity (`GenerationType.IDENTITY`) |
| `created_at` | `timestamp(6) without time zone` | no | Set in `@PrePersist void onCreate()` → `LocalDateTime.now()`; `updatable = false` |
| `email` | `character varying(150)` | no | UNIQUE |
| `full_name` | `character varying(100)` | no | Java field `fullName` |
| `password_hash` | `character varying(255)` | no | BCrypt hash; Java field `passwordHash` |
| `role` | `character varying(20)` | no | Java field `role`; stored as a string (`@Enumerated(EnumType.STRING)`) |
| `status` | `character varying(20)` | no | Java field `status`; stored as a string (`@Enumerated(EnumType.STRING)`) |

Enums:

- `com.banking.user.entity.Role` → `CUSTOMER`, `ADMIN`
- `com.banking.user.entity.UserStatus` → `PENDING_VERIFICATION`, `ACTIVE`, `BLOCKED`

There is no explicit `@Index` on this table. The database also adds a check constraint `users_role_check` limiting `role` to `CUSTOMER` and `ADMIN`.

### Table `email_verification_tokens`

Entity `com.banking.user.entity.EmailVerificationToken`, with
`uniqueConstraints = @UniqueConstraint(name = "uk_evt_hash", columnNames = "tokenHash")` and
`indexes = @Index(name = "idx_evt_user", columnList = "userId")`.

| Column | Type | Null | Default / Notes |
|---|---|---|---|
| `id` | `bigint` | no | PK, generated by default as identity |
| `created_at` | `timestamp(6) without time zone` | no | `@PrePersist onCreate()` → `LocalDateTime.now()`; `updatable = false` |
| `expires_at` | `timestamp(6) without time zone` | no | Java field `expiresAt`; `LocalDateTime.now().plusHours(app.registration.token-ttl-hours)` |
| `token_hash` | `character varying(64)` | no | UNIQUE via `uk_evt_hash`; hex SHA-256 of the raw token |
| `used` | `boolean` | no | Default `false` |
| `user_id` | `bigint` | no | Java field `userId`; indexed by `idx_evt_user` |

The source comment on the field is: *"Only the SHA-256 hash is stored, never the token itself."*

### Table `outbox_events`

Entity `com.banking.user.messaging.OutboxEvent`, with
`indexes = @Index(name = "idx_outbox_pending", columnList = "publishedAt, createdAt")`.

| Column | Type | Null | Default / Notes |
|---|---|---|---|
| `id` | `uuid` | no | PK, assigned manually with `UUID.randomUUID()` |
| `attempts` | `integer` | no | Default `0`; incremented by `recordFailure` |
| `created_at` | `timestamp(6) with time zone` | no | `@PrePersist onCreate()` → `Instant.now()`; `updatable = false` |
| `envelope` | `text` | no | The serialised JSON event envelope |
| `event_type` | `character varying(100)` | no | e.g. `UserRegistered` |
| `last_error` | `character varying(500)` | yes | Cleared by `markPublished`; the failure reason is truncated to 500 characters |
| `message_key` | `character varying(100)` | no | The Kafka message key |
| `published_at` | `timestamp(6) with time zone` | yes | `null` until published |
| `topic` | `character varying(100)` | no | e.g. `user.events` |

### Repositories

`UserRepository extends JpaRepository<User, Long>`:

| Method | Purpose |
|---|---|
| `Optional<User> findByEmail(String email)` | Look a user up by e-mail |
| `boolean existsByEmail(String email)` | Existence check |

`EmailVerificationTokenRepository extends JpaRepository<EmailVerificationToken, Long>`:

| Method | Purpose |
|---|---|
| `Optional<EmailVerificationToken> findByTokenHash(String tokenHash)` | Find the token by its hash |
| `int invalidateActive(Long userId)` | *"Makes every older link for this user useless."* Sets `used = true` for every unused token of that user |
| `int consume(String hash, LocalDateTime now)` | *"Atomic single use: returns 1 for exactly one caller, 0 for everyone else or an expired token."* Updates only where `used = false AND expiresAt > now` |

`OutboxRepository extends JpaRepository<OutboxEvent, UUID>`:

| Method | Purpose |
|---|---|
| `List<OutboxEvent> findTop50ByPublishedAtIsNullOrderByCreatedAtAsc()` | Batch of 50, oldest first, with `@Lock(PESSIMISTIC_WRITE)` and lock timeout `-2` |
| `long countByPublishedAtIsNull()` | Backs the `outbox.pending.events` gauge |
| `int deletePublishedBefore(Instant cutoff)` | Removes published events older than the cutoff |

## Kafka Topics

### Publishes to

**Topic `user.events`** only (constant `com.banking.user.messaging.TopicNames.USER_EVENTS`).

Publishing goes through the transactional outbox. `OutboxEventPublisher.publish(...)` is annotated `@Transactional(propagation = Propagation.MANDATORY)`, so it must be called inside an existing transaction — the state change and the event commit together or not at all.

Every message uses the envelope `com.banking.user.messaging.EventEnvelope` (a record annotated `@JsonIgnoreProperties(ignoreUnknown = true)`):

| Field | Type | Value |
|---|---|---|
| `eventId` | `String` | UUID string |
| `eventType` | `String` | See the tables below |
| `version` | `int` | Always `1` |
| `correlationId` | `String` | The user id as a string |
| `causationId` | `String` | Nullable; `null` for both events below |
| `occurredAt` | `Instant` | `Instant.now()` at publish time |
| `producer` | `String` | `app.messaging.producer-name` → `user-service` |
| `payload` | `JsonNode` | The event payload |

#### Event type `UserRegistered`

Published by `AuthServiceImpl.publishUserRegistered(User)` for **every newly created user**. Message key = the user id as a string; `correlationId` = the user id as a string; `causationId` = `null`.

Payload — `com.banking.user.messaging.payload.UserRegisteredPayload`:

| Field | Type |
|---|---|
| `userId` | `Long` |
| `email` | `String` |
| `fullName` | `String` |

#### Event type `EmailVerificationRequested`

Published by `AuthServiceImpl.publishVerificationRequested(User, VerificationIssue)` whenever a verification token is issued — a new user, or a re-registration of a `PENDING_VERIFICATION` user. Message key = the user id as a string; `correlationId` = the user id as a string; `causationId` = `null`.

Payload — `com.banking.user.messaging.payload.EmailVerificationRequestedPayload`:

| Field | Type | Notes |
|---|---|---|
| `userId` | `Long` | Also the AAD for the AES-GCM encryption |
| `email` | `String` | |
| `fullName` | `String` | |
| `encryptedToken` | `String` | Base64 of `iv + ciphertext + tag`; AES/GCM/NoPadding, 12-byte IV, 128-bit tag, AAD = `userId` as a string |
| `expiresAt` | `Instant` | Token expiry, converted from `LocalDateTime` via `ZoneId.systemDefault()` |

### Consumes from

**None.** `user-service` has no `@KafkaListener` and no consumer configuration; the `spring-kafka` dependency is present for the producer and `KafkaTemplate` only. All `user.events` messages are consumed by `notification-service`
(`notification-service/.../messaging/consumer/UserEventsConsumer.java`), which handles `EmailVerificationRequested` by decrypting `encryptedToken` with AAD = `userId` and sending the mail. The consumer ignores `UserRegistered` and any unknown event type.

### Kafka client configuration

| Property | Value |
|---|---|
| `spring.kafka.bootstrap-servers` | `${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}` |
| `spring.kafka.producer.acks` | `all` |
| `spring.kafka.producer.properties.enable.idempotence` | `true` |
| `spring.kafka.producer.properties.linger.ms` | `0` |
| `spring.kafka.producer.properties.max.block.ms` | `15000` |
| `spring.kafka.producer.properties.request.timeout.ms` | `5000` |
| `spring.kafka.producer.properties.delivery.timeout.ms` | `10000` |

### Outbox relay

`com.banking.user.messaging.OutboxRelay`:

| Behaviour | Detail |
|---|---|
| Poll | `@Scheduled(fixedDelayString = "${app.messaging.relay-interval-ms:1000}") @Transactional publishPending()` |
| Batch | Up to 50 unpublished events, oldest first, row-locked |
| Publish | `kafka.send(topic, messageKey, envelope)` then `.get(30, TimeUnit.SECONDS)` (`PUBLISH_TIMEOUT_SECONDS = 30`) |
| On failure | `event.recordFailure(reason)` (reason truncated to 500 chars), the `outbox.publish.failures` counter is incremented, a warning is logged and the method **returns** — a later event must never overtake an earlier one |
| Cleanup | `@Scheduled(cron = "0 15 3 * * *") @Transactional deleteOldPublishedEvents()` deletes published events older than **7 days** |
| Meters | `outbox.events.published` (counter), `outbox.publish.failures` (counter), `outbox.pending.events` (gauge over `countByPublishedAtIsNull`) |

## Configuration

**File:** `src/main/resources/application.yaml`. There is **no** `application-local.yml` in this service — the `local` profile is not defined here.

### Server, Spring and Eureka properties

| Name | Default | Meaning |
|---|---|---|
| `server.port` | `8083` | HTTP port |
| `management.server.port` | `18083` | Private Actuator/metrics port (health, info, prometheus) |
| `management.endpoints.web.exposure.include` | `health,info,prometheus` | Actuator endpoints exposed |
| `management.metrics.distribution.percentiles-histogram.http.server.requests` | `true` | Histogram buckets for HTTP request metrics |
| `spring.application.name` | `user-service` | Eureka service name and load-balancer name |
| `spring.config.import` | `optional:file:.env[.properties]` | Loads a local `.env` file if present |
| `spring.datasource.url` | `${DB_URL:jdbc:postgresql://127.0.0.1:5433/user_db}` | JDBC URL |
| `spring.datasource.username` | `${DB_USERNAME:banking_admin}` | Database user |
| `spring.datasource.password` | `${DB_PASSWORD:postgre2188}` | Database password |
| `spring.jpa.hibernate.ddl-auto` | `update` | Hibernate updates the schema on start |
| `spring.jpa.open-in-view` | `false` | OSIV disabled |
| `spring.jpa.properties.hibernate.dialect` | `org.hibernate.dialect.PostgreSQLDialect` | Hibernate dialect |
| `spring.data.redis.host` | `${REDIS_HOST:127.0.0.1}` | Redis host (rate limiter) |
| `spring.data.redis.port` | `${REDIS_PORT:6379}` | Redis port |
| `spring.data.redis.password` | `${REDIS_PASSWORD:redis2188}` | Redis password |
| `spring.data.redis.timeout` | `2s` | Command timeout |
| `spring.data.redis.connect-timeout` | `2s` | Connect timeout |
| `spring.kafka.bootstrap-servers` | `${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}` | Kafka brokers |
| `spring.kafka.producer.acks` | `all` | All replicas must acknowledge |
| `spring.kafka.producer.properties.enable.idempotence` | `true` | Idempotent producer |
| `spring.kafka.producer.properties.linger.ms` | `0` | Send immediately |
| `spring.kafka.producer.properties.max.block.ms` | `15000` | Max time to block on a metadata/send request |
| `spring.kafka.producer.properties.request.timeout.ms` | `5000` | Request timeout |
| `spring.kafka.producer.properties.delivery.timeout.ms` | `10000` | Delivery timeout |
| `eureka.client.service-url.defaultZone` | `${EUREKA_URL:http://localhost:8761/eureka}` | Discovery server |
| `eureka.instance.prefer-ip-address` | `true` | Register the IP, not the hostname |

### Custom `app.*` properties

| Name | Default | Meaning |
|---|---|---|
| `app.jwt.secret` | `${JWT_SECRET:78f2b81005ac23edf307e4368594d68fb1038cc667bc12f5b99946b8ea9e5ccb}` | HMAC-SHA key for signing JWTs. **Must be identical to `api-gateway`'s `app.jwt.secret`.** |
| `app.jwt.expiration-ms` | `3600000` | JWT lifetime in ms (1 hour). `AuthResponse.expiresIn` is `3600` seconds. |
| `app.notification.base-url` | `http://notification-service` | Eureka service name resolved by the load balancer; used for `POST /internal/notifications/email` |
| `app.registration.verification-required` | `true` | `false` = new users are active at once (for automated tests). Keep `true` otherwise. |
| `app.registration.verify-url` | `${VERIFY_URL:http://localhost:3000/verify.html}` | Base URL of the front-end verification page (used by the direct e-mail dispatcher) |
| `app.registration.token-ttl-hours` | `24` | Verification token lifetime; `expiresAt = LocalDateTime.now().plusHours(24)` |
| `app.registration.min-response-millis` | `400` | Floor for the register response time (anti-enumeration), plus a random 0–59 ms |
| `app.registration.max-emails-per-address-per-hour` | `3` | Redis-backed per-address e-mail cap |
| `app.messaging.producer-name` | `user-service` | The `producer` field of every event envelope |
| `app.messaging.crypto-key` | `${EVENT_CRYPTO_KEY:pxUXlFhqre/31HUOw+mT9Oz80+rJz33fRtXx/kcGeTk=}` | Base64 32-byte AES key used to encrypt the verification token in the event |
| `app.messaging.relay-interval-ms` | `1000` | Outbox relay poll interval |

`app.registration.*` is bound to `com.banking.user.config.RegistrationProperties` (`@ConfigurationProperties(prefix = "app.registration")`, a record): `boolean verificationRequired, String verifyUrl, int tokenTtlHours, int minResponseMillis, int maxEmailsPerAddressPerHour`.

`app.messaging.*` is bound to `com.banking.user.messaging.MessagingProperties` (`@ConfigurationProperties(prefix = "app.messaging")`, a record): `String producerName, String cryptoKey`.

### Environment variables

| Name | Default | Used for |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://127.0.0.1:5433/user_db` | `spring.datasource.url` |
| `DB_USERNAME` | `banking_admin` | `spring.datasource.username` |
| `DB_PASSWORD` | `postgre2188` | `spring.datasource.password` |
| `REDIS_HOST` | `127.0.0.1` | `spring.data.redis.host` |
| `REDIS_PORT` | `6379` | `spring.data.redis.port` |
| `REDIS_PASSWORD` | `redis2188` | `spring.data.redis.password` |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | `spring.kafka.bootstrap-servers` |
| `EUREKA_URL` | `http://localhost:8761/eureka` | Eureka default zone |
| `JWT_SECRET` | `78f2b81005ac23edf307e4368594d68fb1038cc667bc12f5b99946b8ea9e5ccb` | `app.jwt.secret` |
| `VERIFY_URL` | `http://localhost:3000/verify.html` | `app.registration.verify-url` |
| `EVENT_CRYPTO_KEY` | `pxUXlFhqre/31HUOw+mT9Oz80+rJz33fRtXx/kcGeTk=` | `app.messaging.crypto-key` |
| `SERVER_PORT` | — | Spring Boot standard; `run-all.sh` sets it to `8083` |
| `SPRING_DATASOURCE_URL` / `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` | — | Spring Boot relaxed-binding overrides; `run-all.sh` sets them to `jdbc:postgresql://127.0.0.1:5433/user_db`, `banking_admin` and `$DB_PASSWORD` |

#### Environment variables you must set

There is no variable without a safe default, but three of them are only safe as long as they match another service:

| Name | Why it matters |
|---|---|
| `JWT_SECRET` | Must be **identical** to the API gateway's `app.jwt.secret`. The two defaults differ (`78f2b81005ac23edf307e4368594d68fb1038cc667bc12f5b99946b8ea9e5ccb` here versus `banking-microservices-local-dev-secret-key-2026` in the gateway), so without this the gateway rejects every token this service issues. |
| `EVENT_CRYPTO_KEY` | Base64 32 bytes; must be **identical** to `notification-service`'s key, or the notification service cannot decrypt the verification token and no verification e-mail is sent. |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | Point at the right PostgreSQL instance and database. |

## Behaviour Rules

### Registration (`AuthServiceImpl.register`)

1. `email = request.email().trim().toLowerCase()`; `name = request.fullName().trim()`.
2. `passwordHash = passwordEncoder.encode(request.password())` (BCrypt).
3. The whole database work runs inside `transactionTemplate.execute(status -> process(email, name, passwordHash))`.
4. `DataIntegrityViolationException` (a concurrent registration of the same e-mail) is caught and logged at debug level as `Concurrent registration detected`; the same generic `REGISTER_MESSAGE` is returned.
5. Inside `process(...)`:
   - `verify = properties.verificationRequired()`.
   - Existing user found:
     - If `verify` **and** the status is `PENDING_VERIFICATION`: `fullName` and `passwordHash` are updated, a new token is issued, `EmailVerificationRequested` is published, and the outcome is `VERIFY_LINK`.
     - Otherwise: the outcome is `ALREADY_REGISTERED` and nothing changes.
   - New user: created with `role = Role.CUSTOMER` and `status = verify ? PENDING_VERIFICATION : ACTIVE`, saved with `saveAndFlush`; `UserRegistered` is published; if `verify`, a token is issued and `EmailVerificationRequested` is published and the outcome is `VERIFY_LINK`, otherwise the outcome is `ACCOUNT_READY`.
6. **Anti-enumeration**: the response is always `REGISTER_MESSAGE`, and `ResponseTimeFloor.await(start)` sleeps until at least `app.registration.min-response-millis + random(0..59)` ms have elapsed (the random part is `ThreadLocalRandom.current().nextLong(0, 60)`).
7. The legacy direct e-mail path (`deliver(...)`) is only used for outcomes that are **not** `VERIFY_LINK`, and only when the rate limiter allows it; it sends via `NotificationEmailDispatcher`. The verification e-mail itself goes out over Kafka.

### Verification token rules

| Rule | Detail |
|---|---|
| Token generation | `VerificationTokens.newToken()` — 32 random bytes from `SecureRandom`, Base64 **URL-safe, without padding** (*"256 bits of randomness, safe to put in a URL"*) |
| Storage | `VerificationTokens.hash(token)` → `Hashing.sha256Hex(token)` → hex SHA-256 (64 characters). **Only the hash is stored** in `email_verification_tokens.token_hash`. |
| Invalidation | `issueToken(userId)` first calls `tokenRepository.invalidateActive(userId)`, so every older link for that user becomes unusable |
| Expiry | `expiresAt = LocalDateTime.now().plusHours(app.registration.token-ttl-hours)` (24 hours) |
| Transport | The raw token goes into the event **encrypted** (`EventCrypto.encrypt(rawToken, userId)`), never in clear text |

### E-mail verification (`AuthServiceImpl.verifyEmail`)

1. `hash = verificationTokens.hash(request.token().trim())`.
2. `tokenRepository.findByTokenHash(hash)` — otherwise `InvalidVerificationException("Invalid or expired verification link")` → **400**.
3. `tokenRepository.consume(hash, LocalDateTime.now())` — the atomic update (`used = false AND expiresAt > now`); if it returns `0` (already used or expired) → `InvalidVerificationException` → **400**.
4. The user must exist — otherwise `InvalidVerificationException` → **400**.
5. If the status is `PENDING_VERIFICATION` it is set to `ACTIVE` and saved.
6. Returns `MessageResponse("Email verified. You can now log in.")` → **200 OK**.

### Login (`AuthServiceImpl.login`)

| Situation | Result |
|---|---|
| E-mail is trimmed and lower-cased before the lookup | — |
| The user does not exist | The password is still checked against a pre-computed `dummyHash = passwordEncoder.encode("not-a-real-password-used-for-timing")`, so the response time does not reveal whether the e-mail exists |
| Unknown e-mail or wrong password | `InvalidCredentialsException("Invalid email or password")` → **401** |
| Status is `PENDING_VERIFICATION` | `EmailNotVerifiedException("Please verify your email address before logging in")` → **403** |
| Status is anything other than `ACTIVE` (i.e. `BLOCKED`) | `InvalidCredentialsException("Invalid email or password")` → **401** |
| Success | A JWT is issued |

### JWT claims

`com.banking.user.security.JwtService` signs with `signWith(key)` (HS256 for a 64-character hex secret), where the key is `Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8))` and `secret` is `app.jwt.secret`.

| Claim | Value |
|---|---|
| `sub` | `String.valueOf(user.getId())` |
| `username` | `user.getEmail()` |
| `roles` | `List.of(user.getRole().name())` — e.g. `["CUSTOMER"]` |
| `iat` | `new Date()` |
| `exp` | `new Date(now.getTime() + expirationMs)` |

The gateway's `JwtTokenValidator` reads `sub`, `username` and `roles` and turns them into the `X-User-Id`, `X-User-Email` and `X-User-Roles` headers.

### Password rules

| Rule | Detail |
|---|---|
| Length | 8 to 64 characters (`@Size(min = 8, max = 64)` on `RegisterRequest.password`). `LoginRequest.password` only has `@NotBlank` — no length limit. |
| Storage | BCrypt (`new BCryptPasswordEncoder()`, default strength 10) |
| Complexity | None. No upper-case, digit or symbol requirements beyond the length. |

### Rate limiting

Two independent layers.

**1. In this service — `com.banking.user.throttle.EmailRateLimiter`** (Redis-backed, per e-mail address):

| Aspect | Detail |
|---|---|
| Key | `"reg-mail:" + Hashing.sha256Hex(email)` — *"Hashed, so no email address is stored in Redis."* |
| Algorithm | `setIfAbsent(key, "0", Duration.ofHours(1))` then `increment(key)`; allowed when `count == null || count <= app.registration.max-emails-per-address-per-hour` (**3**) |
| Failure mode | **Fails open**: any `RuntimeException` (for example Redis being down) is logged as `Email rate limiter unavailable, allowing the email: {}` and the mail is allowed |
| Where it applies | Only on the legacy direct e-mail path, not for the Kafka verification mail |

**2. In the API gateway** (`api-gateway/src/main/resources/application.yaml`, `app.rate-limit.rules`):

| Path | Limit | Window |
|---|---|---|
| `/api/auth/register` | `10` | `3600` seconds |
| `/api/auth/login` | `30` | `300` seconds |
| `/api/auth/verify` | `20` | `3600` seconds |

### Error code → HTTP status mapping (`GlobalExceptionHandler`)

| Exception | HTTP status | `error` (reason phrase) | Message |
|---|---|---|---|
| `InvalidCredentialsException` | **401 UNAUTHORIZED** | `Unauthorized` | `ex.getMessage()` |
| `EmailNotVerifiedException` | **403 FORBIDDEN** | `Forbidden` | `ex.getMessage()` |
| `InvalidVerificationException` | **400 BAD_REQUEST** | `Bad Request` | `ex.getMessage()` (`Invalid or expired verification link`) |
| `UserNotFoundException` | **404 NOT_FOUND** | `Not Found` | `ex.getMessage()` (`User not found: <id>`) |
| `MethodArgumentNotValidException` | **400 BAD_REQUEST** | `Bad Request` | `<field>: <defaultMessage>` joined with `; ` for every field error |
| `HttpMessageNotReadableException` | **400 BAD_REQUEST** | `Bad Request` | `Malformed request body` |
| `NoResourceFoundException` | **404 NOT_FOUND** | `Not Found` | `Not found` |
| `Exception` (catch-all) | **500 INTERNAL_SERVER_ERROR** | `Internal Server Error` | `Something went wrong` (the stack trace is logged at ERROR: `Unhandled error in the user service`) |

### Idempotency handling

| Mechanism | Detail |
|---|---|
| Verification tokens | Single use enforced by the atomic `consume(...)` update |
| Registration | Concurrent inserts are caught as `DataIntegrityViolationException` and turned into the same generic message |
| Outbox | The relay locks the pending rows (`PESSIMISTIC_WRITE`), publishes with the producer's `enable.idempotence: true`, and stops the batch on the first failure so ordering is preserved |

There is **no** `processed_events` or deduplication table in this service — that mechanism lives in `account-service` and `notification-service`.

### Other notable rules

- `app.registration.verification-required: false` makes a new user `ACTIVE` immediately, and no verification event or token is produced.
- A `PENDING_VERIFICATION` user who registers again has their **password and name overwritten** and gets a fresh verification link.
- E-mail addresses are stored lower-cased and trimmed; e-mail uniqueness is enforced by the database (`unique = true` on `users.email`).
- `VerificationTokens` uses `SecureRandom`; `Hashing` uses SHA-256 (`HexFormat.of().formatHex`).
- The `AsyncConfig` `emailExecutor` bean has core pool 2, max pool 4, queue capacity 200, thread prefix `email-`, `waitForTasksToCompleteOnShutdown=true` and `awaitTerminationSeconds=10`. Its rejection handler logs `Email queue is full, a message was dropped` and never blocks a registration response.

## Running It

**Prerequisites**

| Requirement | Detail |
|---|---|
| Java | 17 |
| Maven | 3.6+ (or use the bundled `./mvnw`) |
| PostgreSQL | 16, reachable on `127.0.0.1:5433`, with the `user_db` database |
| Redis | Reachable on `127.0.0.1:6379` (the rate limiter fails open, so it is not strictly required) |
| Kafka | Broker on `localhost:9092`, topic `user.events` created by `event-bus-service` |
| Eureka | `discovery-service` on port 8761 (only needed when not using the whole system) |
| `notification-service` | Port 8087, only needed for the legacy direct e-mail path |

**Build**

```bash
cd /home/bs01092/Projects/banking-microservices/user-service
mvn clean package -DskipTests        # or: ./mvnw clean package -DskipTests
# jar: target/user-service-0.0.1-SNAPSHOT.jar
```

**Run**

```bash
java -jar target/user-service-0.0.1-SNAPSHOT.jar
# or with the wrapper: ./mvnw spring-boot:run
```

**Environment variables**

```bash
export DB_URL='jdbc:postgresql://127.0.0.1:5433/user_db'
export DB_USERNAME='banking_admin'
export DB_PASSWORD='postgre2188'
export REDIS_HOST='127.0.0.1'
export REDIS_PORT='6379'
export REDIS_PASSWORD='redis2188'
export KAFKA_BOOTSTRAP_SERVERS='localhost:9092'
export EUREKA_URL='http://localhost:8761/eureka'
export JWT_SECRET='78f2b81005ac23edf307e4368594d68fb1038cc667bc12f5b99946b8ea9e5ccb'
export EVENT_CRYPTO_KEY='pxUXlFhqre/31HUOw+mT9Oz80+rJz33fRtXx/kcGeTk='
export VERIFY_URL='http://localhost:3000/verify.html'
export SERVER_PORT='8083'
```

**Check it is healthy**

```bash
curl -s http://127.0.0.1:18083/actuator/health
# {"status":"UP"}

curl -s http://127.0.0.1:18083/actuator/prometheus | head
```

The Actuator port is private on purpose: the management port (`18083`) serves health, info and prometheus, while the public port (`8083`) must not expose `/actuator`.

**Stop it**

```bash
# if started in the foreground
Ctrl+C

# whole system
cd /home/bs01092/Projects/banking-microservices
./stop-all.sh
```

**Whole-system start** (from the repository root): `./run-all.sh`, or `./run-all.sh --build` to rebuild each jar first. `run-all.sh` starts `user-service` on port `8083` with database `user_db` and sets `SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:5433/user_db`, `SPRING_DATASOURCE_USERNAME=$DB_USER` and `SPRING_DATASOURCE_PASSWORD=$DB_PASSWORD` from `run.env`. Health and metrics check: `./check-all.sh` (expects `user-service` on `8083` / metrics on `18083`).

## API Quick Reference (curl examples)

Register (always 202, whatever the outcome), verify the e-mail (the token comes from the link in the verification e-mail) and log in — all three directly on port 8083:

```bash
curl -i -X POST http://localhost:8083/api/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"fullName":"Ada Lovelace","email":"ada@example.com","password":"correct-horse"}'

curl -i -X POST http://localhost:8083/api/auth/verify \
  -H 'Content-Type: application/json' \
  -d '{"token":"<token from the e-mail link>"}'

curl -i -X POST http://localhost:8083/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"ada@example.com","password":"correct-horse"}'
```

Use the returned token on the other services. Through the API gateway on port 8080 — where the rate limits apply — the same calls look like this:

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"ada@example.com","password":"correct-horse"}' | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')

curl -i http://localhost:8080/api/accounts \
  -H "Authorization: Bearer $TOKEN"
```

Resolve a user through the internal endpoint (direct call; the gateway answers 404 for `/internal/**`):

```bash
curl -i http://localhost:8083/internal/users/1
```

## Configuration Reference (quick table)

| Knob | Default | Change it when |
|---|---|---|
| `server.port` | `8083` | The port is already taken |
| `management.server.port` | `18083` | The metrics port conflicts with another service |
| `DB_URL` | `jdbc:postgresql://127.0.0.1:5433/user_db` | The database is not on the default host/port |
| `DB_USERNAME` | `banking_admin` | The database user differs |
| `DB_PASSWORD` | `postgre2188` | The database password differs |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | `127.0.0.1` / `6379` / `redis2188` | Redis is elsewhere (the limiter fails open if it is unreachable) |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | The broker is elsewhere |
| `EUREKA_URL` | `http://localhost:8761/eureka` | The discovery server is elsewhere |
| `JWT_SECRET` | `78f2b81005ac23edf307e4368594d68fb1038cc667bc12f5b99946b8ea9e5ccb` | **Always set it to the same value as the API gateway** |
| `EVENT_CRYPTO_KEY` | `pxUXlFhqre/31HUOw+mT9Oz80+rJz33fRtXx/kcGeTk=` | **Always set it to the same value as notification-service** |
| `app.jwt.expiration-ms` | `3600000` | The token lifetime should be different (1 hour) |
| `app.registration.verification-required` | `true` | Only set to `false` for automated tests |
| `app.registration.token-ttl-hours` | `24` | The verification link should live longer or shorter |
| `app.registration.min-response-millis` | `400` | The anti-enumeration floor should be different |
| `app.registration.max-emails-per-address-per-hour` | `3` | The per-address e-mail cap should be different |
| `app.registration.verify-url` | `http://localhost:3000/verify.html` | The front-end verification page moved |
| `app.messaging.relay-interval-ms` | `1000` | The outbox should be polled more or less often |
| `app.messaging.producer-name` | `user-service` | The producer name in the envelope must change |
| `app.notification.base-url` | `http://notification-service` | The notification service has a different Eureka name |

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `401` with `Invalid email or password` for a known-good user | The e-mail was stored with different casing or whitespace, or the password is not 8–64 characters | E-mails are stored trimmed and lower-cased; log in with the lower-cased address |
| `403` with `Please verify your email address before logging in` | The user is still `PENDING_VERIFICATION` | Call `POST /api/auth/verify` with a valid token, or register again to get a fresh link |
| `400` with `Invalid or expired verification link` | The token was already used, was invalidated by a newer registration, or is older than 24 hours | Register again; a `PENDING_VERIFICATION` user gets a fresh link and all older links are invalidated |
| The gateway answers `401` with `Invalid or expired token` for a token this service just issued | `JWT_SECRET` differs between the gateway and this service | Set the same `JWT_SECRET` in both. The two defaults are different. |
| No verification e-mail arrives | `EVENT_CRYPTO_KEY` differs from `notification-service`'s key, or Kafka is unreachable | Check that the key matches and that the `user.events` topic exists; look for `outbox.publish.failures` on port 18083 |
| `outbox.pending.events` keeps growing | The relay cannot reach Kafka | Check `KAFKA_BOOTSTRAP_SERVERS` and the broker; the relay logs `Outbox: could not publish <type> <id> to <topic>: <reason>` |
| `500` with `Something went wrong` on `GET /internal/users/{id}` | A non-numeric id was passed | The path variable must be a number; a mismatch is not handled specially and falls into the catch-all |
| The service fails to start with `EVENT_CRYPTO_KEY is not valid Base64` | The key is not Base64 | Set a Base64 32-byte value: `openssl rand -base64 32` |
| The service fails to start with `EVENT_CRYPTO_KEY must be 32 bytes (use: openssl rand -base64 32)` | The decoded key is not 32 bytes long | Generate a new key with `openssl rand -base64 32` |
| The registration form always answers "check your inbox" but nothing happens | Anti-enumeration: the address was already registered and the user is `ACTIVE` | That is the intended behaviour — the message is identical for every outcome |
| `401` with `Missing or invalid Authorization header` from the gateway on an internal path | The gateway blocks `/internal/**` with a 404 by design | Call the internal endpoint directly on port 8083 |
| The connection to Redis fails in the log but registration still works | The rate limiter fails open by design | Set the `REDIS_*` variables to the right values if the per-address cap matters |

## Notes and Known Limitations

- **`@EnableAsync` is missing.** `UserServiceApplication` has `@SpringBootApplication`, `@ConfigurationPropertiesScan` and `@EnableScheduling`, but no `@EnableAsync`. The `@Async("emailExecutor")` on `NotificationEmailDispatcher.send` is therefore **not active** — the method runs synchronously on the caller thread. The `emailExecutor` bean and its queue exist but are unused.
- **Two e-mail paths coexist.** The verification e-mail goes out over Kafka (`EmailVerificationRequested` → `notification-service`). The direct `NotificationEmailDispatcher` path only covers the `ALREADY_REGISTERED` and `ACCOUNT_READY` outcomes, and it posts to `notification-service` `POST /internal/notifications/email` with the body `{"to":..., "subject":..., "message":...}`. `NotificationEmailDispatcher` has `MAX_ATTEMPTS = 3` with a retry wait of `RETRY_WAIT_MILLIS * attempt` (1000 ms, then 2000 ms), logs and never throws. The recipient address is deliberately never logged.
- **`app.registration.min-response-millis` is a floor, not a delay.** It only slows the response down; it never makes it faster.
- **The Redis rate limiter only guards the legacy direct path.** The Kafka verification mail is not rate-limited by this service — only by the gateway's `/api/auth/register` limit of 10 per hour.
- **No `application-local.yml`.** The `local` profile is not defined for this service, and `run-all.sh` explicitly unsets `SPRING_PROFILES_ACTIVE` (*"The 'local' profile must NOT be active in the integrated system."*).
- **The `.env` file is local-only.** `src/main/resources/.env` is not present in the repository, but `user-service/.env` exists locally and is git-ignored. Its contents are `DB_PASSWORD`, `DB_USERNAME`, `REDIS_PASSWORD`, `EVENT_CRYPTO_KEY` and `JWT_SECRET`. The `.gitignore` ignores `.env`, `.env.*` (except `!.env.example`), `target/` and `*.log`.
- **The `users` table has no index** other than the primary key and the unique constraint on `email`.
- **`GET /internal/users/{id}` has no type-mismatch handling**, so a non-numeric id produces a 500 rather than a 400.
- **`passwordHash` is never exposed** in any DTO, but it is stored in `users.password_hash` in clear column form (BCrypt hash, not reversible).
- **The gateway's default `app.jwt.secret` differs from this service's default.** The gateway ships `banking-microservices-local-dev-secret-key-2026`; this service ships `78f2b81005ac23edf307e4368594d68fb1038cc667bc12f5b99946b8ea9e5ccb`. Both work only because `JWT_SECRET` happens to be set to the same value in `user-service/.env` and `api-gateway/.env`. Without that, no token issued here is accepted by the gateway.
- **`user-service` is a separate Git repository** (remote `https://github.com/Md-Jahid-Hasan-10/user-service.git`, branch `main`).
