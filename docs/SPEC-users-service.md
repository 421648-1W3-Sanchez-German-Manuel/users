# Especificación técnica de implementación — users-service

**Proyecto:** Plataforma Gamificada TUP · TPI · Tema 01 (Identidad y Usuarios) · UTN FRC
**Componente:** `users-service` (Spring Boot · servlet MVC) — módulos internos `auth/` + `users/`
**Fuente de verdad:** `manifiesto-users-service.html` (**revisión v5**) — LOCKEADO
**Referencias cruzadas:** `manifiesto-api-gateway.html` (v4 + parche v5), `manifiesto-flujos.html` (v5), `jwt-jwks-redis-explicado.html` (anexo), `PRD-Plataforma-Gamificada-TP.pdf`, `TUP_PIV_BE_PROPUESTA_ARQ.pdf`, contrato de sobre estándar de eventos Kafka (imagen aportada por el equipo)
**Documento par:** `spec/SPEC-api-gateway.md` — comparten la numeración de `DEC-xx` e `INC-xx`
**Estado:** listo para generación de código. Decisiones del equipo en §18.0 (`DEC-01`…`DEC-28`); lo abierto, en §18.

> **Instrucción para el agente de generación de código.**
> Este documento es autocontenido: no hace falta volver a abrir los manifiestos HTML ni los PDF.
> Todo lo afirmado acá es decisión tomada, no sugerencia. Donde dice *"X NUNCA hace Y"*, no lo implementes aunque sea técnicamente posible.
> `TODO-xx` = dato faltante. **No asumas un valor**: dejá el `TODO` visible en el código/config y seguí.
> `DEC-xx` = decisión del equipo que **no sale de ningún manifiesto**, tomada para cerrar un hueco. Implementala tal cual y dejá el comentario `// DEC-xx` en el código, para que se sepa que hay que reflejarla de vuelta en la documentación.
> §18 lista inconsistencias entre documentos fuente. Las que no tienen `DEC` asociado **no las resuelvas**.

---

## Índice

1. [Alcance y principios no negociables](#1-alcance-y-principios-no-negociables)
2. [Scaffolding: coordenadas del proyecto](#2-scaffolding-coordenadas-del-proyecto)
3. [Dependencias](#3-dependencias)
4. [Estructura de paquetes y archivos](#4-estructura-de-paquetes-y-archivos)
5. [La frontera entre `auth/` y `users/`](#5-la-frontera-entre-auth-y-users)
6. [Modelo de datos](#6-modelo-de-datos)
7. [Modelo de autenticación · headers del Gateway](#7-modelo-de-autenticación--headers-del-gateway)
8. [Los tres gates de acceso](#8-los-tres-gates-de-acceso)
9. [Máquina de estados de la cuenta](#9-máquina-de-estados-de-la-cuenta)
10. [Emisión de tokens, JWKS y claves RS256](#10-emisión-de-tokens-jwks-y-claves-rs256)
11. [Redis · qué guarda y quién lo toca](#11-redis--qué-guarda-y-quién-lo-toca)
12. [2FA](#12-2fa)
13. [Kafka · sobre estándar, publicaciones y consumer](#13-kafka--sobre-estándar-publicaciones-y-consumer)
14. [Catálogo de endpoints](#14-catálogo-de-endpoints)
15. [Autorización · capa 1 y capa 2](#15-autorización--capa-1-y-capa-2)
16. [Reglas de negocio propias](#16-reglas-de-negocio-propias)
17. [Convenciones de código y errores](#17-convenciones-de-código-y-errores)
18. [⚠️ Inconsistencias detectadas](#18--inconsistencias-detectadas)
19. [Definition of Done](#19-definition-of-done)

---

## 1. Alcance y principios no negociables

`users-service` es **un solo deploy, un solo repo, una sola base relacional**, con dos paquetes internos claramente separados que no se mezclan. Es el dueño de la identidad de la plataforma y el **único emisor de tokens** del sistema.

### 1.1 Reglas duras

| # | Regla | Origen |
|---|---|---|
| U1 | `auth/` **NUNCA** accede directo a la tabla `users`. Si necesita datos de un usuario, llama a un método público de `users/`. | manifiesto §02.1 |
| U2 | `users/` **NUNCA** abre su propia conexión a Redis. Ni cliente propio, ni `RedisTemplate` inyectado. | manifiesto §02.1 |
| U3 | La comunicación entre módulos es una **llamada de método Java** (interfaz + implementación inyectada). Nunca HTTP interno, nunca una cola. | manifiesto §02.1 |
| U4 | Cada módulo vive en su propio paquete, **sin imports cruzados** salvo por las dos interfaces explícitas de §5. | manifiesto §02.1 |
| U5 | `users-service` **NO valida el JWT**. Su `Authentication` sale de los headers `X-*` que inyecta el Gateway (**DEC-08**, §7). | DEC-08 |
| U6 | La autorización de rol vive **entera acá**: capa 1 (`@PreAuthorize`) y capa 2 (regla de negocio). El Gateway no filtra por rol. | Gateway §07 · manifiesto §07 |
| U7 | No existe el prefijo `/internal/**`. Lo que distingue una llamada de servicio es el **rol `MS` del token**, no la URL. | Gateway §00 · manifiesto §12.1 |
| U8 | **Ningún borrado físico.** Toda baja es lógica (`deleted_at`). | PRD RF-NFR-01 |
| U9 | Lo asincrónico va **100 % por Kafka**, nunca por el Gateway. Publicar un evento no espera respuesta. | Gateway §02 · PDF arq §1.1 |
| U10 | `users-service` **no despacha mails.** Renderiza el contenido (asunto + HTML) y publica el mail ya armado; el despacho es de `notifications-service`, externo. | manifiesto §01.1, §06 |
| U11 | El puerto de `users-service` **no se publica** fuera de la red privada. Es la condición que hace confiables los headers de identidad (§7.4). | Gateway §06 · DEC-08 |

### 1.2 Lo que `users-service` NO hace (lista cerrada)

- ❌ No valida firmas de JWT en el camino de request (sí las **produce**).
- ❌ No consulta el JWKS de nadie (lo **publica**).
- ❌ No lee `session:{userId}` para autenticar requests entrantes — eso lo hace el Gateway. `auth/` sí la **escribe y borra**.
- ❌ No persiste `VALIDADO_PADRON` / `VALIDADO_EXCEPCION` (**DEC-09**, §13.4).
- ❌ No conoce el padrón, ni la matrícula, ni el `CursoCohorte`. Todo eso es de Cursos (Tema 02).
- ❌ No valida el código de invitación contra Cursos por su cuenta: eso es un chequeo sincrónico que hace el **frontend** contra un endpoint público de Cursos, antes del alta (§13.5).
- ❌ No implementa retención/anonimización de PII (`RF-NFR-10`) — fuera de alcance (**DEC-11**, §18 / INC-23).
- ❌ No usa `spring-boot-starter-oauth2-authorization-server`.

---

## 2. Scaffolding: coordenadas del proyecto

**DEC-15 · Paridad total con `api-gateway`.**

| Campo | Valor |
|---|---|
| Project | **Maven** |
| Language | **Java** |
| Java version | **21** |
| Spring Boot | **4.1.1** |
| Spring Cloud | **2025.1.3 "Oakwood"** — solo por el BOM, para `spring-cloud-starter-netflix-eureka-client` · ver `TODO-01` |
| Group | `ar.edu.utn.frc.tup.p4` |
| Artifact | `users-service` |
| Name | `users-service` |
| Package name | `ar.edu.utn.frc.tup.p4.usersservice` |
| Packaging | Jar |

El package **no se deriva**: lo fija literalmente el §12 del manifiesto.

`spring.application.name` = **`users-service`**. Es el mismo valor que usan, y tienen que seguir coincidiendo:

- el `serviceId` de Eureka → de ahí sale `/api/users/**` en el Gateway;
- el `aud` de todo token de servicio dirigido acá;
- el `iss` de **todos** los tokens que emitimos (**DEC-07**);
- el `producer` de los eventos Kafka es **`tema-01-users`** (**DEC-12**), que es el único identificador que **no** coincide — sigue el formato `tema-XX-nombre` del contrato común.

> ✅ **`DEC-35` · Pin verificado: Spring Cloud `2025.1.3` (Oakwood).** Cierra `TODO-01`. La matriz oficial de compatibilidad lista el tren **2025.1.x** contra *"4.0.x, 4.1.x (Starting with 2025.1.2)"*, así que `2025.1.3` + Spring Boot **4.1.1** es una combinación soportada, no una apuesta. Ya no es un `TODO`: es el valor a poner en el `<dependencyManagement>`.
>
> Igual **verificar con `mvn dependency:tree`** que el BOM de Spring Cloud no baje `spring-boot-dependencies` por debajo de 4.1.1 — importar el BOM de Boot **primero** y el de Cloud después lo garantiza. Es criterio de DoD #1.

> **DEC-28 · Puertos fijados.** Cierra `TODO-02`.
>
> | Servicio | Aplicación | Management |
> |---|---|---|
> | `api-gateway` | **8080** | 8081 |
> | `users-service` | **8082** | 8083 |
>
> El valor por defecto anterior del `jwk-set-uri` del Gateway era `users-service:8081`, que **es el puerto de management del propio Gateway**. En Docker con hosts distintos no chocaba; corriendo los dos en `localhost` para desarrollo, sí. `server.port: 8082` y `management.server.port: 8083`. Ninguno se publica fuera de la red privada (U11).

---

## 3. Dependencias

| Artefacto | Módulo | Para qué |
|---|---|---|
| `spring-boot-starter-web` | ambos | API REST. **Servlet MVC**, no WebFlux — al revés que el Gateway |
| `spring-boot-starter-data-jpa` | `users/` | Persistencia de `User`, `email_whitelist`, `service_clients`, `eventos_procesados` |
| `com.mysql:mysql-connector-j` (runtime) | ambos | Driver de **MySQL 8.4 LTS** — base relacional única del microservicio (**DEC-20**) |
| `spring-boot-starter-security` | ambos | **BCrypt** (`users/`) + el filtro de headers y `@PreAuthorize` (§7, §15). **No** se usa como resource server |
| `spring-boot-starter-data-redis` | `auth/` | Refresh revocados, códigos 2FA, rate limit de login, código de activación (`DEC-33`), `session:{userId}` |
| `spring-kafka` | ambos | Publicar mails armados, eventos con Cursos, auditoría (§13) |
| `spring-boot-starter-thymeleaf` | `shared/` | Renderiza asunto + HTML de los mails — reemplaza lo que antes hacía Mailing |
| `spring-cloud-starter-netflix-eureka-client` | ambos | Se registra en Eureka como `users-service` |
| `spring-boot-starter-actuator` | ambos | Health (incluye chequeo de **MySQL** y Redis) |
| `com.nimbusds:nimbus-jose-jwt` | `auth/` | Firma RS256, manejo de `kid`, emisión de JWT y endpoint JWKS **manuales** |
| `spring-boot-starter-validation` | ambos | Validación de DTOs de alta, onboarding, baja reforzada |
| `org.flywaydb:flyway-core` + `flyway-mysql` | `users/` | Migraciones (`db/migration/`, ya previsto en el §12 del manifiesto). ⚠️ **El DDL de MySQL no es transaccional** — ver §6.0 |
| `spring-boot-starter-test`, `spring-kafka-test`, `org.testcontainers:{junit-jupiter,mysql}` (test) | — | §19. **La misma versión de MySQL que producción**, no H2 — ver §6.0 |

**NO incluir:**

- ❌ `spring-boot-starter-oauth2-authorization-server` — trae su propio modelo de `ClientRegistration`/`OAuth2Authorization`, pensado para un flujo OAuth2 completo, no para el JWT propio de este diseño (firma con `nimbus-jose-jwt`, tabla propia `service_clients`, JWKS servido a mano). Sumarlo generaría dos modelos de "cliente"/"token" compitiendo.
- ❌ `spring-boot-starter-oauth2-resource-server` — **DEC-08**: no validamos JWT en el camino de request.
- ❌ `spring-boot-starter-webflux` — el stack es servlet.
- ❌ Cualquier starter de Gateway, LoadBalancer o Resilience4j — nada de eso es de este micro.

---

## 4. Estructura de paquetes y archivos

```
users-service/
├── pom.xml
├── Dockerfile                                  # TODO-03
└── src/
    ├── main/
    │   ├── java/ar/edu/utn/frc/tup/p4/usersservice/
    │   │   ├── UsersServiceApplication.java     # @SpringBootApplication + @EnableDiscoveryClient
    │   │   │
    │   │   ├── auth/                            # ── MÓDULO AUTH ──
    │   │   │   │                                # no importa nada de users/ salvo CredentialService
    │   │   │   ├── controllers/
    │   │   │   │   ├── AuthController.java      # login, 2fa/verify, refresh, logout, password
    │   │   │   │   └── TokenController.java     # client_credentials + JWKS
    │   │   │   ├── services/
    │   │   │   │   ├── AuthService.java
    │   │   │   │   ├── TokenService.java
    │   │   │   │   └── ServiceClientService.java   # valida clientId+secret contra service_clients
    │   │   │   ├── twofactor/
    │   │   │   │   ├── SecondFactorProvider.java   # interfaz (Strategy)
    │   │   │   │   └── EmailOtpProvider.java       # impl
    │   │   │   ├── store/
    │   │   │   │   ├── TokenStore.java             # interfaz — contrato Redis
    │   │   │   │   ├── EphemeralTokenService.java  # interfaz EXPUESTA a users/ (§5)
    │   │   │   │   └── impl/
    │   │   │   │       ├── RedisTokenStore.java
    │   │   │   │       └── RedisEphemeralTokenService.java
    │   │   │   ├── keys/
    │   │   │   │   ├── SigningKeyProvider.java     # interfaz — par RSA activo + kid
    │   │   │   │   └── impl/FileSystemSigningKeyProvider.java   # DEC-18
    │   │   │   ├── tokens/TokenClaims.java         # DEC-45a · builder con obligatorios
    │   │   │   ├── entities/ServiceClient.java
    │   │   │   ├── cli/AdminRecoveryCommand.java   # break-glass RF-ROL-04 (§16.4)
    │   │   │   └── config/AuthKeyConfig.java       # claves RS256, JWKSet en memoria
    │   │   │
    │   │   ├── users/                           # ── MÓDULO USERS ──
    │   │   │   │                                # no importa nada de auth/ salvo EphemeralTokenService
    │   │   │   ├── controllers/
    │   │   │   │   ├── UserController.java
    │   │   │   │   ├── RegistrationController.java
    │   │   │   │   └── WhitelistController.java    # TODO-05
    │   │   │   ├── services/
    │   │   │   │   ├── UserService.java
    │   │   │   │   ├── RegistrationService.java
    │   │   │   │   ├── CredentialService.java      # interfaz EXPUESTA a auth/ (§5)
    │   │   │   │   ├── impl/CredentialServiceImpl.java
    │   │   │   │   └── WhitelistService.java
    │   │   │   ├── listeners/CourseValidationListener.java
    │   │   │   ├── entities/
    │   │   │   │   ├── User.java
    │   │   │   │   └── EmailWhitelist.java
    │   │   │   ├── repositories/                   # interfaces JPA
    │   │   │   │   ├── UserRepository.java
    │   │   │   │   └── EmailWhitelistRepository.java
    │   │   │   └── enums/{Role.java, AccountStatus.java}
    │   │   │
    │   │   ├── shared/                          # ── COMÚN A LOS DOS MÓDULOS ──
    │   │   │   ├── security/
    │   │   │   │   ├── SecurityConfig.java         # DEC-08 · sin oauth2ResourceServer
    │   │   │   │   ├── GatewayIdentityFilter.java  # OncePerRequestFilter · headers -> Authentication
    │   │   │   │   ├── GatewayPrincipal.java       # record: tipo, id, roles, scopes
    │   │   │   │   └── IdentityHeaders.java        # constantes
    │   │   │   ├── gates/
    │   │   │   │   ├── AccountGateInterceptor.java # DEC-14 · los tres gates (§8)
    │   │   │   │   └── SkipAccountGate.java        # anotación de exención
    │   │   │   ├── events/
    │   │   │   │   ├── EventEnvelope.java          # DEC-12 · sobre estándar
    │   │   │   │   ├── AccountEventPublisher.java  # DEC-45b · escribe al OUTBOX, no a Kafka
    │   │   │   │   ├── OutboxPoller.java           # DEC-45b · @Scheduled -> Kafka
    │   │   │   │   ├── AccountEventListener.java   # hub de listeners entrantes
    │   │   │   │   ├── ProcessedEventRepository.java  # DEC-13 · idempotencia (consumer)
    │   │   │   │   ├── OutboxRepository.java       # DEC-45b · idempotencia (producer)
    │   │   │   │   └── entities/{ProcessedEvent.java, OutboxEvent.java}
    │   │   │   ├── notifications/
    │   │   │   │   ├── EmailType.java              # DEC-45c · enum: plantilla + asunto + eventType
    │   │   │   │   ├── EmailTemplateService.java   # Thymeleaf: EmailType + vars -> {asunto, html}
    │   │   │   │   └── NotificationEventPublisher.java
    │   │   │   └── web/
    │   │   │       ├── GlobalExceptionHandler.java # ProblemDetail uniforme
    │   │   │       └── ErrorTypes.java
    │   │   └── config/{JpaConfig.java, RedisConfig.java, KafkaConfig.java}
    │   │
    │   └── resources/
    │       ├── application.yml
    │       ├── templates/
    │       │   ├── code-2fa.html
    │       │   ├── account-activation.html
    │       │   ├── reset-password.html
    │       │   ├── whitelist-request-pending.html        # NUEVO · RF-USR-05i (DEC-11)
    │       │   └── whitelist-request-resolved.html      # NUEVO · RF-USR-05i (DEC-11)
    │       └── db/migration/
    │           ├── V1__users.sql
    │           ├── V2__email_whitelist.sql
    │           ├── V3__service_clients.sql
    │           ├── V4__processed_events.sql
    │           ├── V5__whitelist_requests.sql      # DEC-29
    │           └── V6__outbox_events.sql           # DEC-45b
    └── test/java/…                               # ver §19
```

> **TODO-03 · `Dockerfile` / `docker-compose`.** Igual que en el Gateway, pendiente. Criterio ya fijado: **red privada, sin publicar el puerto** (U11).

> **DEC-18 · Origen del par de claves RS256: PEM montados como secret.** Ningún documento lo definía (era `TODO-04`). **Cerrado por decisión del equipo** — ver §10.7 para el contrato completo. Resumen: las claves **no** se generan al arrancar; se generan una vez, viven fuera del artefacto y se montan por path. `FileSystemSigningKeyProvider` es la implementación real, no un placeholder.

> **`DEC-29` · Whitelist: tres endpoints de ADMIN + tabla de solicitudes con estado.** Cierra `TODO-05` e `INC-21`. Entidad nueva `whitelist_requests` (§6.2b) y endpoints en §14.2.

---

## 5. La frontera entre `auth/` y `users/`

> **Corrección a un malentendido frecuente:** hay **dos** interfaces cruzando la frontera, no una. Ni una más.

```
        ┌──────────────── users-service (un proceso) ────────────────┐
        │                                                            │
        │   auth/  ──── CredentialService ────────────▶  users/      │
        │           (verificar credenciales)                         │
        │                                                            │
        │   auth/  ◀─── EphemeralTokenService ─────────  users/      │
        │           (guardar/consumir en Redis)                      │
        │                                                            │
        │   auth/ → Redis        users/ → MySQL                      │
        └────────────────────────────────────────────────────────────┘
```

### 5.1 `CredentialService` — `users/` la expone, `auth/` la consume

```java
package ar.edu.utn.frc.tup.p4.usersservice.users.services;

/**
 * The only door from auth/ to the users table. The password hash NEVER leaves
 * this module: the comparison happens inside and only the result comes out.
 * The day auth/ and users/ split into two microservices, this interface becomes
 * an HTTP client without touching the rest of the code.
 */
public interface CredentialService {

    /**
     * Verifies e-mail + plaintext password against the stored BCrypt hash.
     * @return the minimum data needed to issue a token, or null when it does not match.
     *         NEVER throws on invalid credentials: that would tell "does not exist"
     *         from "wrong password" by the type of the error.
     */
    VerifiedCredentials verifyCredentials(String email, String plainPassword);

    /** What auth/ needs to decide the login and issue tokens. */
    record VerifiedCredentials(
            UUID userId,
            List<Role> roles,
            AccountStatus accountStatus,     // auth/ NO decide con esto: solo lo propaga
            boolean mustChangePassword,   // RF-USR-01
            String email,                  // para el envío del código 2FA
            String firstNames                 // para personalizar la plantilla del mail
    ) {}

    /** Password change, voluntary or from a reset. Re-hashes with BCrypt inside. */
    void updatePassword(UUID userId, String newPlainPassword);

    /** Reinforced re-verification by userId, for ADMIN deactivation (RF-ROL-06, §16.3). */
    boolean verifyPasswordOf(UUID userId, String plainPassword);
}
```

**Reglas:**

- `CredentialServiceImpl` es el **único** lugar del sistema que toca `password_hash`.
- `accountStatus` viaja en el record **solo para trazabilidad y para que `auth/` sepa qué mail mandar**; `auth/` no toma ninguna decisión de negocio con ese valor — el gate de estado lo aplica el interceptor de §8.
- `verifyCredentials` devuelve `null`, no lanza. Es lo que permite que el rate limit de login y la respuesta uniforme anti-enumeración vivan en `AuthService`.

### 5.2 `EphemeralTokenService` — `auth/` la expone, `users/` la consume

```java
package ar.edu.utn.frc.tup.p4.usersservice.auth.store;

/**
 * The only door from users/ to Redis. It exists because users/ NEVER opens its
 * own Redis connection (U2), yet it has to store the account activation code,
 * which is ephemeral and single-use - exactly the profile of a 2FA code.
 */
public interface EphemeralTokenService {

    /** Stores key -> value with a TTL. Overwrites when the key already existed. */
    void guardar(String key, String valor, Duration ttl);

    /**
     * Reads and DELETES in the same operation: single use, atomic.
     * @return the value, or Optional.empty() when it does not exist or has expired.
     */
    Optional<String> consumir(String key);

    /** Reads without deleting. For existence checks that must not consume the token. */
    Optional<String> verificar(String key);
}
```

**Uso concreto:** `RegistrationService` (módulo `users/`) genera el **código de activación** y lo guarda como `activacion:{emailNormalizado}` con TTL de 30 min; al validarlo, lo **consume** (un solo uso). Ver **`DEC-33`** en §12.1.

> `token_activacion` **no vive en la base relacional**. Estaba como columna `String` temporal en `users` en revisiones anteriores, lo cual contradecía el principio "MySQL = durable, Redis = efímero". Se movió en v5.

### 5.3 Cómo se verifica la frontera

Test de arquitectura obligatorio (ArchUnit o equivalente), en `§19`:

| Regla | Assert |
|---|---|
| U1 | Ninguna clase de `auth.**` importa `users.repositories.**` ni `users.entities.User` |
| U2 | Ninguna clase de `users.**` importa `org.springframework.data.redis.**` |
| U4 | `auth.**` solo importa de `users.**` la interfaz `CredentialService` (+ sus records y enums); `users.**` solo importa de `auth.**` la interfaz `EphemeralTokenService` |

---

---

## 5b. Patrones de diseño · **DEC-45**

Revisión explícita de dónde un patrón **gana algo concreto** y dónde sería decorado. El criterio es el mismo que llevó a retirar `DEC-43`: un patrón entra si evita un bug identificable o si borra duplicación real, no porque tenga nombre.

### 5b.1 Los que ya estaban (nombrarlos, para que sean deliberados)

| Patrón | Dónde | Qué compra |
|---|---|---|
| **Strategy** | `SecondFactorProvider` → `EmailOtpProvider` (§12) | sumar TOTP sin tocar el login |
| **Strategy** | `SigningKeyProvider` → `FileSystemSigningKeyProvider` (`DEC-18`) | que el origen de las claves no quede enterrado en un `@PostConstruct` |
| **Chain of Responsibility** | `AccountGateInterceptor` (§8) · los 9 `GlobalFilter` del Gateway | cada eslabón con una responsabilidad y un `403` propio |
| **Facade / Port** | `CredentialService` y `EphemeralTokenService` (§5) | la frontera entre módulos es una interfaz, no una convención |
| **Adapter** | `TokenStore` → `RedisTokenStore` | Redis no se filtra al dominio |
| **Repository** | las interfaces JPA | — |
| **Idempotent Consumer** | `processed_events` (`DEC-13`) | reprocesar un evento es un no-op verificable |

### 5b.2 Los cuatro que conviene sumar

#### a) `TokenClaimsBuilder` — hace **imposible** emitir un token incompleto

`DEC-44` confía en que `TokenContractTest` detecte un token al que le falte un claim. Un builder con los obligatorios en el constructor lo mueve de "se detecta en CI" a **"no compila"**:

```java
// The mandatory ones are constructor parameters: there is no way to skip them.
TokenClaims.paraPersona(userId, roles, sid, accountStatus, mustChangePassword, firstLogin)
           .conJti(jti).build();          // iss, iat, exp los pone el builder

TokenClaims.paraServicio(clientId, audience, scopes)
           .conOnBehalfOf(userId)         // el ÚNICO opcional
           .build();
```

**Lo que previene:** el bug exacto que motivaba el flag retirado. Un claim obligatorio que falta deja de ser algo que un test tiene que acordarse de mirar. `TokenContractTest` se queda igual, como red de segunda línea.

#### b) **Outbox** para publicar a Kafka — 🔴 arregla un agujero real

Hoy `AccountEventPublisher` es **fire-and-forget** (§13.5) y publica dentro de la misma transacción que el cambio de estado. Pero **Kafka no participa de la transacción de MySQL**, así que hay dos formas de romper:

| Falla | Consecuencia |
|---|---|
| Commit OK, publish falla | El alumno queda `PENDING_COURSE` y **`ALUMNO_REGISTRADO` no salió nunca**. Cursos no se entera |
| Publish OK, commit hace rollback | Se anunció un cambio que no ocurrió |

El primero es grave por una razón específica de este dominio: **`RF-USR-05e` dice que no hay estado de rechazo ni expiración**. Si ese evento se pierde, el alumno queda esperando **para siempre**, y no existe ningún mecanismo de reintento ni de rescate. No hay job de limpieza que lo saque, por diseño.

**Outbox:** el evento se **inserta en una tabla** en la misma transacción que el cambio de estado, y un poller lo publica después.

- Tabla `outbox_events`: `event_id` (PK), `topic`, `payload` (JSON), `created_at`, `published_at` nullable, `intentos`.
- `@Scheduled` cada 2 s: toma los que tienen `published_at IS NULL` con `LIMIT` + `FOR UPDATE SKIP LOCKED` (funciona en MySQL 8), publica, marca.
- Si Kafka está caído, los eventos se acumulan y salen solos cuando vuelve. **Cero pérdida.**

**El costo honesto:** una tabla, una migración, ~60 líneas de poller, y los eventos pasan de instantáneos a "≤2 s". Vale la pena porque es el espejo exacto de `processed_events`: ya resolvimos la idempotencia del **consumidor** y el lado del **productor** había quedado sin red.

> `SELECT … FOR UPDATE SKIP LOCKED` es lo que permite más de una instancia sin publicar duplicados. Sin `SKIP LOCKED`, dos pollers se bloquean entre sí.

#### c) Registro por `enum` para los mails — borra seis métodos casi iguales

Hoy hay seis tipos de mail (§13.2), cada uno con su plantilla y su asunto. En vez de seis métodos que difieren en dos strings:

```java
public enum EmailType {
    CODIGO_2FA          ("code-2fa.html",           "email.2fa.asunto"),
    ACTIVACION_CUENTA   ("account-activation.html",    "email.activacion.asunto"),
    RESET_PASSWORD      ("reset-password.html",       "email.reset.asunto"),
    SOLICITUD_PENDIENTE ("whitelist-request-pending.html",  "email.request.asunto"),
    HABILITACION_RESUELTA("whitelist-request-resolved.html","email.habilitacion.asunto"),
    ALERTA_BREAKGLASS   ("breakglass-alert.html",    "email.breakglass.asunto");
    // + eventType to notifications-service
}

EmailTemplateService.render(EmailType tipo, Map<String, Object> vars) -> {asunto, html}
```

**Lo que compra:** agregar un mail es una fila del enum y un `.html`, no un método nuevo. Y un test parametrizado puede recorrer el enum entero y verificar que **cada plantilla existe y renderiza** — hoy eso no se puede escribir sin listar los seis a mano.

#### d) Tabla de transiciones — vuelve ejecutable la lista cerrada de §9.2

§9.2 ya define la lista cerrada de transiciones válidas. Hoy es prosa que cada método valida por su cuenta con `if`. Un `EnumMap` la convierte en dato:

```java
private static final Map<AccountStatus, Set<AccountStatus>> VALID_TRANSITIONS = Map.of(
    PENDING_EMAIL, EnumSet.of(ACTIVE, PENDING_COURSE, DEACTIVATED),
    PENDING_COURSE, EnumSet.of(ACTIVE, DEACTIVATED),
    ACTIVE,          EnumSet.of(DEACTIVATED),
    DEACTIVATED,            EnumSet.noneOf(AccountStatus.class));   // DEACTIVATED es terminal (DEC-21)
```

Un solo `transitionTo(nuevo)` consulta el mapa y lanza `InvalidTransitionException`. **Lo que compra:** la tabla del §9.2 y el código son el mismo objeto — no pueden divergir. Y `DEACTIVATED` como terminal, que es lo que sostiene `DEC-21`, queda afirmado en una línea en vez de deducirse por ausencia.

> **No es el patrón State.** Una clase por estado para cuatro estados y cinco transiciones sería más código para el mismo comportamiento. Esto es una tabla de transiciones guardada, que es lo que el problema pide.

### 5b.3 Los que se evaluaron y **NO** entran

Dejarlos escritos evita que alguien los proponga de nuevo en tres semanas.

| Patrón | Por qué no |
|---|---|
| **State** (una clase por `AccountStatus`) | 4 estados, 5 transiciones, sin comportamiento propio por estado. La tabla de (d) hace lo mismo con 6 líneas |
| **CQRS** | No hay asimetría lectura/escritura. Un `UserRepository` alcanza |
| **Specification** | Las consultas son `findByEmail` y `countByRol`. No hay criterios componibles |
| **Factory abstracta** de tokens | Hay **dos** tipos de token y no van a ser más. Los dos métodos estáticos de (a) son la fábrica |
| **Observer** in-app | Ya tenemos eventos — por Kafka, entre servicios. Un bus interno sería un segundo mecanismo compitiendo |
| **Decorator** sobre `SecondFactorProvider` | Hay una implementación. Se suma cuando haya dos |

---

## 6. Modelo de datos

Una sola base **MySQL 8.4 LTS** (**DEC-20**), con **dueño de módulo inequívoco por tabla**.

### 6.0 Convenciones de MySQL · **DEC-20**

MySQL no es un reemplazo transparente de PostgreSQL. Estas seis reglas son obligatorias; las tres primeras **cambian el modelo**, no solo el driver.

| # | Regla | Por qué |
|---|---|---|
| 1 | **`id`: `CHAR(36)`**, el UUID en texto. Mapeo explícito con `@Column(columnDefinition = "CHAR(36)")` | MySQL no tiene tipo `uuid` nativo. Hibernate 6 mapearía `UUID` a `BINARY(16)` por defecto, que es más compacto y mejor para el índice — pero **ilegible en una consola SQL** sin `BIN_TO_UUID()`, y este `id` aparece en logs, en el header `X-User-Id` y en el claim `sub`. Con el volumen de este sistema, la legibilidad al debuggear vale más que los 20 bytes por fila. **Es la decisión de §6 que reconsideraría primero si el volumen creciera** |
| 2 | **Timestamps: `DATETIME(6)`**, nunca `TIMESTAMP`. La aplicación escribe siempre **UTC** (`Instant`); la URL JDBC lleva `connectionTimeZone=UTC&preserveInstants=true` | `TIMESTAMP` de MySQL convierte según la timezone de la sesión — dos instancias con distinta `tz` guardan valores distintos para el mismo instante — y **muere en 2038**. `DATETIME` no convierte nada. Además `DEC-12` exige ISO-8601 UTC en el sobre de eventos: una sola representación en todo el sistema |
| 3 | **Ninguna columna de tipo lista.** Una lista es una tabla hija | MySQL no tiene arrays (el `text[]` de Postgres no existe). Afecta a `scopes_permitidos` — ver §6.3 |
| 4 | **Charset `utf8mb4`, collation `utf8mb4_0900_ai_ci`** en todas las tablas | `utf8` a secas en MySQL son 3 bytes y no cubre el plano suplementario. Consecuencia deliberada: la comparación de `email` y `client_id` pasa a ser **insensible a mayúsculas**, que es lo que se quiere para un identificador de login. Igualmente el email se **normaliza a minúsculas en la aplicación** al escribir y al buscar: la collation es la red de seguridad, no el mecanismo |
| 5 | **Toda regla "chequear y después actuar" necesita `SELECT … FOR UPDATE`** | InnoDB usa `REPEATABLE READ` por defecto (Postgres usa `READ COMMITTED`). Cada transacción ve su propio snapshot: dos bajas de ADMIN concurrentes pueden **ambas** contar dos ADMIN activos y **ambas** proceder, dejando cero. Aplica a `validarNoUltimoAdmin()` (§16) |
| 6 | **Una sentencia DDL por migración Flyway**, y ningún test contra H2 | El DDL de MySQL **no es transaccional**: una migración con tres `ALTER` que falla en el segundo deja la base a medias y Flyway **no puede revertirla** — hay que arreglarla a mano. Y H2 en "modo MySQL" no reproduce ninguna de las cinco reglas de arriba: los tests van con Testcontainers sobre la misma imagen que producción |

### 6.1 `users` — dueño: `users/`

La entidad `User` es **polimórfica**: un solo tipo con un campo `role`. **No** hay clases `Estudiante`/`Profesor`, ni herencia JPA, ni tablas por subtipo.

| Campo | Tipo | Nota |
|---|---|---|
| `id` | `CHAR(36)` | UUID en texto (§6.0 regla 1). Identificador estable: es el `sub` del token y el `X-User-Id` |
| `firstNames`, `lastNames` | `String` | Identidad de la persona (`RF-USR-05`) |
| `legajo` | `String` | Se guarda; **no se valida contra padrón acá** (§13.4) |
| `email` | `String` **único** | Identificador de login. Protegido a nivel infraestructura, no de campo |
| `password_hash` | `String` | **BCrypt**. `auth/` lo consulta vía `CredentialService`, nunca directo |
| `role` | `enum Role` | `ADMIN` / `PROFESSOR` / `STUDENT` — **tres valores, ver §18 / INC-17** |
| `estado_cuenta` | `enum AccountStatus` | `PENDING_EMAIL` / `PENDING_COURSE` / `ACTIVE` / `DEACTIVATED` (§9) |
| `email_verificado` | `boolean` | Verificación de posesión del email (`RF-USR-04` paso 2) |
| `debe_cambiar_password` | `boolean` | ADMIN inicial (`RF-USR-01`) y post-recuperación. Lo lee `auth/` vía `CredentialService` |
| `github_username` | `String` | **Solo el handle**, no la URL completa — el link se arma al renderizar, evita inyección de URLs (`RF-USR-06`) |
| `avatar_ref` | `String` **nullable** | Referencia a objeto en MinIO, **no** una URL libre ni el binario. **`DEC-30`: nullable en este sprint** |
| `primer_login` | `boolean` | `true` hasta el primer login completo post-2FA. Dispara el gate de onboarding (§8) |
| `guided_tour_completado` | `boolean` | `RF-TUR-01`. El contenido del tour lo maneja otro equipo; acá solo se guarda si lo completó |
| `tyc_version_aceptada` | `String` | **NUEVO · DEC-11 · `RF-NFR-09`** — versión del texto de T&C aceptada en el alta |
| `tyc_aceptado_en` | `DATETIME(6)` UTC | **NUEVO · DEC-11 · `RF-NFR-09`** |
| `created_at`, `updated_at`, `deleted_at` | `DATETIME(6)` UTC | Auditoría de fila + **baja lógica** (`RF-NFR-01`). `deleted_at IS NULL` = activa |

**Índices:** único sobre `email`; índice sobre `role` (lo usa `validarNoUltimoAdmin`); índice sobre `estado_cuenta`.

> ✅ **`DEC-21` · Un email dado de baja SÍ se puede volver a registrar.** Cierra `TODO-07`, que ningún documento definía.
>
> **Implementación** (§6.0 regla 1 — MySQL no tiene índices únicos parciales, eso es de PostgreSQL):
>
> ```sql
> email_activo VARCHAR(255)
>     AS (IF(deleted_at IS NULL, email, NULL)) STORED,
> UNIQUE KEY uq_users_email_activo (email_activo)
> ```
>
> Funciona porque MySQL trata cada `NULL` como distinto dentro de un índice único: todas las filas dadas de baja conviven, y solo las activas compiten por el email. La columna `email` queda **sin** único propio.
>
> **Por qué se puede reusar, y no al revés:** §9 no tiene transición `DEACTIVATED → ACTIVE`. Una cuenta dada de baja **no se reactiva nunca** — quien vuelve necesita una fila nueva. Con `UNIQUE (email)` a secas, un alumno dado de baja que se reinscribe **queda bloqueado para siempre** y hace falta que un ADMIN toque la base a mano. Con emails institucionales derivados del legajo, ese caso no es hipotético: el mismo email vuelve. Una columna generada es más barata que ese agujero operativo.
>
> **Consecuencia para el alta:** el chequeo de "email ya registrado" mira **cuentas activas**, no el histórico. Y el histórico se conserva igual (`RF-NFR-01`): las filas viejas siguen ahí, con su `id` propio, y los eventos de auditoría apuntan al `id`, no al email.

**Métodos de dominio en `User`** (la lógica de transición vive en la entidad, no en el servicio):

```java
void activate();                    // PENDING_EMAIL -> ACTIVE (PROFESSOR) o -> PENDING_COURSE (STUDENT)
void activateAfterCourseValidation();  // PENDING_COURSE -> ACTIVE
void deactivate();                  // cualquiera -> DEACTIVATED + deleted_at
void completeOnboarding(String githubUsername, String avatarRef, boolean tourOk);  // avatarRef nullable · DEC-30
```

Cada uno **valida la transición** y lanza `InvalidTransitionException` si no corresponde (§9.2).

> 🔴 **`DEC-30` · MinIO queda fuera del alcance de este sprint — y eso bloqueaba el gate 3.**
>
> `avatar_ref` no es un campo suelto: el **gate de onboarding** (§8) exige GitHub + avatar + tour para soltar a la persona a la plataforma. Sin endpoint de subida, nadie puede completar el onboarding y **todo usuario nuevo queda encerrado en `403` indefinidamente**. No es un campo que se pueda "dejar para después" sin más.
>
> **Resolución:** `avatarRef` es **opcional** en `PATCH /api/users/me/onboarding`. El gate 3 se cierra con `githubUsername` + `tourOk`; si viene `avatarRef`, se guarda; si no, queda `null`. El campo, la columna y el contrato quedan tal cual — lo único que falta es el endpoint de subida.
>
> **Cuando MinIO entre** (`TODO-06`, sprint siguiente): se agrega el endpoint de subida y **se decide entonces** si el avatar pasa a ser obligatorio. Volver a hacerlo obligatorio es un cambio de una línea en la validación; migrar usuarios encerrados en un `403` no lo es. El orden importa.

### 6.2 `email_whitelist` — dueño: `users/`

Emails habilitados para alta de PROFESSOR (`RF-USR-02`). Campos: `id`, `email` (único), `agregado_por` (userId del ADMIN), `created_at`, `deleted_at`.

### 6.2b `whitelist_requests` — dueño: `users/` · **DEC-29**

`RF-USR-02` dice que un PROFESSOR puede **solicitar** a un ADMIN que agregue un email a la lista blanca. Ningún documento lo modelaba: ni endpoint, ni entidad, ni evento. Se modela como **solicitud con estado**, no como un mail suelto, para que quede trazable quién pidió qué y quién resolvió.

| Campo | Tipo | Nota |
|---|---|---|
| `id` | `CHAR(36)` | — |
| `email_solicitado` | `String` | el que se pide agregar. Normalizado a minúsculas |
| `solicitado_por` | `CHAR(36)` | userId del PROFESSOR |
| `reason` | `String` | texto libre; es lo que el ADMIN lee para decidir |
| `status` | `enum RequestStatus` | `PENDING` / `APPROVED` / `REJECTED` |
| `resuelto_por` | `CHAR(36)` nullable | userId del ADMIN |
| `motivo_rechazo` | `String` nullable | obligatorio si `REJECTED` |
| `created_at`, `resuelto_en` | `DATETIME(6)` UTC | — |

**Reglas:**

- Único sobre `email_solicitado` **entre las `PENDING`** — misma técnica de columna generada que `DEC-21`, porque MySQL no tiene índices parciales. Dos profesores pidiendo el mismo email no crean dos solicitudes abiertas; la segunda recibe `409`.
- **Aprobar es atómico:** en la misma transacción se marca `APPROVED` y se inserta en `email_whitelist`. No hay estado intermedio donde la solicitud esté aprobada y el email no habilitado.
- Aprobar un email **que ya está** en la whitelist es un no-op sobre la lista, pero igual cierra la solicitud.
- **Nada se borra:** una solicitud rechazada queda con su motivo. `RF-NFR-01`.

**Notificaciones** (§13.2): al crearse, mail a **todos los ADMIN activos**; al resolverse, mail al PROFESSOR solicitante con el resultado y, si fue rechazo, el motivo.

### 6.3 `service_clients` — dueño: `auth/`

`clientId` + `secret_hash` (**BCrypt**, igual que la password de una persona) de los micros que piden tokens de servicio. Campos: `id` (`CHAR(36)`), `client_id` (único), `secret_hash`, `descripcion`, `created_at`, `deleted_at` (`DATETIME(6)`).

**`scopes_permitidos` es una tabla hija, no una columna** (§6.0 regla 3): `service_client_scopes(service_client_id CHAR(36) FK, scope VARCHAR(64))`, con único sobre el par. En JPA, `@ElementCollection`.

- **Por qué tabla hija y no una columna `JSON`** (que MySQL 8 soporta): la tabla hija da FK real, índice único sobre `(service_client_id, scope)` y un `WHERE scope = ?` directo. La validación de §10.2 — "el scope pedido está entre los permitidos de esta fila" — queda como una consulta, no como un parseo.
- **Por qué no un string separado por comas:** `users.profile.read` es un valor de seguridad, y un `LIKE` sobre una lista concatenada matchea prefijos ajenos.

- Vive en **MySQL**, no en Redis: no expira.
- El secret en claro existe **una sola vez**, cuando se genera y se entrega. No se puede recuperar: se regenera.
- `POST /api/users/public/auth/token` **nunca** emite un scope fuera de `scopes_permitidos` de esa fila.

### 6.4 `processed_events` — dueño: `shared/` · **DEC-13**

Idempotencia de los consumers Kafka. Campos: `event_id VARCHAR(64)` (PK, el `eventId` del sobre estándar), `event_type`, `procesado_en DATETIME(6)`.

El listener escribe la fila **en la misma transacción** que el cambio de estado. Reprocesar el mismo evento es un no-op verificable, no solo "no debería duplicar nada".

**Mecanismo · `DEC-20` regla 5:** intentar el `INSERT` y **capturar la `DataIntegrityViolationException`** de PK duplicada → evento ya procesado, se descarta. **No** un `SELECT` previo seguido de un `INSERT`: bajo `REPEATABLE READ` dos consumers concurrentes con el mismo `eventId` pueden **ambos** ver la fila ausente. Tampoco el `INSERT IGNORE` de MySQL, que además del duplicado silencia errores que no lo son — capturar la excepción es portable y preciso.

### 6.4b `outbox_events` — dueño: `shared/` · **DEC-45b**

El espejo de `processed_events`: aquélla da idempotencia al **consumidor**, ésta da entrega garantizada al **productor**.

Campos: `event_id VARCHAR(64)` (PK, el mismo `eventId` del sobre), `topic VARCHAR(255)`, `payload JSON`, `created_at DATETIME(6)`, `published_at DATETIME(6)` **nullable**, `intentos INT`.

- El evento se inserta **en la misma transacción** que el cambio de estado que lo origina. Si la transacción hace rollback, el evento no existe: no se puede anunciar algo que no pasó.
- `OutboxPoller` (`@Scheduled`, cada 2 s) toma un lote con `published_at IS NULL`, lo publica y lo marca.
- Índice sobre `(published_at, created_at)` para que el poller no escanee la tabla entera.

> 🔴 **El `SELECT` del poller lleva `FOR UPDATE SKIP LOCKED`.** Es lo que permite más de una instancia sin publicar duplicados: cada poller toma filas distintas en vez de bloquearse contra el otro. MySQL 8 lo soporta.

> **Los eventos publicados no se borran** (`RF-NFR-01`). Si la tabla molestara, se archiva por `published_at`; no es un problema a este volumen.

### 6.5 Lo que NO existe

- ❌ `two_factor_secret` como campo de `User` — el 2FA es por email, no TOTP: no hay secreto que guardar.
- ❌ Tabla separada de credenciales en `auth/` — el hash vive en `users`, un solo lugar.
- ❌ `tipo_validation_curso` / `VALIDADO_PADRON` / `VALIDADO_EXCEPCION` — **DEC-09**, es de Cursos (§13.4).
- ❌ Estado "pendiente de 2FA" — el código vive solo en Redis con su TTL (§12).
- ❌ Cualquier tabla de retención/anonimización (`RF-NFR-10`) — **DEC-11**, fuera de alcance.

---

## 7. Modelo de autenticación · headers del Gateway

> **DEC-08 · `users-service` NO valida el JWT.** Su `Authentication` sale exclusivamente de los headers `X-*` que inyecta el Gateway.

### 7.1 Por qué, y qué lo sostiene

El Gateway ya validó firma RS256, `iss`, `exp` y — para tokens de persona — `sid` contra Redis. Revalidar acá sería verificar nuestra propia firma con nuestra propia clave: costo sin beneficio.

Lo que hace confiables a los headers **no es el Gateway, es la red**:

1. El Gateway **borra** todo header con nombre reservado antes de inyectar los validados (anti-spoofing).
2. La red impide que un cliente le hable directo al micro — **Docker:** red privada, sin publicar el puerto de `users-service`; **Kubernetes:** `ClusterIP` + `NetworkPolicy`.

**Si falta (2), los headers no valen nada.** Por eso U11 es una regla, no una recomendación de despliegue.

### 7.2 Headers que llegan · contrato exacto

| Header | Cuándo | Valor |
|---|---|---|
| `X-Principal-Type` | request autenticado | `user` \| `service` |
| `X-User-Id` | `type: user` | UUID de la persona (el `sub`) |
| `X-Service-Id` | `type: service` | `sub` del servicio llamador (ej. `cursos-service`) |
| `X-User-Roles` | `type: user` | **coma sin espacio**: `STUDENT` \| `PROFESSOR` \| `ADMIN` |
| `X-Service-Scopes` | `type: service` | **coma sin espacio**, `MS` primero: `MS,users.profile.read` |
| `traceparent` | siempre | W3C Trace Context |
| `X-Request-Id` | siempre | Id de request, para correlacionar logs |

Formato fijado por **DEC-05** (spec del Gateway §10.1b): separador `,` sin espacios, sin valores vacíos, sin coma final, roles antes que scopes.

### 7.3 `GatewayIdentityFilter` — contrato

`OncePerRequestFilter`, registrado **antes** de `UsernamePasswordAuthenticationFilter`.

**Tres casos, y hay que manejar los tres:**

| `X-Principal-Type` | Authentication | Authorities |
|---|---|---|
| `user` | autenticado, principal = `GatewayPrincipal(USER, userId, roles, [])` | `ROLE_STUDENT` / `ROLE_PROFESSOR` / `ROLE_ADMIN` |
| `service` | autenticado, principal = `GatewayPrincipal(SERVICE, serviceId, [MS], scopes)` | `ROLE_MS` + cada scope como authority **plana, sin prefijo** |
| **ausente** | **no se setea nada** — queda anónimo | — |

> **El tercer caso es el que rompe implementaciones.** Las rutas `/api/users/public/**` llegan **sin ningún header de identidad**: el Gateway borra los reservados y no inyecta nada porque no hay token. Login, registro, refresh, `client_credentials` y activación caen ahí. El filtro **no debe** asumir que siempre hay identidad, ni rechazar por su ausencia — de eso se encarga `SecurityConfig`.

**Mapeo de authorities** (el que hace que `hasRole('MS')` y la validación de scope funcionen sobre el mismo `Authentication`):

```java
// X-User-Roles: "STUDENT"          -> [ROLE_STUDENT]
// X-Service-Scopes: "MS,users.profile.read"
//                                  -> [ROLE_MS, users.profile.read]
```

**Validaciones defensivas del filtro** — si alguna falla, responde `401` y **no** setea `Authentication`:

- `X-Principal-Type` presente pero con un valor distinto de `user`/`service`.
- `type: user` sin `X-User-Id`, o con un `X-User-Id` que no parsea como UUID, o sin `X-User-Roles`.
- `type: service` sin `X-Service-Id`, o cuyo `X-Service-Scopes` no contiene `MS`.

No es desconfiar del Gateway: es fallar ruidoso si alguien alguna vez expone el puerto.

### 7.4 `SecurityConfig`

```java
http
  .csrf(csrf -> csrf.disable())                 // API stateless, sin cookies
  .sessionManagement(s -> s.sessionCreationPolicy(STATELESS))
  .httpBasic(AbstractHttpConfigurer::disable)
  .formLogin(AbstractHttpConfigurer::disable)
  .addFilterBefore(gatewayIdentityFilter, UsernamePasswordAuthenticationFilter.class)
  .authorizeHttpRequests(a -> a
      .requestMatchers("/api/users/public/**").permitAll()
      .requestMatchers("/.well-known/jwks.json").permitAll()
      .requestMatchers("/actuator/health/**").permitAll()
      .anyRequest().authenticated())
  .exceptionHandling(e -> e
      .authenticationEntryPoint(problemDetail401)
      .accessDeniedHandler(problemDetail403));
```

- **Sin `oauth2ResourceServer`.** No hay `JwtDecoder`, no hay `jwk-set-uri`, no hay validadores de token en el camino de request.
- `@EnableMethodSecurity` activado — toda la capa 1 es `@PreAuthorize` (§15).
- `PasswordEncoder` = `BCryptPasswordEncoder`, inyectado **solo** en `CredentialServiceImpl` y en `ServiceClientService`.

### 7.5 `on_behalf_of` — **DEC-10**

Como `users-service` no parsea el JWT, el claim `on_behalf_of` **no le llega**. Queda registrado en los logs del Gateway junto al `traceparent`.

**Consecuencia asumida:** el evento de auditoría que publica `users-service` **no puede** incluir en nombre de quién actuó un servicio. Para auditar eso se cruzan los logs del Gateway por `traceparent`. Es coherente con "`on_behalf_of` es trazabilidad, no permisos".

**NO** crear un header `X-On-Behalf-Of`, y **NO** leer el JWT sin verificarlo para obtenerlo.

---

## 8. Los tres gates de acceso

> **DEC-14 · Se implementan como `HandlerInterceptor`, no como `@PreAuthorize`.** Son condiciones del **estado de la cuenta**, no del rol: mezclarlas con la capa 1 obligaría a repetir la misma condición en cada anotación, y a que cada controller sepa de estados.

Son **tres gates independientes**, se evalúan en orden y cada uno tiene su propio código de error. Solo aplican a requests con `X-Principal-Type: user` — un token de servicio (`MS`) **no atraviesa ningún gate**, porque no representa una persona con cuenta.

| Orden | Gate | Condición de bloqueo | Respuesta | Rutas exentas |
|---|---|---|---|---|
| 1 | **Estado** | `estado_cuenta != ACTIVE` | `403` · `type: pending-account`, con `accountStatus` en el cuerpo | `GET /api/users/me` |
| 2 | **Password** | `debe_cambiar_password == true` | `403` · `type: password-change-required` | `POST /api/users/auth/password/change`, `GET /api/users/me` |
| 3 | **Onboarding** | `primer_login == true` | `403` · `type: onboarding-pending` | `PATCH /api/users/me/onboarding`, `GET /api/users/me` |

**`GET /api/users/me` es la única ruta que atraviesa los tres.** Es el mecanismo por el cual el frontend averigua qué le falta a la persona: devuelve `accountStatus`, `mustChangePassword`, `firstLogin` y `guidedTourCompleted` para que decida a qué pantalla mandarla.

**Exención por anotación:**

```java
@SkipAccountGate({Gate.ESTADO, Gate.PASSWORD, Gate.ONBOARDING})
@GetMapping("/me")
public UserMeResponse me() { … }
```

El interceptor lee la anotación del `HandlerMethod`. Sin anotación, aplican los tres.

**Detalle de implementación:** el interceptor necesita el `estado_cuenta`/`debe_cambiar_password`/`primer_login` de la persona, que **no vienen en los headers** — hay que leerlos de la base por `X-User-Id`. Es **una query por request autenticado de persona**. Para `RF-NFR-03` (120 concurrentes) alcanza con el caché de primer nivel de JPA y un `@Cacheable` de vida corta si hiciera falta; no optimizar antes de medir.

> **Alcance del gate · `DEC-23`.** Estos tres gates son los **finos**: protegen las rutas de `users-service` con exenciones por ruta, leyendo el estado **de la base**. El bloqueo sobre rutas de *otros* micros (ej. `/api/cursos/**` con la cuenta pendiente) lo aplica el **Gateway**, con un gate **grueso** que lee los claims `est`/`pwd`/`onb` del token (§10.1) y se resume en una regla sola:
>
> *si el principal es persona y la cuenta no está habilitada, solo se permiten `/api/users/**` y `/api/*/public/**`.*
>
> Compone limpio porque **todas las rutas exentas de los tres gates finos son de `users-service`**: el Gateway no necesita saber nada de las rutas de nadie. Contrato en `SPEC-api-gateway.md` §9.6. Cierra INC-18.

---

## 9. Máquina de estados de la cuenta

### 9.1 Los estados

```
                    alta (RF-USR-05g0)
                            │
                            ▼
                   ┌─────────────────┐
                   │ PENDING_EMAIL │  cuenta creada, sin acceso
                   └────────┬────────┘
                            │ clic en el link de activación
                 ┌──────────┴──────────┐
        PROFESSOR │                     │ STUDENT
                 ▼                     ▼
          ┌─────────────┐    ┌──────────────────┐
          │   ACTIVE    │    │ PENDING_COURSE  │  espera a Cursos
          └─────────────┘    └────────┬─────────┘
                 ▲                    │ evento "validación resuelta"
                 └────────────────────┘
                            │
                            ▼  baja lógica (RF-NFR-01)
                   ┌─────────────────┐
                   │      DEACTIVATED       │
                   └─────────────────┘
```

| Estado | Significado | Acceso |
|---|---|---|
| `PENDING_EMAIL` | Cuenta creada, email sin verificar | **Ninguno.** El frontend manda directo a "validá tu mail" |
| `PENDING_COURSE` | Email confirmado; Cursos todavía no resolvió el código de invitación. **Solo STUDENT** | **Ninguno** salvo `GET /api/users/me` |
| `ACTIVE` | Ambos gates cerrados | Acceso real (tras el gate de onboarding si `primer_login`) |
| `DEACTIVATED` | Baja lógica. El registro permanece | Ninguno |

### 9.2 Transiciones válidas — lista cerrada

| Desde | Hasta | Disparador | Método |
|---|---|---|---|
| — | `PENDING_EMAIL` | alta (`registrarAlumno` / `registrarProfesor`) | constructor |
| `PENDING_EMAIL` | `ACTIVE` | activación de email, rol `PROFESSOR` | `User.activate()` |
| `PENDING_EMAIL` | `PENDING_COURSE` | activación de email, rol `STUDENT` | `User.activate()` |
| `PENDING_COURSE` | `ACTIVE` | evento "validación de curso resuelta" | `User.activateAfterCourseValidation()` |
| cualquiera | `DEACTIVATED` | `UserService.deactivate()` | `User.deactivate()` · **+ borra `session:{userId}`** |

**Cualquier otra transición lanza `InvalidTransitionException` → `409`.**

> **DEC-23 · `deactivate()` borra `session:{userId}`**, igual que el logout (`DEC-02`). Es lo que hace segura la decisión de llevar el estado de cuenta en el token: los claims `est`/`pwd`/`onb` pueden estar hasta 10 min desactualizados, pero **eso solo aplica a ganar acceso**. Al perderlo, borrar la sesión corta al instante en el Gateway, sin esperar el `exp`. Sin este borrado, una cuenta dada de baja seguiría entrando durante toda la vida de su access token. Ningún documento lo tenía.

- `PENDING_COURSE` **nunca** aplica a `PROFESSOR` ni a `ADMIN`: no tienen padrón que validar.
- `ADMIN` se crea directamente en `ACTIVE` (`UserService.crearAdmin`), no pasa por el ciclo de activación por email.
- `activateAfterCourseValidation()` sobre una cuenta que ya está `ACTIVE` es un **no-op**, no un error — es el requisito de idempotencia del consumer (§13.4).

### 9.3 Lo que NO existe — decisión explícita

- ❌ **No hay estado de rechazo.** Si el legajo nunca valida y el profesor nunca otorga la excepción, la cuenta queda en `PENDING_COURSE` **indefinidamente**. Es el comportamiento esperado por `RF-USR-05e` (*"sin registro en el padrón no hay participación posible"*), **no un caso de error**.
- ❌ **No hay expiración.** No hay job de limpieza, no hay timeout, no hay `PENDING_COURSE → DEACTIVATED` automático.
- ❌ **No hay estado "pendiente de 2FA".** Si la persona nunca ingresa el código, no se persiste ningún cambio: el intento de login simplemente no llegó a emitir tokens.
- ❌ **No hay `VALIDADO_PADRON`/`VALIDADO_EXCEPCION`** (**DEC-09**).

### 9.4 La lectura de `RF-USR-05f`, documentada a propósito

El PRD dice que un alumno pendiente *"no tiene acceso a ninguna funcionalidad de la plataforma"* y que *"no existe modo demostración ni acceso parcial"*.

Interpretación del equipo, ya fijada en el manifiesto §8.4 y que hay que sostener en la defensa:

> El **login en sí** (autenticación, emisión de tokens) puede ser técnicamente exitoso aunque la cuenta esté en `PENDING_EMAIL` o `PENDING_COURSE`. Lo que se restringe es **todo lo demás**: el token identifica a la persona, pero cualquier ruta que no sea consultar el propio estado se rechaza con `403` (gate 1, §8) hasta que `estado_cuenta = ACTIVE`. **Funcionalmente es cero acceso**, aunque el usuario haya "entrado".

Es una lectura, no una brecha — pero es una tensión real con la letra del PRD. Ver §18 / INC-19.

---

## 10. Emisión de tokens, JWKS y claves RS256

`users-service` es el **único emisor de tokens del sistema**. Todo lo de esta sección vive en `auth/`.

### 10.1 Token de persona (`access`)

```jsonc
{
  "iss":   "users-service",   // DEC-07 · OBLIGATORIO · sin esto el Gateway rechaza todo
  "sub":   "a3f1c2e4-...",    // userId (UUID)
  "roles": ["STUDENT"],
  "type":  "user",
  "jti":   "b7d9...",
  "sid":   "f0a2...",         // session id · v5
  "est":   "ACTIVE",          // DEC-23 · estado_cuenta
  "pwd":   false,             // DEC-23 · debe_cambiar_password
  "onb":   false,             // DEC-23 · primer_login (onboarding pending)
  "iat":   1730000000,
  "exp":   1730000600         // ~10 minutos
}
```

**`est` / `pwd` / `onb` · `DEC-23`.** No están en ningún manifiesto. Existen para que el **Gateway** pueda aplicar el gate grueso de cuenta sobre rutas de *otros* microservicios (§18 / INC-18, y `SPEC-api-gateway.md` §9.6). Reglas:

- Se emiten **solo** en el token de persona. El token de servicio no los lleva.
- Son un **espejo del estado al momento de emitir**, no la fuente de verdad. La fuente sigue siendo la fila en `users`, y los tres gates finos de §8 la leen de la base, no del token.
- **Desfasaje aceptado en una sola dirección.** Ganar acceso (`PENDING_COURSE → ACTIVE`, completar onboarding) tarda hasta 10 min, o es inmediato si el frontend dispara `/auth/refresh` al detectar el cambio por `GET /api/users/me`. Perder acceso **no puede esperar**: por eso `deactivate()` borra `session:{userId}` (§9.2) y el Gateway corta en el request siguiente.
- El refresh **sí** los recalcula: `TokenService.rotar()` relee el estado de la base al emitir el access nuevo. Es lo que hace que "refrescar" sea el mecanismo de propagación rápida.

### 10.2 Token de servicio (rol `MS`)

```jsonc
{
  "iss":          "users-service",      // DEC-07
  "sub":          "cursos-service",     // clientId de quien pide
  "roles":        ["MS"],               // role EXCLUSIVO micro↔micro, NUNCA asignable a una persona
  "type":         "service",
  "aud":          "users-service",      // DEC-04 · OBLIGATORIO · destino para el que es válido
  "scope":        "users.profile.read", // DEC-04 · OBLIGATORIO
  "on_behalf_of": null,                 // userId si delega; si no, null. Solo trazabilidad
  "jti":          "c1e5...",
  "iat":          1730000000,
  "exp":          1730000300            // ~5 minutos
}
```

**Reglas de emisión:**

- `aud` sale del campo **`audience`** del request de `client_credentials` — **`DEC-17`**, campo que ningún manifiesto tenía (era `TODO-08` / INC-24). El body queda:

  ```jsonc
  {
    "clientId":     "cursos-service",
    "clientSecret": "…",                  // variable de entorno, NUNCA del repo
    "grantType":    "client_credentials",
    "scope":        "users.profile.read",
    "audience":     "users-service"       // DEC-17 — campo nuevo, obligatorio
  }
  ```

  **Validación obligatoria en la emisión** (`emitirServicio()`), en este orden — todas devuelven **`400`**, ninguna emite token:

  1. Falta `audience` → `400`. No hay default: un `aud` implícito es exactamente lo que `DEC-04` quiere evitar.
  2. Los `scope` pedidos abarcan **más de un prefijo** distinto (`users.*` y `mailing.*` en el mismo request) → `400`. Un token tiene un solo `aud`; no se puede servir a dos destinos.
  3. El primer segmento del scope no deriva en el `audience` pedido → `400`. Regla: `users.profile.read` → `users-service`. Es decir, `audience` debe ser igual a `<primer segmento del scope> + "-service"`.

  **Por qué un campo explícito y no derivarlo en silencio:** el modo de falla. Derivándolo, el día que un scope no matchee un `serviceId` el token sale con un `aud` incorrecto, el **Gateway** responde `403` (`DEC-04`) y el síntoma aparece en otro servicio, de otro equipo, con el token correctamente firmado y el cliente correctamente autenticado. Con el campo explícito, la misma situación falla en `POST /auth/token` con un mensaje que dice qué pasó.
- `scope` debe estar dentro de `scopes_permitidos` de la fila de `service_clients`. Si no, **`400`**, no se emite.
- **`DEC-26` · Un scope solo es emitible si su prefijo resuelve a un servicio que existe y está en la allowlist del Gateway.** La regla de derivación de `audience` es correcta, pero aplicada al catálogo cerrado del §04.0b **dos de los tres scopes emiten tokens inútiles**:

  | Scope | Qué pasa hoy | `DEC-26` |
  |---|---|---|
  | `users.profile.read` | `aud: users-service`, que está en la allowlist | ✅ **emitible** |
  | `users.padron.notify` | deriva `aud: users-service`, pero el flujo es **Kafka**: no hay request HTTP y el `aud` no lo mira nadie | ❌ **no emitible por `client_credentials`.** Se marca en `service_clients` como scope de solo-Kafka. Cierra `TODO-16` |
  | `mailing.debug.read` | apuntaba a un endpoint HTTP de mailing | ❌ 🔧 **eliminado del catálogo** por `DEC-41`: `notifications-service` es solo consumidor de Kafka |

  **Catálogo efectivamente emitible hoy: un solo scope, `users.profile.read`.** El arranque **falla** si una fila de `service_clients` tiene un scope no emitible entre sus `scopes_permitidos`: es un error de datos, no un caso a manejar en runtime.
- El rol `MS` **nunca** se asigna a una persona: no es un valor posible del enum `Role` de la entidad `User`. Es un valor que solo aparece en el claim `roles` de un token de servicio.
- Los tokens de servicio son **100 % stateless**: sin `sid`, sin escritura en Redis, sin chequeo de sesión.

### 10.3 Vigencias

| Token | Vigencia | Dónde va | Se guarda |
|---|---|---|---|
| `access` (persona) | ≈ **10 min** | `Authorization: Bearer` en cada request | sí, indirectamente · `session:{userId}` → `sid` |
| `refresh` | ≈ **7 días** | solo al endpoint de refresh | sí · `refresh:revocado:{jti}` |
| `service` (rol `MS`) | ≈ **5 min** | llamadas micro→micro | **no** · stateless |

> **Los access token ya NO son stateless.** Desde v5 llevan `sid` y el Gateway los valida contra Redis en cada request. Es un cambio deliberado (sesión única con invalidación inmediata) y un trade-off aceptado: si Redis cae, la autenticación de toda la plataforma cae con él.

### 10.4 `TokenService` — contrato

```java
public interface TokenService {
    String emitirAccess(UUID userId, List<Role> roles, String sid);
    String emitirRefresh(UUID userId);
    ParDeTokens rotar(String refreshToken);          // §10.6
    void revocar(String jti, Duration vidaRestante);
    String iniciarSesion(UUID userId);               // genera sid nuevo + escribe session:{userId}
    String emitirServicio(String clientId, String aud, String scope, UUID onBehalfOf);
}
```

### 10.5 Sesión única — qué escribe `auth/`

| Momento | Acción |
|---|---|
| Login exitoso **post-2FA** | `iniciarSesion(userId)` → `sid` nuevo, **sobrescribe** `session:{userId}` (pisa cualquier sesión anterior) |
| Refresh | **DEC-22 · NO toca la key.** No genera `sid` nuevo y no escribe Redis — ver abajo |
| **Logout** | **DEC-02 · borra `session:{userId}`** |

`session:{userId}` se guarda **sin TTL propio**: vive lo que dura la sesión activa.

> **DEC-22 · El invariante que hace analizable todo esto:**
>
> **`session:{userId}` lo escribe exactamente una operación (login post-2FA) y lo borra exactamente dos (logout `DEC-02`, y `deactivate()` `DEC-23`). Ninguna otra lo toca.**
>
> "Rotar el `sid`" mezclaba **dos preguntas independientes** (INC-01), y solo una tiene consecuencias: (1) ¿el access nuevo lleva otro valor de `sid`? (2) ¿el refresh **escribe** `session:{userId}`? Escenario: A logueado (`session:{u} = sid-A`), B se loguea (`sid-B`), A hace `/auth/refresh`.
>
> | | **Escribe Redis** | **No escribe Redis** |
> |---|---|---|
> | **`sid` nuevo** | A pisa a B y le **roba** la sesión ❌ | El access nuevo nunca valida — el refresh queda roto siempre ❌ |
> | **`sid` igual** | A **revive** y expulsa a B ❌ | A sigue expulsado ✅ |
>
> Una sola celda funciona, y es la del runbook `jwt-jwks-redis` §08 — o sea que **el anexo tiene razón y el manifiesto §04.1 está mal**, al revés de lo que sugiere la jerarquía de fuentes.
>
> ⚠️ **No confundir con la rotación del refresh token** (§10.6): ese string **sí** rota, con detección de reuso por familia. Son dos usos distintos de la palabra "rotar".

> **DEC-02 · El logout borra la key.** Efecto buscado: el logout corta el access token **al instante**, sin esperar los ~10 min de `exp`. El costo de esa lectura a Redis ya está pagado por la sesión única. `manifiesto-flujos` §05 no incluye este borrado — hay que actualizarlo.

### 10.6 Refresh con rotación y detección de reuso

1. ¿El `jti` del refresh está revocado o **ya fue rotado**?
2. **Si ya fue rotado → señal de robo:** revocar **toda la familia de tokens de ese usuario** y responder `401`. La persona tiene que re-loguearse.
3. **(DEC-22)** ¿`session:{userId}` sigue siendo igual al `sid` atado a esta familia? Si no coincide o la key no existe → **`401`** y **revocar toda la familia**.
4. Si está OK: invalidar el `jti` viejo, guardar el nuevo, emitir un par nuevo. El access nuevo lleva **el mismo `sid`** y **relee de la base** `est`/`pwd`/`onb` (§10.1).

Sin el paso 2, un refresh token robado serviría hasta expirar.

**Sobre el paso 3 · `DEC-22`.** `manifiesto-flujos` §10 dice que chequear la sesión en el refresh o dejar que el access nuevo falle en el Gateway son equivalentes ("detalle de implementación"). **No lo son.** El refresh vive **7 días**: si no se chequea acá, un dispositivo superado conserva una credencial de larga vida, robable, atada a una sesión que ya no existe. Cortarla en el momento es gratis. `TokenService.rotar()` necesita entonces el `sid` de la familia, así que el registro del refresh en Redis pasa a ser `refresh:{jti} → {userId, sid, familyId}` (§11).

### 10.7 JWKS y claves

- `auth/` firma con la **clave privada**; publica la **pública** en `GET /.well-known/jwks.json`.
- La respuesta es un `JWKSet` estándar con un array `keys`, cada una con su `kid`.
- **Rotación sin downtime:** al rotar, se publican **ambas** claves (la vieja y la nueva) mientras existan tokens firmados con la vieja; se firma con la nueva; cuando expiran los últimos tokens de la vieja (máximo la vida del refresh, 7 días), se la quita del JWKS.
- El endpoint es **público, sin autenticación**: la clave pública no es secreta.
- La ruta **no** lleva el prefijo `/api/{nombre}` — es la única excepción real a la convención (RFC 8615), porque las librerías de JWT de cualquier lenguaje la buscan exactamente ahí.

#### Origen de las claves · **DEC-18**

`SigningKeyProvider` abstrae de dónde salen; `FileSystemSigningKeyProvider` las lee de **archivos PEM montados como secret**, nunca del repositorio y nunca generados al arrancar.

| Variable de entorno | Contenido |
|---|---|
| `JWT_PRIVATE_KEY_PATH` | PEM de la clave privada **activa** (ej. `/run/secrets/jwt-private.pem`) |
| `JWT_PUBLIC_KEYS_DIR` | Directorio con **todas** las públicas vigentes, una por `kid` (ej. `/run/secrets/jwks/`) |
| `JWT_ACTIVE_KID` | El `kid` con el que se firma (ej. `2026-09`) |

- Se **firma** con `JWT_ACTIVE_KID`; el JWKS **publica todas** las públicas del directorio → la rotación no tiene ventana de `401`.
- Generación (una vez, fuera del build): `openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048`.
- **Dev local:** `scripts/gen-dev-keys.sh` genera el par en una carpeta **gitignoreada** en el primer arranque. **No se commitea ningún par**, ni siquiera uno "de mentira" — esa costumbre es la que después filtra el de producción.
- Si `JWT_PRIVATE_KEY_PATH` no existe o no parsea, la aplicación **no arranca**. No hay fallback a generación en memoria.

**Por qué no generar al arrancar** (la opción descartada): con dos instancias, cada una firma con un par distinto; el Gateway pide el JWKS, el balanceador lo manda a la instancia A, y los tokens firmados por B fallan la validación → **`401` intermitente y alternante**. Y en cada reinicio los access tokens vivos quedan inválidos mientras los refresh siguen vigentes en Redis: rompe de forma parcial, no limpia. Además el diseño ya asume claves estables — la rotación por `kid` con solapamiento y la revalidación periódica del Gateway no significan nada si el `kid` cambia en cada arranque.

**Por qué no guardarlas en la base** (la otra opción considerada, que evitaba el paso manual): pondría la clave privada de firma en la misma base que los datos de usuario. Hoy un dump de la base entrega hashes BCrypt — malo, contenible. Con la clave adentro, entrega la capacidad de **fabricar un token de ADMIN válido** para los doce microservicios. No compensa ahorrarse un `openssl`.

---

## 11. Redis · qué guarda y quién lo toca

**Dueño: `auth/`.** `users/` accede solo vía `EphemeralTokenService` (§5.2).

| Key | Valor | TTL | Escribe | Lee |
|---|---|---|---|---|
| `session:{userId}` | `sid` | **sin TTL** (se pisa en cada login, se borra en logout) | `auth/` | **el Gateway** (§7) |
| `refresh:{jti}` | `{userId, sid, familyId}` · **DEC-22** | vida del refresh (7 d) | `auth/` | `auth/` |
| `refresh:revocado:{jti}` | marca | vida restante del refresh | `auth/` | `auth/` |
| `2fa:{userId}` | código | **5 min** | `auth/` | `auth/` |
| `activacion:{emailNormalizado}` | `{userId, code, intentos}` · **DEC-33** | **30 min**, un solo uso | `users/` vía `EphemeralTokenService` | ídem |
| `reset:{token}` | `userId` | **15 min**, un solo uso | `auth/` | `auth/` |
| `ratelimit:login:{email}` | contador · **DEC-24** | ventana · `TODO-09` | `auth/` | `auth/` |

> **La única grieta en "Redis es privado de `auth/`"** es que el Gateway **lee** `session:{userId}`. Es una excepción puntual y acotada, documentada: el Gateway solo lee, nunca escribe.

> **`DEC-24` · Reparto con el rate limit del Gateway.** No están en conflicto y ninguno puede hacer el trabajo del otro: el Gateway limita **por IP** sobre `/api/users/public/auth/**` (guardia de inundación, corta **antes** del viaje a la base y del BCrypt, que es caro a propósito); `auth/` limita **por email** (guardia de fuerza bruta sobre una cuenta concreta, que el Gateway no puede ver porque no parsea el body). Claves distintas → **no pueden dispararse por la misma condición**: el que salta primero responde. Los dos devuelven `429` + `Retry-After` con el **mismo `type` de `ProblemDetail`**, para que el frontend no tenga que saber quién contestó; se distinguen en logs y métricas. Cierra INC-15.

> **`DEC-42` · Umbral fijado: 5 fallos / 15 min por email.** Cierra `TODO-09`. Cuenta **fallos**, no intentos: quien acierta la contraseña no consume presupuesto, así que un usuario legítimo nunca se topa con el límite. Al agotarse, `429` + `Retry-After` con `type: too-many-attempts` (`DEC-24`).
>
> Es un valor **estándar y conservador a propósito**: se afloja mirando métricas, pero un sistema sin límite no tiene de dónde empezar a medir. Revisar tras la primera prueba de carga — si aparecen `429` sobre tráfico legítimo, subir la ventana antes que el conteo.

---

## 12. Códigos de un solo uso · 2FA y validación de email

### 12.1 Un solo motor de OTP para dos flujos · **DEC-33**

La validación de email **deja de ser un link** y pasa a ser un **código de 6 dígitos** que la persona escribe en pantalla, igual que el 2FA. Ningún manifiesto lo dice así — todos describen `GET /registro/activate?token=…` — así que es una desviación deliberada. 🔧 **Reflejar en `manifiesto-users-service` §12.1 y `manifiesto-flujos` §03.**

**Qué se gana:**

- **Un solo motor.** `OtpService` genera, guarda, valida y limita intentos; el 2FA y la activación son dos clientes del mismo componente. Antes eran dos mecanismos con dos formatos, dos TTL y dos formas de fallar.
- **Una sola pantalla.** El frontend reusa el mismo componente de "ingresá el código". El link obligaba a deep-linking y a que el frontend rutee un `token` de query string.
- **El token deja de viajar en una URL**, donde queda en el historial del navegador, en el `Referer` y en los logs de cualquier proxy intermedio.
- **El reenvío deja de ser opcional.** Con un link de 24 h, "reenviar" era una comodidad; con un código de 30 min es obligatorio. Eso **cierra `INC-20`**: sí hay endpoint de reenvío, y es el mismo patrón para los dos flujos.

> ⚠️ **Precisión sobre "matamos dos pájaros de un tiro":** se comparte el **mecanismo**, no el **momento**. Validar el email prueba posesión de la casilla en el alta; el 2FA del login prueba posesión en cada ingreso. **La persona igual va a hacer 2FA en su primer login** — no se saltea. Lo que se unifica es el código, la pantalla y el componente, no los dos eventos.

**Contrato del código de activación:**

| | Valor | Por qué |
|---|---|---|
| Formato | 6 dígitos numéricos | igual que el 2FA; se dicta por teléfono sin ambigüedad |
| TTL | **30 min** (antes 24–48 h con link) | 🔴 **obligatorio bajarlo.** 6 dígitos son ~20 bits: aceptable por 30 min con límite de intentos, temerario por 48 h |
| Intentos | **5**, después se invalida el código y hay que pedir uno nuevo | sin esto, un código de 6 dígitos es forzable |
| Clave Redis | `activacion:{emailNormalizado}` | el reenvío **pisa** el código anterior: nunca hay dos válidos a la vez |
| Respuesta al error | `400` · `invalid-code`, **idéntica** para código incorrecto, vencido y email inexistente | anti-enumeración: no revela si esa dirección está registrada |
| Reenvío | rate-limitado por email, misma respuesta exista o no la cuenta | mismo criterio que el reset de password |

**Lo que NO cambia a código: el reset de password.** Sigue siendo un **token largo por link** (`DEC-16`), y la asimetría es a propósito: activar un email mueve una cuenta de `PENDING_EMAIL` a `PENDING_COURSE` y **no le da acceso a nadie** (sigue haciendo falta la contraseña); adivinar un reset de password **es tomar la cuenta**. Seis dígitos no alcanzan para custodiar eso. Distinto impacto, distinto mecanismo.

### 12.2 2FA del login

**Obligatorio en cada login** (`RF-NFR-02`), por **email**, sin dispositivos de confianza. No hay forma de saltearlo.

### 12.3 Dónde vive el 2FA

Detrás de la interfaz `SecondFactorProvider` (patrón **Strategy**), para poder sumar TOTP después sin tocar el login:

```java
public interface SecondFactorProvider {
    void generarDesafio(UUID userId, String email, String firstNames);
    boolean verificar(UUID userId, String code);
}
```

Implementación única: `EmailOtpProvider` — genera el código, lo guarda en Redis (`2fa:{userId}`, TTL 5 min), renderiza el mail con `EmailTemplateService` y **publica el evento**; no espera el despacho.

### 12.4 El login son dos requests, no uno

**Fase 1 — `POST /api/users/public/auth/login`** (credenciales):

1. `AuthService` llama a `CredentialService.verifyCredentials()` — **llamada de método directa, sin red**.
2. Si falla → `401` acá mismo, **sin publicar nada**.
3. Rate limit de login (¿demasiados intentos?).
4. `SecondFactorProvider.generarDesafio()`: código a Redis (TTL 5 min), plantilla renderizada, evento publicado a Kafka.
5. `200 "revisá tu correo"` — **inmediato, sin esperar el despacho real**.

**La fase 1 termina SIN token.** Solo se validaron credenciales y se publicó el mail ya armado.

**Fase 2 — `POST /api/users/public/auth/2fa/verify`** (código):

1. Comparar contra Redis. Si no coincide o venció → `401`.
2. `iniciarSesion(userId)` → `sid` nuevo, sobrescribe `session:{userId}`.
3. Firmar `access` (con ese `sid`) + `refresh` (RS256).
4. Guardar el `jti` del refresh (TTL 7 d).
5. `200 { accessToken, refreshToken }`.

**Recién acá la persona está autenticada.** Además, si es su primer login completo, es el momento en que `primer_login` sigue en `true` y dispara el gate de onboarding (§8).

### 12.5 Código vencido

No se persiste nada. La persona reintenta el login **desde la fase 1** (credenciales nuevas → código nuevo → mail renderizado y publicado de nuevo).

> **No hay endpoint de "reenviar código".** El manifiesto §06 es explícito: *"eso dispara los mismos pasos 3-4 de nuevo, no un endpoint nuevo"*. El frontend, ante un 2FA vencido, ofrece "reenviar código" como acción que **re-ejecuta la fase 1**. Ver §18 / INC-20: la redacción admite otra lectura.

### 12.6 Consecuencia operativa asumida

Un pico de logins simultáneos (`RF-NFR-03`: 120 alumnos entrando a la vez) genera **un pico de 120 emails de 2FA**. `notifications-service` tiene que estar dimensionado para eso. Si el mail nunca llega, la persona no puede completar el login — pero `users-service` no se entera ni se cuelga: los reintentos y la dead-letter queue del despacho son de `notifications-service`.

---

## 13. Kafka · sobre estándar, publicaciones y consumer

### 13.1 El sobre estándar — **DEC-12**

**Todos** los eventos que se publican al bus siguen este contrato común, fijado a nivel plataforma:

```jsonc
{
  "eventId":   "123e4567-e89b-12d3-a456-426614174000",  // UUID · trazabilidad e idempotencia
  "eventType": "NOMBRE_DEL_EVENTO",                     // ej. USUARIO_REGISTRADO
  "timestamp": "2026-09-02T19:30:00Z",                  // ISO 8601, UTC
  "producer":  "tema-01-users",                         // DEC-12
  "payload":   { }                                      // específico de cada evento
}
```

El contrato común garantiza **consistencia en la envoltura** (`eventId`, `eventType`, `timestamp`, `producer`) y **flexibilidad en los datos** (`payload`).

**Implementación:** `EventEnvelope<T>` genérico en `shared/events/`. **Ningún publisher arma el JSON a mano**: todos pasan por un método que envuelve el payload y completa los cuatro campos de la envoltura.

```java
public record EventEnvelope<T>(
        UUID eventId,          // generado al publicar
        String eventType,
        Instant timestamp,     // Instant.now() al publicar
        String producer,       // siempre "tema-01-users"
        T payload) {}
```

### 13.2 Publicaciones hacia `notifications-service` (mails)

`users-service` **renderiza** el mail (asunto + HTML, plantillas Thymeleaf propias) y publica el **mail ya armado**. `notifications-service` decide el canal y el proveedor: eso ya no es asunto nuestro.

| `eventType` | Cuándo se dispara | Plantilla | Payload |
|---|---|---|---|
| `EMAIL_2FA` | fase 1 del login, tras validar credenciales | `code-2fa.html` | `{to, asunto, html}` |
| `EMAIL_ACTIVACION_CUENTA` | alta de STUDENT o PROFESSOR, al crear la cuenta **y en cada reenvío** | `account-activation.html` — **código, ya no link** (`DEC-33`) | ídem |
| `EMAIL_ALERTA_BREAKGLASS` | **NUEVO · DEC-32** · uso del comando de recuperación de ADMIN | `breakglass-alert.html` | ídem, a todos los ADMIN activos |
| `EMAIL_RESET_PASSWORD` | pedido de recuperación | `reset-password.html` | ídem |
| `EMAIL_SOLICITUD_PENDIENTE` | **NUEVO · DEC-11** · al pasar a `PENDING_COURSE` | `whitelist-request-pending.html` | ídem |
| `EMAIL_HABILITACION_RESUELTA` | **NUEVO · DEC-11** · al pasar `PENDING_COURSE → ACTIVE` | `whitelist-request-resolved.html` | ídem |

Los dos últimos salen de **`RF-USR-05i`**, que ningún manifiesto capturó: *"Mientras el alumno esté en estado pendiente de validación, el único mensaje que recibe es un email transaccional informando el estado de su solicitud y, al resolverse, su habilitación."* Tiene sentido: un usuario pendiente **no accede a la plataforma**, así que no puede recibir notificaciones in-app (`RF-NOT-05`).

> **`DEC-34` · Nuestra mitad queda fijada; la costura es el tópico.** El sobre estándar está confirmado por el contrato de plataforma (**DEC-12**), y el `payload` de todo mail que publicamos es exactamente:
>
> ```jsonc
> { "to": "alumno@…", "asunto": "…", "html": "<!DOCTYPE html>…" }
> ```
>
> Tres campos, sin anidar. Nosotros **renderizamos** con Thymeleaf y publicamos el mail ya armado; `notifications-service` elige canal y proveedor. Esto se implementa **ahora**, sin esperar a nadie.
>
> **`TODO-10` queda reducido a una property:** `users.kafka.topics.notificaciones`, con un default. Si el equipo de notifications pide otra forma de payload, cambia `NotificationEventPublisher` y nada más — ningún flujo de negocio lo toca.

### 13.2b Regla de las costuras externas · **DEC-34**

Tres contratos dependen de otro equipo (`TODO-10` notifications, `TODO-11` Cursos). En vez de dejarlos como `TODO` abiertos que bloquean el código, se aplica una regla:

> **Definimos nuestra mitad completa y la implementamos. La costura queda en un solo lugar, nombrado, y es configuración — no rediseño.**

En concreto:

| Lo nuestro (implementable ya) | La costura (una property) |
|---|---|
| El sobre estándar (`DEC-12`), ya cerrado y confirmado por el contrato de plataforma | — |
| El `eventType` y el payload que publicamos | el **nombre del tópico**: `users.kafka.topics.*` |
| El `payload` que sabemos leer, con los campos que **no** nos importan ignorados | el nombre del tópico que consumimos |

**Dos reglas que hacen que la costura no duela:**

1. **Los consumers ignoran campos desconocidos** (`FAIL_ON_UNKNOWN_PROPERTIES = false`). Si Cursos manda tres campos de más, no rompemos. Si manda uno de menos que necesitamos, falla explícito en la deserialización, no en silencio.
2. **Todo nombre de tópico es una property con default**, nunca una constante en el código. Acordar el nombre real es cambiar una línea de `application.yml`, no recompilar.

### 13.3 Publicación hacia Cursos — alumno registrado

**Cuándo:** al cerrar `PENDING_EMAIL` de un **STUDENT**, es decir en la transición a `PENDING_COURSE`. No antes: si nunca verifica el email, Cursos no se entera de nada.

```jsonc
// ⚠️ BORRADOR · TODO-11 · no acordado con Cursos
{
  "eventId":   "…",
  "eventType": "ALUMNO_REGISTRADO",
  "timestamp": "2026-09-02T19:30:00Z",
  "producer":  "tema-01-users",
  "payload": {
    "userId":           "a3f1c2e4-…",
    "legajo":           "76543",
    "invitationCode": "PROG4-2026-A1",
    "email":            "alumno@…",       // ¿lo necesita Cursos? sin confirmar
    "firstNames":          "…",              // idem
    "lastNames":        "…"               // idem
  }
}
```

> **TODO-11 · Tópico y schema hacia Cursos, sin acordar.** El manifiesto §09 y `flujos` §09 dicen literalmente: *"Resta acordar con el equipo de Cursos el nombre de los dos tópicos y el esquema exacto de cada payload"*. Lo de arriba es **un borrador**, no un contrato. Los tres campos que **sí** están confirmados por los manifiestos son `userId`, `legajo` y `invitationCode`; el resto es propuesta. **No implementar como definitivo.**

### 13.4 Consumer desde Cursos — validación resuelta

`CourseValidationListener` (módulo `users/`), registrado en `AccountEventListener`.

```jsonc
// ⚠️ BORRADOR · TODO-11
{
  "eventType": "VALIDACION_CURSO_RESUELTA",
  "producer":  "tema-02-cursos",
  "payload": {
    "userId":    "a3f1c2e4-…",
    "resultado": "VALIDADO_PADRON",   // | VALIDADO_EXCEPCION — mismos firstNames que el enum
                                      // EstadoValidacion de Cursos
    "cursoId":   "…"
  }
}
```

**Qué hace el listener:**

1. **Idempotencia (DEC-13):** ¿`eventId` ya está en `processed_events`? Si sí → descartar, `ack`.
2. `User.activateAfterCourseValidation()` → `PENDING_COURSE → ACTIVE`. Si ya estaba `ACTIVE`, **no-op**.
3. Publicar `EMAIL_HABILITACION_RESUELTA`.
4. Insertar el `eventId` en `processed_events`, **en la misma transacción** que el paso 2.

**Qué NO hace — DEC-09:**

> **`users-service` NO persiste `resultado` ni `cursoId`.** Solo los usa para decidir el gate y los descarta. El dueño de ese dato es **Cursos**: su entidad `Matricula` tiene `estadoValidacion`, y la traza de quién otorgó una excepción, cuándo y por qué (`RF-USR-05h`) vive en su entidad `ValidacionExcepcion`. Duplicarlo acá sería una segunda fuente de verdad para el mismo dato — que es exactamente lo que la revisión v5 corrigió.
>
> **Consecuencia:** la pantalla de `RF-USR-05h` (*"esa marca es visible para ADMIN"*) la sirve **Cursos**, no `users-service`. Va como dependencia externa, no como funcionalidad nuestra.

**Tiempo entre los dos eventos: variable.** Puede ser segundos (el legajo está en el padrón) o **días** (espera a que el profesor resuelva). No hay timeout. Si el consumer estaba caído, Kafka retoma desde el último offset commiteado: el evento no se pierde, solo se demora.

#### 13.4b Cómo se entera el alumno · **DEC-34**

El flujo acordado con el equipo, de punta a punta:

1. El alumno valida su email → `PENDING_COURSE`. Publicamos `ALUMNO_REGISTRADO`.
2. **Cursos** busca el legajo en el padrón. Si está → resuelve solo. Si no está → **notifica al PROFESSOR** con los datos de la solicitud, y el profesor acepta o rechaza.
3. Cursos publica `VALIDACION_CURSO_RESUELTA`. Nosotros consumimos y pasamos a `ACTIVE` (§13.4).
4. El alumno, mientras tanto, está en una pantalla de "tu solicitud fue enviada" y **consulta `GET /api/users/me`** — por polling suave o con un botón de "actualizar estado". Cuando `accountStatus` pasa a `ACTIVE`, el frontend lo suelta.

**No hace falta ningún endpoint nuevo:** `GET /api/users/me` ya devuelve `accountStatus` y ya está exenta de los tres gates (§8) justamente para esto. Era el caso de uso que esa exención existía para cubrir.

**Tampoco hacen falta websockets.** El estado cambia como mucho una vez en la vida de la cuenta y el usuario está mirando la pantalla: un polling de 10–15 s durante esa pantalla, y nada el resto del tiempo, es más barato que sostener una conexión.

> ⚠️ **Sobre la pantalla de "Validando credenciales…" con spinner:** ese spinner es **cosmético**. El flujo real es asincrónico y puede tardar días, así que la pantalla tiene que **degradar sola** a "tu solicitud fue enviada" después de unos segundos, no quedarse girando. Los dos desenlaces del mockup (encontrado en padrón / fuera de padrón) no son una bifurcación que ocurra en ese instante: son dos formas en que puede terminar un proceso que ya arrancó. Vale decirlo porque el mockup sugiere una consulta sincrónica a SysAcad que **no existe** en esta arquitectura.

> ✅ **Lo que esto confirma:** el diseño ya soportaba este flujo sin cambios. La decisión de `DEC-09` (no persistir `VALIDADO_PADRON`/`VALIDADO_EXCEPCION`) sigue en pie: para el alumno, "aprobado por padrón" y "aprobado por excepción del profesor" son el mismo `ACTIVE`. La distinción le importa a Cursos, que es quien la guarda.

### 13.5 Dependencia externa · validación sincrónica del código de invitación

El código de invitación se valida **al tipearlo en el formulario** (feedback inmediato, antes de enviar el alta). Eso es un **chequeo sincrónico contra un endpoint público de Cursos** (*"¿este código corresponde a un `CursoCohorte` activo?"*), no un evento.

- Ese endpoint es **de Cursos**, dueño de `CursoCohorte.invitationCode`. `users-service` **no lo modela ni lo llama**.
- `RF-USR-05g1` agrega dos reglas que confirman la propiedad: el código es **único por curso** (no por alumno) y **deja de ser válido cuando el curso se archiva**.
- Si el código no existe o no corresponde a una cohorte activa, el formulario pide uno válido **antes** de dejar avanzar el alta: **no se crea la cuenta con un código inválido**.
- `users-service` guarda el código recibido y lo reenvía en el evento. **No lo valida.**

### 13.6 Eventos de auditoría

`AccountEventPublisher` — **desde `DEC-45b` escribe al outbox, no a Kafka** (§6.4b); la publicación real la hace `OutboxPoller`. Para quien lo llama sigue siendo fire-and-forget, pero ahora la entrega está garantizada. Se publica para **toda acción sensible de identidad**: alta de ADMIN, baja de cualquier usuario, cambio de rol, recuperación de ADMIN server-only, baja reforzada.

Payload mínimo fijado por el manifiesto §8.3:

```jsonc
{ "accion": "…", "actorId": "…", "targetId": "…", "timestamp": "…" }
```

Envuelto en el sobre estándar. **Quién consume ese tópico, cómo se persiste y una eventual pantalla de consulta para ADMIN quedan fuera de alcance** — son de Backoffice (Tema 12) o de un consumer futuro. Acá solo se define el contrato de emisión: `users-service` publica, no le importa quién (o si alguien, por ahora) lo consume.

> **`DEC-37` · Convención de nombres de tópicos, y el de auditoría.** Cierra `TODO-12`. Ningún documento define cómo se nombran los tópicos de la plataforma, solo el **sobre** (`DEC-12`). Se adopta:
>
> ```
> <producer>.<asunto>.<version>
> ```
>
> con `<producer>` en el mismo formato que el campo del sobre (`tema-01-users`). Los tópicos que **publicamos y nos pertenecen**:
>
> | Tópico | Contenido |
> |---|---|
> | `tema-01-users.auditoria.v1` | eventos de cuenta: alta, baja, cambio de rol, `RECUPERACION_ADMIN` (`DEC-32`) |
> | `tema-01-users.alumno-registrado.v1` | `ALUMNO_REGISTRADO` hacia Cursos (§13.3) |
>
> Los que **no son nuestros** (el de mails de `notifications-service`, el de resolución de Cursos) llevan **el nombre que defina su dueño** y viven en una property (`DEC-34`). No los renombramos por consistencia: el dueño del tópico es quien lo nombra.
>
> **Por qué el sufijo `.v1`:** es la única forma barata de cambiar un schema sin romper consumidores. El día que el payload de auditoría cambie de forma incompatible, se publica en `.v2` y los dos conviven mientras los consumidores migran. Sin el sufijo, la única salida es coordinar un corte simultáneo entre equipos — que en un sistema de doce equipos no pasa.
>
> **Recordatorio de `DEC-10`:** el payload de auditoría **no** incluye `on_behalf_of` cuando el actor es un servicio (§7.5).

---

## 14. Catálogo de endpoints

Los paths salen de **properties**, nunca hardcodeados:

```properties
app.api.public-path=/api/users/public
app.api.private-path=/api/users
```

```java
@RestController
@RequestMapping("${app.api.public-path}/auth")   // nunca "/api/users/public/auth" literal
public class AuthController { … }
```

### 14.1 Rutas públicas (sin token, sin headers de identidad)

| Método + ruta | Módulo | Qué hace |
|---|---|---|
| `POST /api/users/public/auth/login` | `auth/` | Fase 1 del login → dispara 2FA |
| `POST /api/users/public/auth/2fa/verify` | `auth/` | Fase 2 → emite tokens |
| `POST /api/users/public/auth/refresh` | `auth/` | Rotación de refresh (§10.6) |
| `POST /api/users/public/auth/token` | `auth/` | `client_credentials` → token de servicio |
| `POST /api/users/public/auth/password/reset` | `auth/` | **pedir** reset — manda el mail; respuesta siempre idéntica (anti-enumeración) |
| `POST /api/users/public/auth/password/reset/confirm` | `auth/` | **confirmar** — consume el token de 1 uso y cambia la password · **DEC-16** |
| `POST /api/users/public/registration/student` | `users/` | Alta de STUDENT (dominio institucional + código de invitación) |
| `POST /api/users/public/registration/professor` | `users/` | Alta de PROFESSOR (contra whitelist) |
| `POST /api/users/public/registration/validar-email` | `users/` | **DEC-33** · activación por **código** de 6 dígitos. Reemplaza el link |
| `POST /api/users/public/registration/reenviar-code` | `users/` | **DEC-33** · reenvía el código de activación. Cierra INC-20 |
| `POST /api/users/public/auth/2fa/reenviar` | `auth/` | **DEC-33** · reenvía el código de 2FA. Mismo patrón. Cierra INC-20 |
| `GET /api/users/public/legal/terms` | `users/` | **DEC-31** · texto vigente de T&C, legible sin cuenta |
| `GET /.well-known/jwks.json` | `auth/` | Claves públicas · **única excepción real a la convención** |

### 14.2 Rutas privadas (requieren headers de identidad)

| Método + ruta | Módulo | Capa 1 | Gates (§8) |
|---|---|---|---|
| `POST /api/users/auth/logout` | `auth/` | autenticado | exento de los 3 |
| `POST /api/users/auth/password/change` | `auth/` | autenticado | exento de PASSWORD |
| `GET /api/users/me` | `users/` | autenticado | **exento de los 3** |
| `PATCH /api/users/me/onboarding` | `users/` | autenticado | exento de ONBOARDING |
| `POST /api/users` | `users/` | `hasRole('ADMIN')` | los 3 |
| `DELETE /api/users/{id}` | `users/` | `hasRole('ADMIN')` | los 3 |
| `PATCH /api/users/{id}/role` | `users/` | `hasRole('ADMIN')` | los 3 |
| `GET /api/users/profile/{id}` | `users/` | `hasRole('MS')` + scope | **N/A** (token de servicio) |
| `POST /api/users/whitelist` | `users/` | `hasRole('ADMIN')` | los 3 · **DEC-29** |
| `GET /api/users/whitelist` | `users/` | `hasRole('ADMIN')` | los 3 · **DEC-29** |
| `DELETE /api/users/whitelist/{id}` | `users/` | `hasRole('ADMIN')` | los 3 · **DEC-29** |
| `POST /api/users/whitelist/requests` | `users/` | `hasRole('PROFESSOR')` | los 3 · **DEC-29** · crea la solicitud |
| `GET /api/users/whitelist/requests` | `users/` | `hasAnyRole('ADMIN','PROFESSOR')` | los 3 · **DEC-29** · el PROFESSOR ve **solo las suyas** (capa 2) |
| `PATCH /api/users/whitelist/requests/{id}` | `users/` | `hasRole('ADMIN')` | los 3 · **DEC-29** · aprobar / rechazar |

### 14.3 Lo que desapareció

- ❌ `POST /internal/credentials/verify` — era la llamada de Auth a Usuarios. Ahora es la interfaz Java `CredentialService` (§5.1).
- ❌ `/api/users/internal/profile/{id}` — pasó a ser `GET /api/users/profile/{id}`, una ruta privada normal que exige rol `MS` vía `@PreAuthorize`. **La diferencia ya no la da la URL.**

---

## 15. Autorización · capa 1 y capa 2

**Las dos capas viven acá.** El Gateway autentica, rutea y propaga; **no filtra por rol**.

### 15.1 Capa 1 · rol ↔ endpoint

*¿Este rol puede llamar esta ruta?* — `@PreAuthorize` en el controller, leyendo el `Authentication` que armó `GatewayIdentityFilter` (§7.3).

```java
@PreAuthorize("hasRole('ADMIN')")
@DeleteMapping("/{id}")
public void deactivate(@PathVariable UUID id, @Valid @RequestBody BajaReforzadaRequest req) { … }

@PreAuthorize("hasRole('MS') and hasAuthority('users.profile.read')")
@GetMapping("/profile/{id}")
public ProfileResponse perfil(@PathVariable UUID id) { … }
```

El scope se valida como **authority plana** (sin prefijo `ROLE_`), que es exactamente cómo lo mapea el filtro. *"Sos un servicio"* **no alcanza**: además hay que traer el recurso puntual que corresponde.

### 15.2 Capa 2 · regla de negocio

*¿Puede hacer **esto** con **estos datos**?* — en el caso de uso, no en la anotación.

| Ejemplo | Dónde |
|---|---|
| Un PROFESSOR pasó la capa 1 en `PATCH /api/users/{id}/role`, pero ¿no está dejando la plataforma sin ningún ADMIN? | `UserService.validarNoUltimoAdmin()` |
| Un token con `scope: users.profile.read` pasó la capa 1, pero intenta escribir | rechazo en el caso de uso |
| Un ADMIN intenta darse de baja a sí mismo | `UserService.deactivate()` compara solicitante vs objetivo |

### 15.3 Por qué `@PreAuthorize` y no `@RolesAllowed`

`@RolesAllowed` (JSR-250) es más portable entre frameworks, pero **solo sabe responder "tiene este rol sí/no"**. `@PreAuthorize` soporta **SpEL**, lo que permite combinar rol + condición en la misma anotación sin escribir una clase aparte:

```java
@PreAuthorize("hasRole('MS') and hasAuthority('users.profile.read')")
@PreAuthorize("hasRole('ADMIN') and #id != authentication.principal.id")  // no auto-baja
```

`users-service` necesita exactamente eso. Con `@RolesAllowed` habría que sacar cada condición a un `PermissionEvaluator` o a código del servicio, y la capa 1 dejaría de leerse en el controller.

### 15.4 `RF-USR-07` / `RF-USR-08` — se cumplen por construcción

El PRD exige que un STUDENT nunca vea datos de otro alumno, y que un PROFESSOR nunca vea datos de otro profesor ni de alumnos fuera de sus cursos.

En `users-service` **no hay una regla que codear**: se cumple por diseño, porque **no existe ninguna ruta persona→persona que devuelva el perfil de otro usuario**.

- `GET /api/users/me` es siempre sobre uno mismo.
- `GET /api/users/profile/{id}` **exige rol `MS`**, nunca un rol de persona.

La única forma de que el dato de un usuario llegue a otro contexto es vía un microservicio (Cursos, Desafíos) que lo pide con token de servicio para resolver su propia lógica — no es la persona consultando directamente. **Queda anotado como derivado del diseño, no como capa 2 nueva.** Ver §18 / INC-10: un documento describe un flujo que lo contradice.

---

## 16. Reglas de negocio propias

### 16.1 Whitelist de emails de PROFESSOR

Módulo `users/`. Es una regla de **quién puede llegar a existir como profesor**: administración de identidad pura. La administra ADMIN (`RF-USR-02`); un PROFESSOR puede *solicitar* que se agregue un email — **`TODO-05`**, ese flujo de solicitud no está modelado en ningún documento.

Alta de STUDENT: valida **dominio institucional** del email, no whitelist. El dominio es **parámetro de lanzamiento configurable**, no hardcodeado.

### 16.2 Reglas duras de ADMIN

| Regla | Implementación |
|---|---|
| ADMIN inicial cambia password en el primer login (`RF-USR-01`) | `users/`: `debe_cambiar_password = true` en la instalación; el gate 2 (§8) lo fuerza |
| ADMIN no se auto-elimina (`RF-ROL-02`) | `users/`: `userId` a bajar ≠ el del solicitante |
| Solo ADMIN crea/elimina ADMIN (`RF-ROL-03`) | capa 1 por rol del solicitante |
| **Nunca cero ADMIN activos** (`RF-ROL-05`) | `users/`: `validarNoUltimoAdmin()`, **bloqueo incondicional** — no se puede saltear ni con confirmación explícita. **`DEC-20` regla 5: el conteo va con `SELECT … FOR UPDATE`** sobre las filas de ADMIN activos, dentro de la misma transacción que la baja. Sin el lock, bajo `REPEATABLE READ` dos bajas concurrentes cuentan dos ADMIN cada una y la plataforma queda sin ninguno |
| Baja de ADMIN reforzada (`RF-ROL-06`) | §16.3 |
| Recuperación de ADMIN server-only (`RF-ROL-04`) | §16.4 |

### 16.3 Baja reforzada de ADMIN · `RF-ROL-06`

Cruza los dos módulos **en el mismo proceso, sin salto de red**:

1. **`auth/` — identidad reforzada:** re-verifica password (`CredentialService.verifyPasswordOf`) **y** exige un 2FA nuevo. Falla → `401`, no procede.
2. **`users/` — integridad del sistema:** ¿solicitante ≠ objetivo? ¿queda ≥ 1 ADMIN activo? Si dejaría cero ADMIN → **`409`**, bloqueo incondicional.
3. Baja lógica (`deleted_at`), el registro permanece.
4. Evento de auditoría.

**DEC-11 · agregado del PRD:** `RF-ROL-06` exige además **confirmación explícita escrita** — el request debe traer el nombre de usuario a eliminar, tipeado, y coincidir exactamente. Ningún manifiesto lo captura.

```java
public record BajaReforzadaRequest(
        @NotBlank String passwordActual,
        @NotBlank String twoFactorCode,
        @NotBlank String usernameConfirmation) {}   // DEC-11 · RF-ROL-06
```

Las dos capas son **responsabilidades distintas en módulos distintos**, y ambas son bloqueantes: `auth/` valida que quien pide la baja es realmente ese ADMIN (no alguien con su sesión abierta); `users/` valida que la baja no rompa la plataforma.

### 16.4 Recuperación de ADMIN · `RF-ROL-04` · **DEC-11**

**Comando CLI, sin endpoint HTTP.** No hay UI. `AdminRecoveryCommand` en `auth/cli/`.

El PRD pide cuatro cosas que el manifiesto no menciona:

1. Protegido por un **secreto de instalación** custodiado fuera del equipo de desarrollo → variable de entorno, comparada contra un hash; **nunca** en el repo ni en un `application.yml` de ejemplo.
2. **Cambio de contraseña forzado** tras usarlo → setea `debe_cambiar_password = true` (gate 2).
3. **Auditoría completa** → evento `RECUPERACION_ADMIN` vía `AccountEventPublisher`.
4. **Alerta automática al usarse** → **`DEC-32`**, abajo.

> **`DEC-32` · Canal de la alerta: los tres que ya tenemos, ninguno nuevo.** Cierra `TODO-13`. Un uso legítimo del break-glass es rarísimo; uno ilegítimo es el compromiso total de la plataforma. La alerta va por tres vías en paralelo, porque cada una falla distinto:
>
> | Vía | Qué es | Por qué |
> |---|---|---|
> | **Log a nivel `ERROR`** | una línea con `traceId`, timestamp UTC y el `id` del ADMIN creado | queda en el agregador de logs sin depender de que Kafka o el mail estén vivos |
> | **Evento `RECUPERACION_ADMIN`** al tópico de auditoría | el sobre estándar (`DEC-12`) | es el registro durable y auditable; ya es el punto 3 de esta lista |
> | **Mail `EMAIL_ALERTA_BREAKGLASS`** a **todos los ADMIN activos** | vía `notifications-service`, como cualquier otro mail (§13.2) | es la única vía que le llega a una **persona**. Si quedaban ADMIN, se enteran; si no quedaba ninguno, la lista es vacía y las otras dos vías siguen valiendo |
>
> **Ninguna de las tres es infraestructura nueva.** La alternativa era un canal propio (webhook a Slack, PagerDuty) y no se justifica: agrega una dependencia para un evento que puede no ocurrir nunca.
>
> 🔴 **El comando no debe fallar si la alerta falla.** Si Kafka está caído o el mail no sale, el ADMIN se crea igual y el log queda: el break-glass existe justamente para escenarios donde las cosas están rotas. Las tres vías van fuera de la transacción de creación.

Es criterio de release #12 del PRD, junto con el bloqueo de último ADMIN.

### 16.5 Ciclo de alta · los gates en orden

1. **Dominio institucional / whitelist** — se valida **en el momento del alta**. **No es un estado persistido**: si no matchea, el alta se rechaza directamente (`403`).
2. **`PENDING_EMAIL`** — cuenta creada, `RegistrationService` renderiza el mail de activación y publica el evento. Mientras esté acá, **no hay ningún acceso**, ni siquiera para cargar el código de invitación.
3. **`PENDING_COURSE`** (solo STUDENT) — email confirmado, Cursos todavía no resolvió.
4. **`ACTIVE`** — ambos gates cerrados. Si `primer_login`, antes de soltarla a la plataforma pasa por vincular GitHub + elegir avatar + Guided Tour (gate 3).

### 16.6 T&C · `RF-NFR-09` · **DEC-11**

La aceptación de Términos y Condiciones es **obligatoria en el alta** y es **criterio de release #14** del PRD.

- Campos `tyc_version_aceptada` + `tyc_aceptado_en` en `User` (§6.1).

> **`DEC-31` · Texto mock versionado.** Cierra `TODO-14`. El texto legal real no existe todavía y no lo escribe este equipo, pero **el mecanismo sí es nuestro y no puede quedar sin probar**.
>
> - Archivo `src/main/resources/legal/terms-v1.md`, con un texto **mock explícitamente marcado como tal** en su primera línea.
> - Se sirve en `GET /api/users/public/legal/terms` (público, sin token: hay que poder leerlo **antes** de tener cuenta).
> - La versión vigente es una property: `users.legal.terms-version: "v1"`. Es lo que se guarda en `tyc_version_aceptada`.
> - El alta **rechaza con `400`** si `tycAceptado` no viene, o si la versión aceptada no es la vigente.
>
> **Reemplazar el texto es cambiar un archivo y bumpear la property.** Como la versión aceptada queda guardada por usuario, el día que haya texto real se sabe exactamente quién aceptó qué — que es el punto de `RF-NFR-09` y del criterio de release #14. Un mock versionado cumple el requisito; un `TODO` no.
- El DTO de alta (alumno y profesor) exige `termsVersion` + `tycAceptado = true`; sin eso, `400`.
- Se guarda **la versión**, no un booleano suelto: si el texto cambia, hay que saber qué aceptó cada persona.

El **texto** de los T&C (que debe cubrir los cuatro puntos de `RF-NFR-09`) es contenido, no código — **`TODO-14`**: quién lo redacta y dónde se sirve no está definido en ningún documento.

---

## 17. Convenciones de código y errores

### 17.1 Patrón Repository explícito

Interfaces (contratos) separadas de `impl/`. No mezclar contrato e implementación en la misma clase. Aplica a `CredentialService`, `EphemeralTokenService`, `TokenStore`, `SigningKeyProvider`, `SecondFactorProvider` y los repositorios JPA.

### 17.2 Manejo de errores centralizado

**Un `@RestControllerAdvice`**, no `try/catch` disperso. Siempre el mismo formato **`ProblemDetail`** (RFC 9457) — el mismo que ya usa el Gateway.

```jsonc
{
  "type":     "https://tpi.utn.frc/errors/pending-account",
  "title":    "Cuenta pending de validación",
  "status":   403,
  "detail":   "La cuenta no está activa.",
  "instance": "/api/users/me",
  "accountStatus": "PENDING_COURSE",
  "requestId": "…",
  "traceId":   "…"
}
```

Catálogo mínimo:

| Código | `type` | Cuándo |
|---|---|---|
| `400` | `validation` | DTO inválido, scope fuera de `scopes_permitidos`, T&C sin aceptar |
| `401` | `invalid-credentials` | login fallido, 2FA incorrecto/vencido, refresh revocado o reusado |
| `403` | `pending-account` / `password-change-required` / `onboarding-pending` | los tres gates (§8) |
| `403` | `email-not-whitelisted` | alta de PROFESSOR fuera de whitelist, o dominio no institucional |
| `403` | `access-denied` | capa 1 (`@PreAuthorize`) |
| `409` | `last-admin` | `RF-ROL-05`, bloqueo incondicional |
| `409` | `invalid-transition` | transición de estado no permitida (§9.2) |
| `409` | `duplicate-email` | alta con un email **activo** ya registrado (`DEC-21`) |
| `429` | `too-many-attempts` | rate limit de login por email (`DEC-24`). **El mismo `type` que usa el Gateway** para su límite por IP, con `Retry-After`: el frontend tiene una sola rama de manejo y no necesita saber cuál de los dos respondió |

### 17.3 Anti-enumeración

- La respuesta al **pedido de reset de password es siempre idéntica**, exista o no el email. Evita que un atacante descubra qué emails están registrados.
- `verifyCredentials` devuelve `null`, no distingue "no existe" de "password incorrecta".
- El `ProblemDetail` de `401` en login **nunca** dice cuál de las dos falló.

### 17.4 Logs limpios y trazables

Mensajes estructurados, consistentes, siempre con el `traceId` visible. El pattern de logging incluye `traceId`/`spanId`/`requestId` en cada línea, tomados de los headers `traceparent`/`X-Request-Id` que inyecta el Gateway.

**Nunca se loguea:** password en claro, `clientSecret`, hash BCrypt, código 2FA, código de activación, token de reset, ni ningún JWT completo.

### 17.5 Seguridad al cambiar la password

Los dos flujos (cambio voluntario y recuperación por olvido) **terminan revocando todos los refresh tokens del usuario**. Una contraseña cambiada debe cerrar las sesiones viejas. El token de reset es de **un solo uso** y vida corta (15 min).

---

## 18. ⚠️ Inconsistencias detectadas

> Son contradicciones **reales entre los documentos fuente**. Este documento no las arregla en los manifiestos: los manifiestos siguen contradiciéndose y hay que corregirlos.
> Prioridad: 🔴 bloquea código correcto · 🟡 afecta el contrato con otro equipo · 🔵 documental.
> Estado: ✅ cerrada por `DEC-xx` · ⏳ abierta.
> **Numeración compartida con `SPEC-api-gateway.md`.** Un mismo `INC-xx` es la misma inconsistencia en las dos specs: `INC-01`, `INC-09`, `INC-10`, `INC-12`, `INC-13`, `INC-15` e `INC-17` ya estaban reportadas ahí y se repiten acá con el ángulo de `users-service`. `INC-18` en adelante son nuevas de este documento. Los huecos (`INC-02`…`INC-08`, `INC-11`, `INC-14`, `INC-16`) son inconsistencias que solo afectan al Gateway — están en su spec, no acá.

### 18.0 Decisiones tomadas por el equipo

`DEC-01`…`DEC-07` están en `SPEC-api-gateway.md` §14.0. Las que obligan a `users-service` se repiten acá; `DEC-08`…`DEC-18` son nuevas.

| ID | Decisión | Impacto en `users-service` |
|---|---|---|
| **DEC-02** | El logout borra `session:{userId}` | 🔧 agregar el borrado al flujo de logout (§10.5) |
| **DEC-03** | El Gateway **reenvía** el `Authorization` original al destino | ninguno: con `DEC-08` no lo leemos. Queda disponible por si alguna vez hace falta defensa en profundidad |
| **DEC-04** | El Gateway valida `aud` contra el destino resuelto | 🔧 **emitir siempre `aud` + `scope`** en tokens de servicio, o el Gateway responde 403 a todos |
| **DEC-05** | Headers: coma sin espacio, `MS` dentro de `X-Service-Scopes` | 🔧 parsear con `split(",")`, mapear `MS` → `ROLE_MS` (§7.3) |
| **DEC-07** | `iss = "users-service"` | **emitir el claim `iss`** en ambos tipos de token, desde el primer commit. Es aditivo: nadie lo valida hasta que se active `DEC-19` |
| **DEC-08** | `users-service` **no valida el JWT**; su `Authentication` sale de los headers `X-*` | define todo el §7. Cierra la pregunta de dónde nace el `Authentication` |
| **DEC-09** | **No** se persiste `VALIDADO_PADRON`/`VALIDADO_EXCEPCION` | el dueño es Cursos; `RF-USR-05h` lo sirve Cursos (§13.4) |
| **DEC-10** | `on_behalf_of` se queda en los logs del Gateway | el evento de auditoría no lleva `onBehalfOf` (§7.5) |
| **DEC-11** | Entran al alcance: T&C (`RF-NFR-09`), emails de estado/habilitación (`RF-USR-05i`), confirmación escrita en baja de ADMIN (`RF-ROL-06`), break-glass completo (`RF-ROL-04`). **Queda fuera** `RF-NFR-10` (retención 5 años) | §6.1, §13.2, §16.3, §16.4 |
| **DEC-12** | Sobre estándar de eventos + `producer: "tema-01-users"` | §13.1 |
| **DEC-13** | Idempotencia de consumers vía tabla `processed_events` con el `eventId` del sobre | §6.4, §13.4 |
| **DEC-14** | Los tres gates de cuenta se implementan como `HandlerInterceptor` con exención por anotación, no como `@PreAuthorize` | §8 |
| **DEC-15** | Scaffolding en paridad total con `api-gateway`: mismo group, mismas versiones, `spring-cloud` solo por el BOM | §2 |
| **DEC-16** | El reset de password son **dos endpoints**: `…/reset` (pedir) y `…/reset/confirm` (confirmar) | §14.1. Cierra INC-22 |
| **DEC-17** | El request de `client_credentials` suma un campo **`audience`** obligatorio, validado contra el prefijo del `scope` | §10.2. Cierra INC-24 / TODO-08. 📢 **cambio de contrato**: avisarle a los equipos consumidores |
| **DEC-18** | Las claves RS256 son **PEM montados como secret**, con `kid` activo por variable de entorno. Nunca generadas al arrancar, nunca en el repo, nunca en la base | §10.7. Cierra TODO-04 |
| **DEC-19** | El Gateway **activa la validación de `iss` por configuración**, y arranca con ella apagada | quita el riesgo de secuencia de `DEC-07`: podemos emitir el claim cuando queramos. Ver `SPEC-api-gateway.md` §9.1.0 |
| **DEC-20** | La base relacional es **MySQL 8.4 LTS**, no PostgreSQL. Seis convenciones obligatorias en §6.0 | §6 completo. 🔴 **cambia el modelo**, no solo el driver: `CHAR(36)`, `DATETIME(6)`, tabla hija de scopes, `FOR UPDATE` en las reglas de conteo. Forzó además cerrar `TODO-07` como `DEC-21` |
| **DEC-21** | Un email dado de baja **sí** se puede volver a registrar, vía columna generada `email_activo` | §6.1. Cierra TODO-07 |
| **DEC-22** | El refresh **no** genera `sid` nuevo ni escribe `session:{userId}`; `auth/` valida la sesión **antes** de rotar y revoca la familia si no coincide | §10.5, §10.6, §11. Cierra INC-01 |
| **DEC-23** | El token de persona lleva `est`/`pwd`/`onb`; el **Gateway** aplica el gate grueso sobre rutas ajenas; `deactivate()` borra `session:{userId}` | §10.1, §9.2, §8. Cierra INC-18 |
| **DEC-24** | Rate limit repartido: Gateway **por IP**, `auth/` **por email**. Mismo `429`, claves distintas | §11. Cierra INC-15 |
| **DEC-25** | El Gateway cachea `session:{userId}` localmente 3 s | ninguno para `users-service`. Ver `SPEC-api-gateway.md` §9.1.2 |
| **DEC-26** | Un scope solo es emitible por `client_credentials` si su prefijo resuelve a un servicio en la allowlist. Hoy: solo `users.profile.read` | §10.2. Cierra TODO-16 |
| **DEC-27** | El Gateway declara una **ruta estática** para `GET /.well-known/jwks.json` | ninguno acá; el endpoint no cambia. Ver `SPEC-api-gateway.md` §6.4 |
| **DEC-28** | Puertos: `api-gateway` **8080**/8081, `users-service` **8082**/8083 | §2. Cierra TODO-02 |
| **DEC-29** | Whitelist: 3 endpoints de ADMIN + entidad `whitelist_requests` con estado y notificación en ambos extremos | §6.2b, §14.2. Cierra TODO-05 / INC-21 |
| **DEC-30** | MinIO **fuera del sprint**; `avatarRef` pasa a **opcional** en el onboarding | §6.1, §8. Sin esto el gate 3 encerraba a todo usuario nuevo en `403` |
| **DEC-31** | T&C: texto **mock versionado** en el repo + `GET /api/users/public/legal/terms` | §16.6. Cierra TODO-14 |
| **DEC-32** | Alerta de break-glass por **tres vías ya existentes**: log `ERROR`, evento de auditoría, mail a los ADMIN activos | §16.4. Cierra TODO-13 |
| **DEC-33** | La validación de email pasa de **link** a **código de 6 dígitos**, mismo motor OTP que el 2FA, con TTL 30 min, 5 intentos y endpoint de reenvío | §12.1. Cierra INC-20. ⚠️ **desviación de los manifiestos** |
| **DEC-34** | Regla de costuras externas: definimos e implementamos nuestra mitad; el nombre del tópico es una property | §13.2b, §13.4b. Reduce TODO-10 y TODO-11 a configuración |
| **DEC-35** | Spring Cloud **2025.1.3 "Oakwood"** con Boot 4.1.1 — **verificado** contra la matriz oficial | §2. Cierra TODO-01 |
| **DEC-36** | `GET /api/users/profile/{id}` acepta **token de persona además de `MS`** | §18/INC-10, §15. Cierra INC-10 |
| **DEC-37** | Convención `<producer>.<asunto>.<version>`; auditoría en `tema-01-users.auditoria.v1` | §13. Cierra TODO-12 |
| **DEC-38** | Password ≥12 y **≤72 bytes**, sin reglas de composición, BCrypt 12; sin timeout de inactividad; PII sin cifrado por columna | §18/INC-26. Cierra TODO-15 |
| **DEC-39** | **Ante discrepancia, prevalece la spec sobre el anexo.** Sin análisis caso por caso | Cierra INC-13, INC-14, INC-16, INC-25 |
| **DEC-40** | `Dockerfile` + `docker-compose.yml` definidos | `SPEC-api-gateway.md` §16. Cierra TODO-03 (acá) y TODO-02 (allá) |
| **DEC-41** | `notifications-service` es **solo consumidor de Kafka**; se elimina el scope `mailing.debug.read` | §10.2, §18/INC-12. Cierra INC-12 |
| **DEC-42** | Rate limit de login: **5 fallos / 15 min por email**, valor estándar a recalibrar | §11. Cierra TODO-09 |
| ~~**DEC-43**~~ | ~~Switch de tres estados en el Gateway~~ | 🔴 **RETIRADA por `DEC-44`** — era sobreingeniería |
| **DEC-44** | Los claims nuevos **se validan siempre, sin flag**; la red de seguridad es un test | 🔴 **emitir `iss`/`est`/`pwd`/`onb` desde el primer commit** + `TokenContractTest` (DoD #31) |
| **DEC-45** | Cuatro patrones que se suman (**a** builder de claims, **b** outbox, **c** enum de mails, **d** tabla de transiciones) y seis que se descartan con motivo | §5b. **b** agrega tabla + migración `V6` |
| **DEC-46** | **El contrato HTTP y el código están en inglés; lo que es de otro equipo, no** | §18.1. Rutas, campos JSON, `type` de error, valores de enum, columnas y migraciones en inglés. Quedan en castellano `legajo`, los nombres de tópico y payload de Kafka, y el texto que lee la persona usuaria — que vive en el front, no en la API |

> ✅ **El riesgo de secuencia de `DEC-07` está neutralizado por `DEC-19`.** El claim `iss` no aparece en el contrato de token de ningún manifiesto, así que había que emitirlo **antes** de que el Gateway lo validara, o el sistema entero devolvía 401. Con `DEC-19` el Gateway arranca con esa validación **apagada** y se enciende con una variable de entorno. Para `users-service` eso significa: **emitir `iss` desde el primer commit, sin coordinar con nadie** — es un claim aditivo que nadie valida todavía. El paso de encendido es del Gateway (`SPEC-api-gateway.md` §9.1.0).

---

### INC-01 🔴 ✅ · ¿El refresh genera un `sid` nuevo? · **cerrada por `DEC-22`**

| Documento | Dice |
|---|---|
| `manifiesto-users-service` §04.1 | *"Al emitir tokens (login exitoso tras 2FA, **o refresh**), `TokenService` genera un `sid` nuevo"* |
| `jwt-jwks-redis` §06.5, tabla paso 1 | *"Login exitoso (post-2FA) **o refresh** → `auth/` genera un `sid` nuevo"* |
| `jwt-jwks-redis` §08, runbook "Renovar la sesión" | *"(v5) **El `sid` NO cambia en un refresh** — es la misma sesión continuando, no un login nuevo. Solo un login post-2FA genera `sid` nuevo."* |

El anexo se contradice **consigo mismo**, y su §08 contradice al manifiesto.

En la spec del Gateway esto no tenía impacto en código (el Gateway solo compara). **Acá sí**: es una línea de `TokenService.rotar()`.

**`DEC-22` · Resuelto: el refresh no genera `sid` nuevo ni escribe Redis.** La intuición de que "si no rota, un dispositivo viejo revive la sesión" está **invertida** — rotar es lo que permite revivirla. El análisis de las cuatro combinaciones, el invariante resultante y el chequeo de sesión dentro del refresh están en §10.5 y §10.6.

Nota de fuentes: es el único caso donde **el anexo le gana al manifiesto**. `jwt-jwks-redis` §08 tiene razón y `manifiesto-users-service` §04.1 está mal, al revés de lo que dicta la jerarquía. 🔧 **Reflejar en el manifiesto §04.1.**

---

### INC-09 🔵 ✅ · El campo `scope` del request de client credentials · **cerrada por `DEC-17`**

`manifiesto-api-gateway` §05.3 muestra el body **con** `"scope": "users.profile.read"`; `jwt-jwks-redis` §7.3c muestra el mismo request **sin** ese campo. Y `manifiesto-users-service` §04.0b dice que cada `service_client` tiene scopes asociados **en la base**, lo que sugiere que el scope podría no venir del request.

Implementé "viene en el request y se valida contra `scopes_permitidos`", que es la lectura del manifiesto del Gateway.

---

### INC-10 🟡 ✅ · `GET /api/users/profile/{id}` con token de persona · **cerrada por `DEC-36`**

- `manifiesto-users-service` §12.1: la ruta es *"privado · **rol MS + scope**"*, y §07 refuerza: *"exige rol MS, **nunca un rol de persona**"*.
- `jwt-jwks-redis` §7.4, "Flujo real 3": *"Cursos […] **reenvía el mismo token del profesor** […] `users-service` devuelve los datos de perfil"* — token de persona contra esa misma ruta.

El caso "a" del Gateway §02 (reenviar el JWT de persona) es válido en general, pero **este ejemplo concreto choca de frente** con la regla. Con la spec implementada, ese flujo devuelve **403**. Alguien lo va a implementar como está escrito en el anexo y va a perder una tarde.

*(Misma `INC-10` de `SPEC-api-gateway.md`. Allá no tenía impacto — el Gateway no autoriza. Acá sí: es la ruta que devuelve el 403.)*

**`DEC-36` · Resuelto: la ruta acepta los dos tipos de token.**

```java
@PreAuthorize("hasRole('MS') and hasAuthority('users.profile.read') or isAuthenticated()")
@GetMapping("/profile/{id}")
```

| Tipo de token | Capa 1 | Capa 2 |
|---|---|---|
| `type: service` | rol `MS` **y** scope `users.profile.read` | — |
| `type: user` | autenticado, cualquier rol | los tres gates de §8 **sí aplican**: una cuenta pendiente no lee perfiles ajenos |

**Por qué es la decisión correcta y no una concesión al anexo:**

- **Es un perfil, no un dato sensible.** Devuelve `firstNames`, `lastNames`, `avatarRef`, `githubUsername` — lo que cualquier compañero de curso ve en una pantalla. No devuelve `email`, ni `legajo`, ni `accountStatus`, ni nada de `auth/`. Si devolviera eso, la respuesta sería no.
- **Obligar a token de servicio empeora la trazabilidad.** El flujo del anexo (Cursos reenvía el token del profesor) deja registrado **qué persona** miró ese perfil. Forzando token de servicio, el log dice "cursos-service" y se pierde quién fue — que es justo lo que `on_behalf_of` intentaba recuperar, y que con `DEC-10` ya solo vive en los logs del Gateway.
- **Menos secretos en circulación.** Si cada lectura de perfil necesitara token de servicio, cada micro que muestre un nombre necesita `clientId`+`clientSecret`. Reenviar el token de la persona que ya está en la request no agrega ninguna credencial nueva.

⚠️ **Precisión sobre el `aud`:** cuando Cursos **reenvía el token del profesor**, ese token es de persona y **no lleva `aud`** — así que el `ServiceAudienceFilter` (`DEC-04`) no lo mira: solo aplica a `type: service`. El flujo del anexo funciona sin tocar nada del Gateway.

🔧 **Reflejar en `manifiesto-users-service` §07 y §12.1**, que dicen *"nunca un rol de persona"*.

---

### INC-12 🟡 ✅ · ¿Existe `mailing-service` como servicio HTTP? · **cerrada por `DEC-41`**

`manifiesto-users-service` §01.1 dice que **Mailing salió del alcance** y lo reemplazó `notifications-service`, externo, que *"no nos llama por HTTP, solo consume eventos"*.

Pero §04.0b conserva el scope **`mailing.debug.read`**, descrito como *"el único endpoint HTTP restante de mailing: lectura de logs/estado de envíos"*.

**`DEC-41` · Resuelto: `notifications-service` es un consumidor de Kafka, no un servicio HTTP nuestro.**

Confirmado por el equipo: va a existir, lo hace otro equipo, y **no sabemos ni necesitamos saber cómo está construido**. Lo único compartido es el **contrato del sobre de eventos** (`DEC-12`), por donde le avisamos que tiene que salir un mail. No le pegamos por HTTP, nunca.

**Consecuencia para `users-service`:** 🔧 **`mailing.debug.read` se elimina** del catálogo de scopes emitibles (§04.0b del manifiesto) y de cualquier fila de `service_clients`. Apuntaba a "el único endpoint HTTP restante de mailing" — un endpoint que no es nuestro, que no consumimos, y para el que no vamos a emitir tokens.

**Efecto neto, sumado a `DEC-26`:** el catálogo emitible queda en **un solo scope, `users.profile.read`**. Y la validación de arranque de `DEC-26` (fallar si una fila tiene un scope no emitible) atrapa el caso de que alguien lo deje cargado en la base.

---

### INC-13 🔵 ✅ · Terminología de "rol MS" en el anexo · **cerrada por `DEC-39`**

`jwt-jwks-redis` usa el rol `MS` de forma inconsistente:

- §7.3 (caso B) y §08 (runbook *"Un micro llama a otro"*) describen el token de servicio solo como `type: service`, **sin nombrar el rol `MS`** ni los claims `aud`/`scope`/`on_behalf_of` que la revisión v4 hizo obligatorios.
- §08, paso 6: *"B ve un token válido que identifica al servicio A, y **decide qué puede hacer (capa 2)**"* — **saltea la capa 1** (`hasRole('MS')`), que es parte explícita del contrato.
- §7.3c (comentarios del request) sí lo nombra bien.

**Prioricé `manifiesto-users-service` §04 y §07.**

---

### INC-15 🟡 ✅ · Rate limit de login: dos dueños · **cerrada por `DEC-24`**

- `manifiesto-api-gateway` §03 p.7: `RateLimitFilter`, *"solo en rutas marcadas como caras"*.
- `manifiesto-users-service` §05 y §10: `auth/` guarda *"rate limit de login"* en Redis, y `TokenStore` expone `rateLimit(key)`.
- `manifiesto-flujos` §02: el paso *"rate-limit login"* está **en el carril de `auth/`**, no en el del Gateway.

Sin definir: si `/api/users/public/auth/login` es una "ruta cara" del Gateway, y si aplican los dos, cuál responde el `429`. Combinado con `TODO-09` (ni el Gateway ni nosotros tenemos umbrales), queda abierto en los dos lados.

---

### INC-17 🟡 ✅ · Discrepancias del PDF de arquitectura · fila (a): un cuarto rol · **cerrada por el equipo**

*(`INC-17` en `SPEC-api-gateway.md` agrupa seis discrepancias del PDF, de (a) a (f). Acá solo importa la (a), que es la que toca el modelo de datos; las otras cinco están en esa spec.)*

`TUP_PIV_BE_PROPUESTA_ARQ.pdf`, Tema 01, "Pedido para empezar":

> *"Roles: ADMIN, **responsable**, profesor, alumno"*

**Cuatro roles**, incluido `responsable`. Los manifiestos y el PRD (Tabla 3) declaran **tres**: `ADMIN` / `PROFESSOR` / `STUDENT`, más el rol `MS` exclusivo de servicios.

Si `responsable` es real, el enum `Role` de §6.1 está incompleto y `X-User-Roles` tiene un valor posible más. **Prioricé los manifiestos y el PRD.**

---

### INC-18 🟡 ✅ · El gate de cuenta sobre rutas de *otros* micros · **cerrada por `DEC-23`**

`manifiesto-flujos` §11 dibuja esta secuencia:

```
GET /api/cursos/mis-cursos  →  Gateway (rutea)  →  users-svc·users/  →  403 ONBOARDING_PENDIENTE
```

**El carril está mal.** Esa ruta va a `cursos-service`, no a `users-service`. `users-service` **no ve ese tráfico** y no puede bloquearlo.

Ningún documento define cómo un micro ajeno se entera de que la cuenta está en `PENDING_COURSE` o `primer_login`.

**`DEC-23` · Resuelto: el estado viaja como claim (`est`/`pwd`/`onb`, §10.1) y el gate grueso lo aplica el Gateway** (`SPEC-api-gateway.md` §9.6).

| Approach | Por qué no |
|---|---|
| El Gateway consulta el estado por request | Otro salto en el camino crítico, encima del de Redis (INC-04) |
| Cada micro consulta `users-service` con token de servicio | Un salto HTTP por request **por servicio**, y convierte a `users-service` en dependencia dura de los otros once |
| El estado viaja como claim y **cada micro** lo chequeá | Costo de red cero, pero son **once equipos implementando lo mismo**, inconsistentemente |

**Por qué el Gateway y no cada micro:** `R3` dice que el Gateway no autoriza **por rol**, y el estado de cuenta no es un rol. Y hay un detalle que lo vuelve limpio: **todas las rutas exentas de los tres gates finos son de `users-service`**, así que la regla del Gateway se escribe sin conocer las rutas de nadie. El gate grueso vive en el Gateway, los tres finos siguen en §8 (`DEC-14` intacto), y no depende de que once equipos hagan nada.

🔧 **Reflejar en `manifiesto-flujos` §11**, cuyo diagrama dibuja el `403` saliendo del carril `users-svc·users/` para una ruta `/api/cursos/**` — técnicamente imposible.

---

### INC-19 🟡 ⏳ · `RF-USR-05f` vs. "el login puede completarse"

`RF-USR-05f` (PRD): *"El alta de un STUDENT **no puede completarse** hasta que su Legajo esté validado. […] la cuenta queda en estado pendiente de validación y el usuario **no tiene acceso a ninguna funcionalidad** de la plataforma — ni cursos, ni desafíos, ni chat, ni Guided Tour. **No existe modo demostración ni acceso parcial.**"*

El manifiesto §8.4 interpreta que el login **sí** puede completarse y emitir tokens, y que lo bloqueado es todo lo demás. Está documentado como lectura deliberada, y es defendible — pero es una tensión con la letra del PRD, no una equivalencia. La dejo señalada porque es exactamente el tipo de cosa que se pregunta en la defensa.

Relacionado: `RF-TUR-04` afirma que *"un alumno en estado pendiente de validación no accede a la plataforma en absoluto"* y que *"todo usuario que llega al tour está ya validado"*.

> **Actualización · `DEC-23` fortalece mucho la defensa.** Esta nota decía que la lectura del manifiesto era consistente con `RF-TUR-04` **siempre que el gate se aplicara en todos los micros**, y que eso era justo lo que INC-18 dejaba abierto. **INC-18 ya está cerrada.** Con el `AccountStateGuard` del Gateway, una cuenta en `PENDING_COURSE` que se loguea obtiene tokens y **no puede alcanzar absolutamente nada** salvo `/api/users/**`: ni cursos, ni desafíos, ni chat, ni tour. La condición que faltaba está cumplida.
>
> **Cómo defenderlo:** `RF-USR-05f` prohíbe el **acceso a funcionalidad**, no la emisión de un token. El token es el mecanismo por el que la persona consulta `GET /api/users/me` y se entera de qué le falta — sin él no habría forma de comunicarle su propio estado, y habría que inventar un segundo canal autenticado para eso. "No existe modo demostración ni acceso parcial" se cumple literalmente: el conjunto de funcionalidades alcanzables es **vacío**.
>
> **La tensión que queda es de vocabulario**, no de comportamiento: el PRD dice que el alta "no puede completarse" y acá el alta se completa pero la cuenta queda inhabilitada. Vale señalarlo en la defensa antes de que lo pregunten.

---

### INC-20 🔵 ✅ · ¿Hay endpoint de "reenviar código 2FA"? · **cerrada por `DEC-33`**

`manifiesto-users-service` §06: *"El frontend, ante un 2FA vencido, debe ofrecer 'reenviar código' como acción explícita […] eso dispara los mismos pasos 3-4 de nuevo, **no un endpoint nuevo**"*.

Los "pasos 3-4" son *renderizar el mail* y *publicar el evento* — que están **dentro** de la fase 1 del login. Dos lecturas posibles: (a) el frontend re-ejecuta `POST /auth/login` completo, con credenciales; (b) hay algo que dispara solo los pasos 3-4, lo que **requeriría** un endpoint. La frase niega el endpoint pero describe una operación que no existe sin él.

**Implementé (a)**, que es lo que la frase afirma literalmente. Si el equipo quería (b), hay que agregar `POST /api/users/public/auth/2fa/resend`.

---

### INC-21 🟡 ✅ · Whitelist sin endpoints · **cerrada por `DEC-29`**

`RF-USR-02`: *"el email debe validarse contra una lista blanca administrada por ADMIN. **Un PROFESSOR puede solicitar a un ADMIN que agregue otro email a esa lista.**"*

`WhitelistService` existe en el §11 del manifiesto (`agregarEmail()`, `estaEnLista()`), pero **el §12.1 no expone ningún endpoint** de whitelist. Y el flujo de *solicitud* del PROFESSOR no está modelado en ninguna parte: ni endpoint, ni entidad, ni evento.

**`DEC-29` · Resuelto:** tres endpoints de ADMIN sobre la lista + tres sobre solicitudes, con la entidad `whitelist_requests` (§6.2b).

La forma elegida fue **tabla de solicitudes con estado + notificación**, sobre las otras dos que estaban en juego (evento Kafka sin persistir, o mail suelto al ADMIN). El motivo es que `RF-NFR-01` pide que nada se pierda: con un mail suelto, una solicitud rechazada no deja rastro y nadie puede responder "¿qué pasó con lo que pedí?". Con estado, la respuesta es una consulta.

---

### INC-22 🔴 ✅ · Dos operaciones distintas en el mismo `POST` · **cerrada por `DEC-16`**

`manifiesto-flujos` §06 muestra el flujo de recuperación de password con **el mismo método y el mismo path para las dos mitades**:

```
POST /api/users/public/auth/password/reset   → pedir el reset (manda el mail)
POST /api/users/public/auth/password/reset   → confirmar (consume el token, cambia la password)
```

No es implementable como está: son dos operaciones con cuerpos y efectos distintos.

**Lectura:** no es una decisión de diseño del manifiesto, es un atajo de dibujo — las dos mitades están una debajo de la otra en la misma lane con el mismo label, y el resto del §06 sí distingue todo lo demás con precisión (`/password/change` vs `/password/reset`).

**`DEC-16` · Resuelto:** `POST …/password/reset` (pedir) + `POST …/password/reset/confirm` (confirmar), ambos bajo `/public`.

- **Por qué no discriminar por body** (mismo path, "¿viene el campo `token`?"): un `@Valid` no puede validar dos DTOs en una misma operación, OpenAPI no puede describir dos schemas en un endpoint, y mezcla la respuesta constante anti-enumeración con un error real de token inválido.
- **Por qué no renombrar a `forgot` + `reset`** (naming más convencional): `DEC-16` conserva literal el path que el manifiesto **sí** escribió, así que el diff contra un documento lockeado es *un endpoint agregado*, no dos renombrados.
- **Nota:** el link del mail apunta al **frontend** con el token como query param, no a la API. El path es interno; cambiarlo no rompe mails ya enviados.

🔧 **Reflejar en `manifiesto-flujos` §06.**

---

### INC-23 🟡 ⏳ · `RF-NFR-10` (retención 5 años): el PRD lo exige, la arquitectura lo difiere

| Documento | Dice |
|---|---|
| PRD `RF-NFR-10` | Requisito completo: plazo de 5 años configurable (PAR-16), **purga nunca automática**, estado "pendiente de decisión", notificación al ADMIN con preaviso (PAR-17), decisión caso por caso, anonimización auditada |
| PRD, tabla de definiciones residuales | Lo lista bajo "Privacidad de datos", cerrando RSK-11 |
| `TUP_PIV_BE_PROPUESTA_ARQ.pdf`, Tema 01 | *"Retención: 5 años configurable, sin purga automática, decisión de ADMIN"* está en la columna **"Para más adelante"**; y al pie: *"La purga y anonimización de PII (RSK-11) está **diferida por decisión del product owner: queda declarada como fuera de alcance**"* |
| Los cuatro manifiestos | **No lo mencionan en absoluto** |

**DEC-11 lo deja fuera de alcance**, siguiendo al PDF de arquitectura (que es posterior y explícito). Pero el PRD lo tiene como requisito con detalle, así que la discrepancia queda reportada: **no es un olvido, es una decisión de otro documento que contradice al PRD**.

---

### INC-24 🔴 ✅ · Cómo declara el cliente el `aud` que quiere · **cerrada por `DEC-17`**

`DEC-04` (spec del Gateway) hace que el Gateway rechace con `403` todo token de servicio cuyo `aud` no coincida con el destino resuelto. Para eso, `users-service` tiene que **emitir** un `aud` correcto.

Pero el request de `client_credentials` documentado (`manifiesto-api-gateway` §05.3 y `jwt-jwks-redis` §7.3c) **no tiene ningún campo para declarar el destino**:

```jsonc
{ "clientId": "cursos-service", "clientSecret": "…", "grantType": "client_credentials", "scope": "users.profile.read" }
```

**`DEC-17` · Resuelto:** el request suma un campo **`audience` obligatorio**, y el servidor lo valida contra el prefijo del `scope`. Contrato completo y reglas de rechazo en §10.2.

**Superficie real del problema hoy** — el catálogo de scopes (§04.0b del manifiesto) es cerrado y tiene tres filas:

| Scope | Destino real |
|---|---|
| `users.profile.read` | `users-service`, vía HTTP |
| `users.padron.notify` | **ninguno — es Kafka**, ida y vuelta con Cursos: el `aud` nunca pasa por un filtro del Gateway |
| `mailing.debug.read` | `mailing`, que hoy **no está en la allowlist** (`DEC-06`) |

O sea: **un solo scope de los tres genera tráfico HTTP por el Gateway**. El problema es real y bloqueaba `emitirServicio()`, pero su superficie es mínima.

**Por qué un campo explícito y no derivarlo del scope** (que hoy funcionaría, porque el primer segmento ya codifica el destino): el modo de falla. Ver §10.2.

**Por qué no un `aud` fijo por fila de `service_clients`:** un cliente que legítimamente hable con dos servicios necesitaría dos `clientId`, y duplica información que el `scope` ya lleva.

📢 **Cambio de contrato.** El body de `client_credentials` está documentado en `manifiesto-api-gateway` §05.3 y en `jwt-jwks-redis` §7.3c: hay que actualizar los dos y avisarle a los equipos consumidores. El vehículo es el runbook de `SPEC-api-gateway.md` §6.5.

⚠️ **Dos cosas que arrastra esta decisión, todavía abiertas:**

1. `users.padron.notify` no tiene destino HTTP. ¿Emite token igual, o es un scope que nunca debería salir por `client_credentials`? **`TODO-16`.**
2. `mailing.debug.read` apunta a un servicio fuera de la allowlist (INC-25). Si Mailing salió del alcance, sacarlo de `service_clients` deja el catálogo emitible en **un solo scope**.

---

### INC-25 🔵 ✅ · Runbooks del anexo desactualizados · **cerrada por `DEC-39`**

`jwt-jwks-redis` §08, runbook "Iniciar sesión", paso 3: *"Auth genera un código 2FA, lo guarda en Redis (TTL 5 min) y **lo manda por email (mailing)**"*.

Contradice la revisión que sacó Mailing del alcance: hoy `auth/` **renderiza** el mail y **publica un evento**; no lo manda. El mismo runbook dice *"Auth le pide a **Usuarios** que verifique las credenciales"* como si fueran dos servicios.

> ✅ **Cerrada por `DEC-39` · regla de precedencia documental.** El anexo `jwt-jwks-redis-explicado.html` es material **didáctico**, escrito antes del parche v5 y nunca actualizado por completo — él mismo lo admite (*"dejamos el razonamiento viejo en el resto del documento tal cual estaba"*).
>
> **Regla:** ante cualquier discrepancia entre el anexo y estas dos specs, **prevalece la spec**, sin excepción y sin analizar caso por caso. El anexo sirve para entender **por qué** se diseñó algo; no es fuente de **qué** implementar.
>
> La única excepción documentada es `INC-01`, donde el anexo tenía razón y el manifiesto estaba mal — y ahí la decisión se tomó explícitamente (`DEC-22`), no por precedencia.


---

### INC-26 🔵 ✅ · Canal del 2FA y los tres puntos residuales · **cerrada por `DEC-38`**

El PRD, en su tabla de definiciones pendientes, lista bajo *Seguridad (residual)*: *"Política de contraseñas, **canal del 2FA (TOTP/email/SMS)**, gestión y expiración de sesiones, cifrado de datos sensibles (legajo, email) — definible en LL"*.

Los manifiestos ya lo cerraron: **2FA por email**, detrás de `SecondFactorProvider` para poder sumar TOTP después. No es una contradicción de fondo (el manifiesto es posterior y decide).

**`DEC-38` · Los otros tres puntos de esa fila, cerrados.** Cierra `TODO-15`.

**a) Política de contraseñas** — criterio NIST SP 800-63B: largo, no complejidad.

| Regla | Valor |
|---|---|
| Mínimo | **12 caracteres** |
| Máximo | 🔴 **72 bytes**, obligatorio |
| Composición | **ninguna regla** de mayúsculas/dígitos/símbolos |
| Lista de bloqueo | rechazar un top-1000 de contraseñas comunes, en el repo |
| Rotación forzada | **no existe**, salvo `debe_cambiar_password` |
| Costo de BCrypt | **12** |

> 🔴 **El máximo de 72 bytes no es cosmético.** BCrypt **trunca silenciosamente** en 72 bytes: sin ese límite, una contraseña de 100 caracteres se valida contra sus primeros 72 y el usuario cree tener una seguridad que no tiene. Peor: con acentos en UTF-8 el corte puede caer a mitad de un carácter. **Validar en bytes, no en caracteres.**
>
> **Por qué sin reglas de composición:** obligar a "una mayúscula y un símbolo" produce `Password1!` — 10 caracteres predecibles. Doce caracteres libres tienen más entropía real y la gente los recuerda. Es lo que recomienda NIST desde 2017.

**b) Expiración de sesión más allá del `exp`.** No se agrega nada: el tope efectivo ya es la **vida del refresh (7 días)**, y la sesión única (`DEC-22`) hace que un login nuevo mate al anterior. **No hay timeout por inactividad**, a propósito: implementarlo obligaría a **escribir** en Redis en cada request para mover la marca de "última actividad" — hoy el Gateway solo lee (§11), y romper eso convierte a Redis en un cuello de escritura por el pico de examen. El costo no compensa.

**c) Cifrado en reposo de PII.** `email` y `legajo` **no se cifran a nivel columna**, y es una decisión, no un olvido: `email` es el identificador de login y tiene un índice único (`DEC-21`); cifrarlo rompe el índice, la búsqueda y la collation case-insensitive (`DEC-20` regla 4). La protección de esos campos es **de infraestructura** (cifrado de disco, red privada sin puerto publicado, U11) más las reglas de logging de §17.3, no criptografía por campo. `password_hash` no es PII cifrable: ya es un hash de un solo sentido.

⚠️ Si el docente exige cifrado por campo, el candidato es `legajo` — no participa de ningún índice de búsqueda. `email` no es viable sin rediseñar el login.

---

### Consolidado de `TODO`s

> ⚠️ **Los `TODO-xx` son locales a este documento**, a diferencia de los `INC-xx` y los `DEC-xx`, que sí comparten numeración entre las dos specs. `TODO-04` acá **no** es `TODO-04` en la otra spec. Citá siempre el archivo junto al ID.

| ID | Qué falta | Bloquea |
|---|---|---|
| ~~**TODO-01**~~ | ~~Pin del release train de Spring Cloud~~ | ✅ **cerrado por `DEC-35`**: `2025.1.3`, verificado |
| ~~**TODO-02**~~ | ~~Puerto de `users-service`~~ | ✅ **cerrado por `DEC-28`** (§2): app `8082`, management `8083` |
| ~~**TODO-03**~~ | ~~`Dockerfile` / `docker-compose`~~ | ✅ **cerrado por `DEC-40`** (`SPEC-api-gateway.md` §16) |
| ~~**TODO-04**~~ | ~~Origen del par de claves RS256~~ | ✅ **cerrado por `DEC-18`** (§10.7) |
| ~~**TODO-05**~~ | ~~Endpoints de whitelist + flujo de solicitud del PROFESSOR~~ | ✅ **cerrado por `DEC-29`** (§6.2b, §14.2) |
| **TODO-06** | Endpoint de subida/lectura de avatar contra MinIO | ⏭️ **fuera del sprint (`DEC-30`)** — `avatarRef` quedó opcional para no bloquear el gate 3 |
| ~~**TODO-07**~~ | ~~¿Se puede reusar un email tras la baja lógica?~~ | ✅ **cerrado por `DEC-21`** (§6.1): sí, vía columna generada |
| ~~**TODO-08**~~ | ~~Cómo declara el cliente el `aud` en `client_credentials`~~ | ✅ **cerrado por `DEC-17`** (§10.2) |
| ~~**TODO-09**~~ | ~~Umbrales del rate limit de login~~ | ✅ **cerrado por `DEC-42`**: 5 fallos / 15 min por email |
| **TODO-10** | **Solo el nombre del tópico** hacia `notifications-service` — el payload ya es nuestro y está fijado (`DEC-34`) | una property |
| **TODO-11** | **Solo los nombres de los tópicos** con Cursos — el flujo y los payloads están definidos (`DEC-34`, §13.4b) | dos properties |
| ~~**TODO-12**~~ | ~~Tópico de auditoría~~ | ✅ **cerrado por `DEC-37`**: `tema-01-users.auditoria.v1` |
| ~~**TODO-13**~~ | ~~Canal de la alerta del break-glass~~ | ✅ **cerrado por `DEC-32`** (§16.4): log + evento + mail a ADMIN |
| ~~**TODO-14**~~ | ~~Texto y hosting de los T&C~~ | ✅ **cerrado por `DEC-31`** (§16.6): mock versionado + endpoint público |
| ~~**TODO-15**~~ | ~~Política de contraseñas, expiración, cifrado de PII~~ | ✅ **cerrado por `DEC-38`** (§18/INC-26) |
| ~~**TODO-16**~~ | ~~¿Emite token `client_credentials` el scope `users.padron.notify`?~~ | ✅ **cerrado por `DEC-26`** (§10.2): no |

---


### 18.1 Idioma del contrato · `DEC-46`

Un mismo endpoint no puede ser mitad y mitad. `/api/users/me` al lado de
`/api/users/public/registro/alumno` es lo primero que se nota leyendo la API, y
no había una razón detrás: los manifiestos se escribieron en castellano y la
implementación los siguió.

**Va en inglés todo lo que es nuestro y se lee como código:**

| Superficie | Ejemplo |
|---|---|
| Rutas | `/api/users/public/registration/student`, `PATCH /api/users/{id}/role` |
| Campos JSON | `firstNames`, `accountStatus`, `mustChangePassword`, `challengeId` |
| `type` de error | `invalid-credentials`, `pending-account`, `route-not-found` |
| Valores de enum | `STUDENT`, `PROFESSOR`, `ACTIVE`, `PENDING_COURSE`, `APPROVED` |
| Columnas y migraciones | `first_names`, `account_status`, `terms_accepted_at` |
| Identificadores Java, métodos, claves de configuración | `RegistrationService`, `users.legal.terms-version` |
| `title` y `detail` de `problem+json` | `"Invalid credentials"`, `"Wrong username or password."` |

**Queda en castellano, y cada caso por un motivo distinto:**

- **`legajo`.** No tiene traducción fiel. *Student ID* y *file number* pierden
  el sentido institucional y el PRD lo nombra así. Misma clase de palabra que
  `CUIT` o `IBAN`.
- **Kafka: nombres de tópico, `eventType` y claves del payload.**
  `tema-NN-<subsistema>.<evento>.vN` es la convención acordada entre los diez
  subsistemas de la plataforma. Dos de los cuatro tópicos que tocamos son de
  otros equipos, y `ALUMNO_REGISTRADO` lo consume Cursos. Renombrarlos de este
  lado rompe a quien está del otro. La frontera con otro equipo no es nuestra
  para renombrar; el catálogo de tópicos sigue siendo configuración (`DEC-34`),
  así que quien quiera otro nombre lo pone por variable de entorno.
- **El texto que lee la persona usuaria.** No porque sea intocable, sino porque
  **no viaja por la API**. El backend manda `type`; el frontend tiene el mapa de
  `type` a la frase en castellano y esa frase gana sobre el `detail`. Así el
  idioma de la pantalla se cambia sin tocar un microservicio, y una API en
  inglés nunca le llega en inglés a un estudiante. El `detail` existe igual, en
  inglés, para el log, el test y el equipo del subsistema de al lado.

El costo de decidirlo tarde es lineal en la cantidad de consumidores, así que se
decide una vez, acá, y no se vuelve a discutir por endpoint.

## 19. Definition of Done

Del manifiesto §12.2, más lo que agregan las decisiones de §18.0.

| # | Criterio | Prueba |
|---|---|---|
| 1 | Levanta como **un solo proceso**, se registra en Eureka como `users-service`, `/actuator/health` reporta **MySQL y Redis** | smoke test |
| 2 | Un alumno se registra con código de invitación en el mismo form; la cuenta pasa `PENDING_EMAIL → PENDING_COURSE` tras activar el mail, y publica `ALUMNO_REGISTRADO` | `StudentRegistrationIT` con Testcontainers (MySQL + Kafka) |
| 3 | **Login completo:** credenciales → código 2FA generado y mail renderizado → evento publicado → código verificado → tokens emitidos con `sid` nuevo y `session:{userId}` escrito. **Ninguna llamada HTTP interna** entre `auth/` y `users/`, ninguna hacia `notifications-service` | `LoginFlowIT` |
| 4 | Un **segundo login** del mismo usuario **sobrescribe `session:{userId}`** con el `sid` nuevo, y el `sid` del token viejo deja de coincidir con el valor en Redis | `SingleSessionIT` con Testcontainers Redis — **sin Gateway**: se verifica el estado en Redis, que es lo único que este repo controla. El `401` efectivo lo prueba `SessionInvalidationIT` del Gateway (su DoD #7), que es de quien depende |
| 5 | `CourseValidationListener` consume el evento de vuelta y mueve `PENDING_COURSE → ACTIVE`, **sin persistir el detalle** de cómo se resolvió | `CourseValidationListenerIT` |
| 6 | Reprocesar el **mismo `eventId`** dos veces es un no-op verificable (**DEC-13**) | ídem: publicar el evento dos veces, assert de una sola transición y una sola fila en `processed_events` |
| 7 | Antes de completar el onboarding, **cualquier ruta fuera de las exentas rechaza con `403`**, aunque la cuenta esté `ACTIVE` y `primer_login = true` | `AccountGateIT` — los tres gates, con sus tres `type` distintos |
| 8 | `CredentialService.verifyCredentials()` es invocado **directamente** por `AuthService` — se puede probar con un **test unitario sin levantar ningún servidor HTTP** | `AuthServiceTest` (Mockito, sin `@SpringBootTest`) |
| 9 | Las reglas de ADMIN (password inicial, no auto-baja, no cero ADMIN, baja reforzada **con confirmación escrita**) funcionan cruzando los dos módulos **sin red de por medio** | `AdminRulesIT` |
| 10 | Un cliente externo obtiene un token de servicio con rol `MS` vía `POST /api/users/public/auth/token` y lo usa contra `GET /api/users/profile/{id}`; un token **sin rol `MS`** o con `scope` incorrecto es rechazado | `ServiceTokenIT` |
| 11 | **La frontera entre módulos se sostiene** | test de arquitectura (§5.3): `auth/` no importa entidades ni repos de `users/`; `users/` no importa nada de Redis |
| 12 | **(DEC-07)** Todo token emitido lleva `iss: "users-service"` | `TokenServiceTest` — assert sobre ambos tipos de token |
| 13 | **(DEC-02)** El logout borra `session:{userId}` | `LogoutIT` con Testcontainers Redis — assert de que la key no existe |
| 14 | **(DEC-04 + DEC-17)** Todo token de servicio lleva `aud` y `scope`; un request **sin `audience`**, con **scopes de dos prefijos distintos**, o con un `audience` que **no deriva del scope**, devuelve `400` **sin emitir token** | `TokenServiceTest` — un caso por cada una de las tres reglas de §10.2 |
| 15 | **(DEC-05)** El filtro parsea `X-Service-Scopes: MS,users.profile.read` y `hasRole('MS')` + `hasAuthority('users.profile.read')` matchean | `GatewayIdentityFilterTest` |
| 16 | **(DEC-08)** Un request sin headers de identidad a una ruta privada da `401`; uno a `/public/**` pasa. **No hay `JwtDecoder` en el contexto** | `SecurityConfigIT` |
| 17 | **(DEC-11)** El alta sin `tycAceptado` da `400`; la baja de ADMIN sin `usernameConfirmation` correcto da `400` | `RegistrationIT`, `AdminRulesIT` |
| 18 | **(DEC-16)** `…/reset` responde idéntico exista o no el email; `…/reset/confirm` consume el token de 1 uso, revoca los refresh, y un segundo intento con el mismo token da `400` | `PasswordResetIT` |
| 19 | **(DEC-18)** Con `JWT_PRIVATE_KEY_PATH` inexistente o ilegible la aplicación **no arranca**; el JWKS publica **todas** las públicas de `JWT_PUBLIC_KEYS_DIR`, y el `kid` del token emitido es `JWT_ACTIVE_KID` | `SigningKeyProviderTest` + `JwksIT` — assert de que **no existe** ningún camino que genere claves en memoria |
| 20 | **(DEC-20)** Con **dos bajas de ADMIN concurrentes** sobre una plataforma con exactamente dos ADMIN, una falla y queda uno — nunca cero | `AdminRulesIT` — dos hilos, misma transacción de baja, assert de `count(ADMIN activos) >= 1` |
| 21 | **(DEC-20)** Un `Instant` guardado y releído desde **dos instancias con timezone de sistema distinta** devuelve el mismo valor | `TimestampIT` con Testcontainers MySQL — assert de igualdad exacta al microsegundo |
| 22 | **(DEC-21)** Dar de baja una cuenta y registrar **la misma dirección de email** de nuevo funciona y crea una fila nueva con otro `id`; dos cuentas **activas** con el mismo email fallan con violación de unicidad | `EmailReuseIT` con Testcontainers MySQL |
| 23 | **(DEC-22)** Con el dispositivo A superado por un login de B, `POST /auth/refresh` de A devuelve `401` **y deja revocada toda la familia** de A; el refresh de B sigue funcionando y su access nuevo mantiene el mismo `sid` | `SingleSessionRefreshIT` con Testcontainers Redis |
| 24 | **(DEC-23)** `deactivate()` borra `session:{userId}`; el access token de esa persona, todavía sin expirar, deja de servir en el request siguiente | `DeactivationEndsSessionIT` |
| 25 | **(DEC-23)** El access de una cuenta en `PENDING_COURSE` lleva `est: "PENDING_COURSE"`; tras `activateAfterCourseValidation()`, un `/auth/refresh` devuelve un access con `est: "ACTIVE"` **sin re-login** | `AccountStatusInTokenIT` |
| 26 | **(DEC-33)** Un código de activación vencido, uno incorrecto y un email inexistente devuelven **la misma respuesta**; al 5º intento fallido el código se invalida y hace falta reenviar | `ActivationCodeIT` |
| 27 | **(DEC-30)** `PATCH /api/users/me/onboarding` **sin `avatarRef`** cierra el gate 3 y la persona accede a la plataforma | `OnboardingWithoutAvatarIT` — es el test que evita encerrar a todo usuario nuevo |
| 28 | **(DEC-29)** Aprobar una solicitud marca `APPROVED` **e** inserta en `email_whitelist` en la misma transacción; si falla el insert, la solicitud sigue `PENDING` | `WhitelistRequestIT` |
| 29 | **(DEC-32)** El comando de break-glass crea el ADMIN **aunque Kafka y el mail fallen**, y deja el log `ERROR` igual | `AdminRecoveryCommandTest` con los publishers rotos a propósito |
| 30 | **(DEC-42)** Al 6º **fallo** de login sobre el mismo email dentro de la ventana, la respuesta es `429` con `Retry-After`; un login **exitoso** no consume presupuesto | `RateLimitLoginIT` con Testcontainers Redis |
| 31 | **(DEC-44)** `TokenContractTest`: **todo** token de persona lleva `iss`, `sub`, `roles`, `type`, `jti`, `sid`, `est`, `pwd`, `onb`, `iat`, `exp`; todo token de servicio lleva `iss`, `sub`, `roles`, `type`, `aud`, `scope`, `jti`, `iat`, `exp`. Si falta uno, falla nombrando el claim | es lo que reemplaza al flag de despliegue del Gateway |
| 32 | **(DEC-45a)** No existe forma de construir un `TokenClaims` sin los claims obligatorios | revisión de la firma del builder + `TokenContractTest` como segunda línea |
| 33 | **(DEC-45b)** Con **Kafka detenido**, un alta de alumno **igual completa** y el evento queda en `outbox_events` con `published_at IS NULL`; al levantar Kafka, el poller lo publica **sin intervención** | `OutboxIT` con Testcontainers — parar el contenedor de Kafka, hacer el alta, levantarlo, esperar |
| 34 | **(DEC-45c)** Un test parametrizado recorre **todo** el enum `EmailType` y verifica que cada plantilla existe y renderiza sin variables sin resolver | `EmailTypeTest` — hoy esto no se puede escribir sin listar los seis a mano |
| 35 | **(DEC-45d)** Toda transición fuera de la tabla de §9.2 lanza `InvalidTransitionException`; en particular **`DEACTIVATED` no transiciona a nada** | `TransitionsTest` parametrizado sobre el producto cartesiano de estados |

### Compatibilidad con el Gateway

Antes de integrar, verificar en este orden — **el orden importa**:

1. `users-service` **emite `iss` + `est`/`pwd`/`onb`** (`DEC-07`, `DEC-23`) desde el primer commit: son parte del contrato de token de §10.1, no un paso aparte. `TokenContractTest` (DoD #31) lo garantiza en CI. El Gateway los valida siempre, sin flags (`DEC-44`).
2. `users-service` emite `aud` + `scope` en tokens de servicio (`DEC-04`) → recién después el Gateway activa `ServiceAudienceFilter`.
3. Ambos lados usan el mismo puerto en `jwk-set-uri`: **8082** (`DEC-28`).
4. El formato de headers de `DEC-05` matchea en los dos lados.
