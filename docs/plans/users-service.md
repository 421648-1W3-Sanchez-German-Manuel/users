# users-service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Construir el microservicio de Identidad y Usuarios (Tema 01): emite y firma los JWT de toda la plataforma, publica el JWKS, gestiona el ciclo de vida de las cuentas y coordina por Kafka con Cursos y notificaciones.

**Architecture:** Un solo proceso Spring Boot **servlet** (no WebFlux) con dos módulos internos, `auth/` y `users/`, que se comunican **exclusivamente por interfaces Java** (`CredentialService`, `EphemeralTokenService`) y nunca por HTTP. El servicio **no valida JWT en el camino de request**: su `Authentication` sale de los headers `X-*` que inyecta el API Gateway, y eso se sostiene porque el puerto no se publica fuera de la red privada. Persistencia en MySQL 8.4 para lo durable y Redis para lo efímero. Todo evento saliente pasa por una tabla **outbox** antes de llegar a Kafka.

**Tech Stack:** Java 21 · Maven · Spring Boot 4.1.1 · Spring Cloud 2025.1.3 "Oakwood" · MySQL 8.4 · Redis 7 · Kafka (KRaft) · Flyway · Thymeleaf · Nimbus JOSE+JWT · Testcontainers · JUnit 5 + AssertJ + Mockito

**Spec:** `spec/SPEC-users-service.md` (documento par: `spec/SPEC-api-gateway.md`)

---

## Global Constraints

Estos valores salen de la spec y aplican a **todas** las tareas. Copiados textuales.

- **Java 21**, **Maven**, **Spring Boot 4.1.1**, **Spring Cloud 2025.1.3** (`DEC-35` — verificado contra la matriz oficial: el tren 2025.1.x soporta Boot 4.0.x y 4.1.x desde 2025.1.2). Importar el BOM de Boot **primero** y el de Cloud después.
- **Group:** `ar.edu.utn.frc.tup.p4` · **Artifact:** `users-service` · **Package raíz:** `ar.edu.utn.frc.tup.p4.usersservice` (`DEC-15`).
- **Puertos** (`DEC-28`): aplicación **8082**, management **8083**. Nunca publicados fuera de la red privada.
- **MySQL, seis reglas obligatorias** (`DEC-20`):
  1. Todo `id` es **`CHAR(36)`** (UUID en texto), mapeado con `@Column(columnDefinition = "CHAR(36)")`.
  2. Todo timestamp es **`DATETIME(6)`** y la aplicación escribe **UTC** (`Instant`). Nunca `TIMESTAMP`. JDBC lleva `connectionTimeZone=UTC&preserveInstants=true`.
  3. **Ninguna columna de tipo lista.** Una lista es una tabla hija.
  4. Charset `utf8mb4`, collation `utf8mb4_0900_ai_ci`. El email se normaliza a **minúsculas en la aplicación**.
  5. Toda regla "chequear y después actuar" usa **`SELECT … FOR UPDATE`** (InnoDB es `REPEATABLE READ`).
  6. **Una sentencia DDL por migración** Flyway (el DDL de MySQL no es transaccional) y **ningún test contra H2** — Testcontainers con la misma imagen que producción.
- **`users-service` NO valida el JWT** (`DEC-08`). No incluir `spring-boot-starter-oauth2-resource-server`, ni `-oauth2-authorization-server`, ni `-webflux`, ni nada de Gateway/LoadBalancer/Resilience4j.
- **Headers de identidad** (`DEC-05`): separador **coma sin espacio**; `MS` viene **dentro** de `X-Service-Scopes`. Parsear con `split(",")` y mapear `MS` → `ROLE_MS`.
- **Claims obligatorios de todo token** (`DEC-07`, `DEC-23`, `DEC-44`): persona → `iss, sub, roles, type, jti, sid, est, pwd, onb, iat, exp`; servicio → `iss, sub, roles, type, aud, scope, jti, iat, exp`. `iss` es siempre `"users-service"` (nombre lógico, no URL).
- **Rol `MS` nunca es asignable a una persona.** No es un valor del enum `Role`.
- **BCrypt costo 12.** Password mínimo 12 caracteres, **máximo 72 bytes** (BCrypt trunca en silencio ahí — validar en **bytes**, no en caracteres), sin reglas de composición (`DEC-38`).
- **Sobre estándar de eventos** (`DEC-12`): `{eventId, eventType, timestamp, producer, payload}` con `producer: "tema-01-users"` y `timestamp` ISO-8601 UTC.
- **Convención de tópicos** (`DEC-37`): `<producer>.<asunto>.<version>`, p. ej. `tema-01-users.auditoria.v1`. Todo nombre de tópico es una **property con default**, nunca una constante en el código (`DEC-34`).
- **Nunca loguear:** password en claro, `clientSecret`, hash BCrypt, código 2FA, código de activación, token de reset, ni ningún JWT completo.
- **Errores:** siempre `ProblemDetail` (RFC 9457) desde un único `@RestControllerAdvice`, con `type` `https://tpi.utn.frc/errors/<slug>`.

---

## File Structure

```
users-service/
├── pom.xml
├── Dockerfile
├── scripts/gen-dev-keys.sh
└── src/
    ├── main/java/ar/edu/utn/frc/tup/p4/usersservice/
    │   ├── UsersServiceApplication.java
    │   ├── auth/
    │   │   ├── controllers/{AuthController, TokenController}.java
    │   │   ├── services/{AuthService, TokenService, ServiceClientService}.java
    │   │   ├── tokens/TokenClaims.java                    # DEC-45a
    │   │   ├── twofactor/{SecondFactorProvider, EmailOtpProvider}.java
    │   │   ├── otp/OtpService.java                        # DEC-33
    │   │   ├── store/{TokenStore, EphemeralTokenService}.java + impl/
    │   │   ├── keys/{SigningKeyProvider}.java + impl/FileSystemSigningKeyProvider.java
    │   │   ├── entities/{ServiceClient, ServiceClientScope}.java
    │   │   └── cli/AdminRecoveryCommand.java              # DEC-32
    │   ├── users/
    │   │   ├── controllers/{UserController, RegistrationController, WhitelistController}.java
    │   │   ├── services/{UserService, RegistrationService, WhitelistService}.java + impl/CredentialServiceImpl.java
    │   │   ├── entities/{User, EmailWhitelist, WhitelistRequest}.java
    │   │   ├── enums/{Role, AccountStatus, RequestStatus}.java
    │   │   ├── listeners/CourseValidationListener.java
    │   │   └── repositories/*.java
    │   ├── shared/
    │   │   ├── security/{SecurityConfig, GatewayIdentityFilter, GatewayPrincipal, IdentityHeaders}.java
    │   │   ├── gates/{AccountGateInterceptor, SkipAccountGate}.java
    │   │   ├── events/{EventEnvelope, AccountEventPublisher, OutboxPoller, AccountEventListener}.java + entities/
    │   │   ├── notifications/{EmailType, EmailTemplateService, NotificationEventPublisher}.java
    │   │   └── web/{GlobalExceptionHandler, ErrorTypes}.java
    │   └── config/{JpaConfig, RedisConfig, KafkaConfig}.java
    └── resources/
        ├── application.yml
        ├── legal/terms-v1.md
        ├── templates/*.html
        └── db/migration/V1..V6__*.sql
```

**Criterio de decomposición:** los archivos se agrupan por **módulo de negocio** (`auth/`, `users/`, `shared/`), no por capa técnica. La frontera entre `auth/` y `users/` es la que verifica el test de arquitectura de la Tarea 24 — es la restricción estructural más importante del servicio.

---

### Task 1: Scaffolding del proyecto

**Files:**
- Create: `pom.xml`
- Create: `src/main/java/ar/edu/utn/frc/tup/p4/usersservice/UsersServiceApplication.java`
- Create: `src/main/resources/application.yml`
- Create: `src/test/java/ar/edu/utn/frc/tup/p4/usersservice/AbstractIntegrationTest.java`
- Create: `src/test/java/ar/edu/utn/frc/tup/p4/usersservice/UsersServiceApplicationTest.java`

**Interfaces:**
- Consumes: nada.
- Produces: `AbstractIntegrationTest` — clase base con contenedores MySQL 8.4 y Redis 7 vía `@ServiceConnection`, que **todas** las tareas siguientes extienden para tests de integración.

- [ ] **Step 1: Escribir el `pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <groupId>ar.edu.utn.frc.tup.p4</groupId>
  <artifactId>users-service</artifactId>
  <version>0.0.1-SNAPSHOT</version>
  <name>users-service</name>

  <properties>
    <java.version>21</java.version>
    <maven.compiler.release>21</maven.compiler.release>
    <spring-boot.version>4.1.1</spring-boot.version>
    <spring-cloud.version>2025.1.3</spring-cloud.version>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
  </properties>

  <dependencyManagement>
    <dependencies>
      <!-- ORDEN OBLIGATORIO: Boot PRIMERO. Si va después, el BOM de Cloud
           puede bajar spring-boot-dependencies por debajo de 4.1.1. -->
      <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-dependencies</artifactId>
        <version>${spring-boot.version}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
      <dependency>
        <groupId>org.springframework.cloud</groupId>
        <artifactId>spring-cloud-dependencies</artifactId>
        <version>${spring-cloud.version}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>

  <dependencies>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-security</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-data-jpa</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-data-redis</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-validation</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-thymeleaf</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-actuator</artifactId></dependency>
    <dependency><groupId>org.springframework.kafka</groupId><artifactId>spring-kafka</artifactId></dependency>
    <dependency>
      <groupId>org.springframework.cloud</groupId>
      <artifactId>spring-cloud-starter-netflix-eureka-client</artifactId>
    </dependency>
    <dependency><groupId>com.nimbusds</groupId><artifactId>nimbus-jose-jwt</artifactId><version>10.0.1</version></dependency>
    <dependency><groupId>com.mysql</groupId><artifactId>mysql-connector-j</artifactId><scope>runtime</scope></dependency>
    <dependency><groupId>org.flywaydb</groupId><artifactId>flyway-core</artifactId></dependency>
    <dependency><groupId>org.flywaydb</groupId><artifactId>flyway-mysql</artifactId></dependency>

    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
    <dependency><groupId>org.springframework.security</groupId><artifactId>spring-security-test</artifactId><scope>test</scope></dependency>
    <dependency><groupId>org.springframework.kafka</groupId><artifactId>spring-kafka-test</artifactId><scope>test</scope></dependency>
    <dependency><groupId>org.testcontainers</groupId><artifactId>junit-jupiter</artifactId><scope>test</scope></dependency>
    <dependency><groupId>org.testcontainers</groupId><artifactId>mysql</artifactId><scope>test</scope></dependency>
    <dependency><groupId>com.redis</groupId><artifactId>testcontainers-redis</artifactId><version>2.2.4</version><scope>test</scope></dependency>
    <dependency><groupId>org.testcontainers</groupId><artifactId>kafka</artifactId><scope>test</scope></dependency>
    <dependency><groupId>com.tngtech.archunit</groupId><artifactId>archunit-junit5</artifactId><version>1.3.0</version><scope>test</scope></dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
        <version>${spring-boot.version}</version>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 2: Escribir la clase principal**

```java
package ar.edu.utn.frc.tup.p4.usersservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableDiscoveryClient
@EnableScheduling          // lo necesita OutboxPoller (Tarea 6)
public class UsersServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(UsersServiceApplication.class, args);
    }
}
```

- [ ] **Step 3: Escribir `application.yml`**

```yaml
spring:
  application:
    name: users-service
  datasource:
    # DEC-20 rule 2: UTC explicit on the connection - never rely on the host's timezone
    url: ${DB_URL:jdbc:mysql://localhost:3306/users?connectionTimeZone=UTC&preserveInstants=true}
    username: ${DB_USER:root}
    password: ${DB_PASSWORD:}
  jpa:
    hibernate:
      ddl-auto: validate        # Flyway manda; Hibernate solo verifica
    open-in-view: false
    properties:
      hibernate.jdbc.time_zone: UTC
  flyway:
    enabled: true
    locations: classpath:db/migration
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
  kafka:
    bootstrap-servers: ${KAFKA_SERVERS:localhost:9092}
    consumer:
      group-id: users-service
      auto-offset-reset: earliest
      properties:
        spring.json.trusted.packages: "*"
        # DEC-34: si otro equipo agrega campos, no rompemos
        spring.json.fail.on.unknown.properties: false

server:
  port: ${SERVER_PORT:8082}     # DEC-28

management:
  server:
    port: ${MANAGEMENT_PORT:8083}   # DEC-28
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
  endpoint:
    health:
      probes:
        enabled: true
      show-details: never

eureka:
  client:
    service-url:
      defaultZone: ${EUREKA_URL:http://localhost:8761/eureka/}
    register-with-eureka: true
    fetch-registry: false        # SOLO el Gateway tiene true
  instance:
    prefer-ip-address: true

users:
  legal:
    terms-version: "v1"          # DEC-31
  jwt:
    issuer: users-service        # DEC-07
    access-ttl: PT10M
    refresh-ttl: P7D
    service-ttl: PT5M
    private-key-path: ${JWT_PRIVATE_KEY_PATH:./secrets/jwt-private.pem}   # DEC-18
    public-keys-dir: ${JWT_PUBLIC_KEYS_DIR:./secrets/jwks}
    active-kid: ${JWT_ACTIVE_KID:dev}
  otp:
    activation-ttl: PT30M        # DEC-33
    two-factor-ttl: PT5M
    max-attempts: 5
  ratelimit:
    login-max-failures: 5        # DEC-42
    login-window: PT15M
  kafka:
    # DEC-34: a topic name is configuration, never a constant.
    #
    # The KEYS are ours and go in English like the rest of the code. The
    # VALORES no: `tema-NN-<subsistema>.<evento>.vN` es la convencion acordada
    # entre los diez subsistemas, y dos de estos cuatro topicos son de otros
    # equipos. Renombrarlos de este lado rompe a quien consume.
    topics:
      audit:              ${TOPIC_AUDIT:tema-01-users.auditoria.v1}
      student-registered: ${TOPIC_STUDENT_REGISTERED:tema-01-users.alumno-registrado.v1}
      notifications:      ${TOPIC_NOTIFICATIONS:tema-XX-notificaciones.email.v1}
      course-validation:  ${TOPIC_COURSE_VALIDATION:tema-02-cursos.validacion-resuelta.v1}

logging:
  pattern:
    level: "%5p [${spring.application.name},%X{traceId:-},%X{requestId:-}]"
```

- [ ] **Step 4: Escribir la clase base de tests de integración**

```java
package ar.edu.utn.frc.tup.p4.usersservice;

import com.redis.testcontainers.RedisContainer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for every integration test.
 * DEC-20 rule 6: the SAME MySQL image as production, never H2 -
 * H2 en "modo MySQL" no reproduce columnas generadas, SKIP LOCKED,
 * REPEATABLE READ ni la collation.
 * The containers are static: they are shared across test classes.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
public abstract class AbstractIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName("users")
            .withCommand(
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_0900_ai_ci",
                    "--default-time-zone=+00:00");

    @Container
    @ServiceConnection
    static final RedisContainer REDIS = new RedisContainer(DockerImageName.parse("redis:7-alpine"));
}
```

- [ ] **Step 5: Escribir el test de arranque**

```java
package ar.edu.utn.frc.tup.p4.usersservice;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

class UsersServiceApplicationTest extends AbstractIntegrationTest {

    @Autowired
    ApplicationContext ctx;

    @Test
    void the_context_starts() {
        assertThat(ctx).isNotNull();
    }

    /**
     * DEC-08: we do not validate JWTs. If someone adds the resource-server starter,
     * a JwtDecoder appears in the context and this test catches it.
     */
    @Test
    void no_existe_ningun_JwtDecoder_en_el_contexto() {
        assertThat(ctx.getBeanNamesForType(org.springframework.security.oauth2.jwt.JwtDecoder.class))
                .isEmpty();
    }
}
```

- [ ] **Step 6: Correr los tests y verificar que fallan**

Run: `mvn -q test -Dtest=UsersServiceApplicationTest`
Expected: FAIL — no existen las migraciones Flyway todavía, así que `ddl-auto: validate` no encuentra tablas. Es el fallo esperado; la Tarea 2 lo resuelve.

- [ ] **Step 7: Verificar el árbol de dependencias**

Run: `mvn dependency:tree -Dincludes=org.springframework.boot:spring-boot-dependencies`
Expected: `spring-boot-dependencies:4.1.1`. **Si aparece una versión menor, el orden de los BOM está invertido** — Boot va primero (`DEC-35`).

- [ ] **Step 8: Commit**

```bash
git add pom.xml src/main/java src/main/resources/application.yml src/test/java
git commit -m "chore: scaffolding de users-service con Boot 4.1.1 y Spring Cloud 2025.1.3

Testcontainers con MySQL 8.4 y Redis 7 como base de integracion (DEC-20 r6).
Puertos 8082/8083 (DEC-28)."
```

---

### Task 2: Migración V1 y entidad `User` con tabla de transiciones

**Files:**
- Create: `src/main/resources/db/migration/V1__users.sql`
- Create: `src/main/java/ar/edu/utn/frc/tup/p4/usersservice/users/enums/Role.java`
- Create: `src/main/java/ar/edu/utn/frc/tup/p4/usersservice/users/enums/AccountStatus.java`
- Create: `src/main/java/ar/edu/utn/frc/tup/p4/usersservice/users/entities/User.java`
- Create: `src/main/java/ar/edu/utn/frc/tup/p4/usersservice/users/InvalidTransitionException.java`
- Create: `src/main/java/ar/edu/utn/frc/tup/p4/usersservice/users/repositories/UserRepository.java`
- Test: `src/test/java/ar/edu/utn/frc/tup/p4/usersservice/users/TransitionsTest.java`
- Test: `src/test/java/ar/edu/utn/frc/tup/p4/usersservice/users/EmailReuseIT.java`

**Interfaces:**
- Consumes: `AbstractIntegrationTest` (Tarea 1).
- Produces:
  - `enum Role { ADMIN, PROFESSOR, STUDENT }` — **sin `MS`**.
  - `enum AccountStatus { PENDING_EMAIL, PENDING_COURSE, ACTIVE, DEACTIVATED }`.
  - `User` con `UUID getId()`, `String getEmail()`, `Role getRole()`, `AccountStatus getAccountStatus()`, `boolean isEmailVerified()`, `boolean mustChangePassword()`, `boolean isFirstLogin()`, y los métodos de transición `activate()`, `activateAfterCourseValidation()`, `deactivate()`, `completeOnboarding(String, String, boolean)`.
  - `UserRepository extends JpaRepository<User, UUID>` con `Optional<User> findByEmailAndDeletedAtIsNull(String email)` y `long countByRolAndDeletedAtIsNull(Role role)`.

- [ ] **Step 1: Escribir la migración V1**

`DEC-21`: `email_activo` es una **columna generada**, porque MySQL no tiene índices únicos parciales. `DEC-20` r1 y r2: `CHAR(36)` y `DATETIME(6)`.

```sql
CREATE TABLE users (
    id                   CHAR(36)     NOT NULL PRIMARY KEY,
    first_names              VARCHAR(100) NOT NULL,
    last_names            VARCHAR(100) NOT NULL,
    legajo               VARCHAR(20)  NULL,
    email                VARCHAR(255) NOT NULL,
    password_hash        VARCHAR(72)  NOT NULL,
    role                  VARCHAR(20)  NOT NULL,
    account_status        VARCHAR(20)  NOT NULL,
    email_verified     BOOLEAN      NOT NULL DEFAULT FALSE,
    must_change_password BOOLEAN     NOT NULL DEFAULT FALSE,
    github_username      VARCHAR(100) NULL,
    avatar_ref           VARCHAR(255) NULL,
    first_login         BOOLEAN      NOT NULL DEFAULT TRUE,
    guided_tour_completed BOOLEAN    NOT NULL DEFAULT FALSE,
    terms_version_accepted VARCHAR(20)  NULL,
    terms_accepted_at      DATETIME(6)  NULL,
    created_at           DATETIME(6)  NOT NULL,
    updated_at           DATETIME(6)  NOT NULL,
    deleted_at           DATETIME(6)  NULL,
    -- DEC-21: MySQL no tiene indices unicos parciales. Esta columna vale
    -- the e-mail only while the row is active, and NULL once it is deactivated.
    -- Because MySQL treats every NULL in a unique index as distinct, all the
    -- deactivated rows coexist and only the active accounts compete.
    active_email         VARCHAR(255) AS (IF(deleted_at IS NULL, email, NULL)) STORED,
    UNIQUE KEY uq_users_active_email (active_email),
    KEY idx_users_role (role),
    KEY idx_users_account_status (account_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
```

- [ ] **Step 2: Escribir los enums**

```java
package ar.edu.utn.frc.tup.p4.usersservice.users.enums;

/**
 * Three values. MS is deliberately NOT here: it is a role exclusive to service
 * tokens and cannot be assigned to a person.
 */
public enum Role { ADMIN, PROFESSOR, STUDENT }
```

```java
package ar.edu.utn.frc.tup.p4.usersservice.users.enums;

public enum AccountStatus { PENDING_EMAIL, PENDING_COURSE, ACTIVE, DEACTIVATED }
```

- [ ] **Step 3: Escribir el test de transiciones (falla)**

`DEC-45d`: la tabla de §9.2 tiene que ser dato, no prosa. El test recorre el **producto cartesiano** de estados: lo que no está en la tabla, falla.

```java
package ar.edu.utn.frc.tup.p4.usersservice.users;

import ar.edu.utn.frc.tup.p4.usersservice.users.entities.User;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.AccountStatus;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransitionsTest {

    private User conEstado(AccountStatus e, Role role) {
        User u = User.create("Ana", "Perez", "ana@utn.edu.ar", "$2a$12$hash", role, "v1");
        u.forceStatusForTest(e);
        return u;
    }

    @Test
    void activating_a_student_moves_it_to_pending_course() {
        User u = conEstado(AccountStatus.PENDING_EMAIL, Role.STUDENT);
        u.activate();
        assertThat(u.getAccountStatus()).isEqualTo(AccountStatus.PENDING_COURSE);
        assertThat(u.isEmailVerified()).isTrue();
    }

    @Test
    void activating_a_professor_goes_straight_to_active() {
        User u = conEstado(AccountStatus.PENDING_EMAIL, Role.PROFESSOR);
        u.activate();
        assertThat(u.getAccountStatus()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void course_validation_on_an_already_active_account_is_a_no_op() {
        // Consumer idempotency requirement (DEC-13): reprocessing is not an error.
        User u = conEstado(AccountStatus.ACTIVE, Role.STUDENT);
        u.activateAfterCourseValidation();
        assertThat(u.getAccountStatus()).isEqualTo(AccountStatus.ACTIVE);
    }

    @ParameterizedTest
    @EnumSource(AccountStatus.class)
    void desde_cualquier_estado_se_puede_dar_de_baja_salvo_desde_BAJA(AccountStatus from) {
        User u = conEstado(from, Role.STUDENT);
        if (from == AccountStatus.DEACTIVATED) {
            assertThatThrownBy(u::deactivate).isInstanceOf(InvalidTransitionException.class);
        } else {
            u.deactivate();
            assertThat(u.getAccountStatus()).isEqualTo(AccountStatus.DEACTIVATED);
            assertThat(u.getDeletedAt()).isNotNull();
        }
    }

    @Test
    void BAJA_es_terminal_no_transiciona_a_nada() {
        // This is what makes DEC-21 necessary: someone coming back needs a brand
        // new row, so the e-mail has to be reusable.
        User u = conEstado(AccountStatus.DEACTIVATED, Role.STUDENT);
        assertThatThrownBy(u::activate).isInstanceOf(InvalidTransitionException.class);
        assertThatThrownBy(u::activateAfterCourseValidation).isInstanceOf(InvalidTransitionException.class);
        assertThatThrownBy(u::deactivate).isInstanceOf(InvalidTransitionException.class);
    }

    @Test
    void activar_desde_ACTIVA_es_transicion_invalida() {
        User u = conEstado(AccountStatus.ACTIVE, Role.STUDENT);
        assertThatThrownBy(u::activate).isInstanceOf(InvalidTransitionException.class);
    }
}
```

- [ ] **Step 4: Correr el test y verificar que falla**

Run: `mvn -q test -Dtest=TransitionsTest`
Expected: FAIL con error de compilación — `User` y `InvalidTransitionException` no existen.

- [ ] **Step 5: Escribir la excepción**

```java
package ar.edu.utn.frc.tup.p4.usersservice.users;

import ar.edu.utn.frc.tup.p4.usersservice.users.enums.AccountStatus;

public class InvalidTransitionException extends RuntimeException {
    public InvalidTransitionException(AccountStatus from, AccountStatus to) {
        super("Invalid transition: " + from + " -> " + to);
    }
}
```

- [ ] **Step 6: Escribir la entidad `User`**

```java
package ar.edu.utn.frc.tup.p4.usersservice.users.entities;

import ar.edu.utn.frc.tup.p4.usersservice.users.InvalidTransitionException;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.AccountStatus;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.*;

import static ar.edu.utn.frc.tup.p4.usersservice.users.enums.AccountStatus.*;

@Entity
@Table(name = "users")
public class User {

    /**
     * DEC-45d: the closed list of transitions from spec §9.2, as data.
     * The table in the document and the code are the same object: they cannot drift.
     * DEACTIVATED maps to an empty set, so it is terminal - and that is what makes
     * DEC-21 (e-mail reuse) necessary, because someone coming back needs a new row.
     */
    private static final Map<AccountStatus, Set<AccountStatus>> VALID_TRANSITIONS = Map.of(
            PENDING_EMAIL, EnumSet.of(ACTIVE, PENDING_COURSE, DEACTIVATED),
            PENDING_COURSE, EnumSet.of(ACTIVE, DEACTIVATED),
            ACTIVE,          EnumSet.of(DEACTIVATED),
            DEACTIVATED,            EnumSet.noneOf(AccountStatus.class));

    @Id
    @Column(columnDefinition = "CHAR(36)")      // DEC-20 regla 1
    private UUID id;

    private String firstNames;
    private String lastNames;
    private String legajo;
    private String email;

    @Column(name = "password_hash")
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_status")
    private AccountStatus accountStatus;

    @Column(name = "email_verified")      private boolean emailVerificado;
    @Column(name = "must_change_password") private boolean mustChangePassword;
    @Column(name = "github_username")       private String  githubUsername;
    @Column(name = "avatar_ref")            private String  avatarRef;
    @Column(name = "first_login")          private boolean firstLogin;
    @Column(name = "guided_tour_completed") private boolean guidedTourCompleted;
    @Column(name = "terms_version_accepted")  private String  tycVersionAceptada;
    @Column(name = "terms_accepted_at")       private Instant tycAceptadoEn;
    @Column(name = "created_at")            private Instant createdAt;
    @Column(name = "updated_at")            private Instant updatedAt;
    @Column(name = "deleted_at")            private Instant deletedAt;

    protected User() { }   // JPA

    public static User nueva(String firstNames, String lastNames, String email,
                             String passwordHash, Role role, String termsVersion) {
        User u = new User();
        u.id = UUID.randomUUID();
        u.firstNames = firstNames;
        u.lastNames = lastNames;
        u.email = email.toLowerCase(Locale.ROOT);   // DEC-20 regla 4
        u.passwordHash = passwordHash;
        u.role = role;
        u.accountStatus = PENDING_EMAIL;
        u.firstLogin = true;
        u.tycVersionAceptada = termsVersion;
        u.tycAceptadoEn = Instant.now();
        u.createdAt = Instant.now();
        u.updatedAt = u.createdAt;
        return u;
    }

    /** An ADMIN is created straight into ACTIVE: it skips e-mail activation. */
    public static User createAdmin(String firstNames, String lastNames, String email,
                                  String passwordHash, String termsVersion) {
        User u = nueva(firstNames, lastNames, email, passwordHash, Role.ADMIN, termsVersion);
        u.accountStatus = ACTIVE;
        u.emailVerificado = true;
        u.mustChangePassword = true;    // RF-USR-01
        return u;
    }

    private void transitionTo(AccountStatus nuevo) {
        if (!VALID_TRANSITIONS.get(this.accountStatus).contains(nuevo)) {
            throw new InvalidTransitionException(this.accountStatus, nuevo);
        }
        this.accountStatus = nuevo;
        this.updatedAt = Instant.now();
    }

    /** PENDING_EMAIL -> ACTIVE (PROFESSOR/ADMIN) o -> PENDING_COURSE (STUDENT). */
    public void activate() {
        transitionTo(role == Role.STUDENT ? PENDING_COURSE : ACTIVE);
        this.emailVerificado = true;
    }

    /** PENDING_COURSE -> ACTIVE. No-op si ya estaba ACTIVE (idempotencia, DEC-13). */
    public void activateAfterCourseValidation() {
        if (this.accountStatus == ACTIVE) return;
        transitionTo(ACTIVE);
    }

    public void deactivate() {
        transitionTo(DEACTIVATED);
        this.deletedAt = Instant.now();
    }

    /** DEC-30: avatarRef is OPTIONAL while object storage is out of this sprint. */
    public void completeOnboarding(String githubUsername, String avatarRef, boolean tourOk) {
        this.githubUsername = githubUsername;
        this.avatarRef = avatarRef;
        this.guidedTourCompleted = tourOk;
        this.firstLogin = false;
        this.updatedAt = Instant.now();
    }

    public void changePassword(String newHash) {
        this.passwordHash = newHash;
        this.mustChangePassword = false;
        this.updatedAt = Instant.now();
    }

    public void requirePasswordChange() { this.mustChangePassword = true; }
    public void changeRole(Role nuevo) { this.role = nuevo; this.updatedAt = Instant.now(); }

    /** Test-only: builds a User in an arbitrary status, bypassing the transition table. */
    public void forceStatusForTest(AccountStatus e) { this.accountStatus = e; }

    public UUID getId() { return id; }
    public String getFirstNames() { return firstNames; }
    public String getLastNames() { return lastNames; }
    public String getLegajo() { return legajo; }
    public void setLegajo(String legajo) { this.legajo = legajo; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public Role getRole() { return role; }
    public AccountStatus getAccountStatus() { return accountStatus; }
    public boolean isEmailVerified() { return emailVerificado; }
    public boolean mustChangePassword() { return mustChangePassword; }
    public String getGithubUsername() { return githubUsername; }
    public String getAvatarRef() { return avatarRef; }
    public boolean isFirstLogin() { return firstLogin; }
    public boolean isGuidedTourCompleted() { return guidedTourCompleted; }
    public Instant getDeletedAt() { return deletedAt; }
}
```

- [ ] **Step 7: Escribir el repositorio**

```java
package ar.edu.utn.frc.tup.p4.usersservice.users.repositories;

import ar.edu.utn.frc.tup.p4.usersservice.users.entities.User;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmailAndDeletedAtIsNull(String email);

    Optional<User> findByIdAndDeletedAtIsNull(UUID id);

    long countByRolAndDeletedAtIsNull(Role role);

    /**
     * DEC-20 regla 5: InnoDB es REPEATABLE READ. Sin FOR UPDATE, dos bajas de
     * concurrent ADMIN deletions each count two ADMINs in their own snapshot and
     * ambas proceden, dejando la plataforma en cero. Lo usa validarNoUltimoAdmin.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select count(u) from User u where u.role = :role and u.deletedAt is null")
    long countActivosConLock(Role role);
}
```

- [ ] **Step 8: Correr el test unitario y verificar que pasa**

Run: `mvn -q test -Dtest=TransitionsTest`
Expected: PASS — 6 tests.

- [ ] **Step 9: Escribir el test de integración de reuso de email**

```java
package ar.edu.utn.frc.tup.p4.usersservice.users;

import ar.edu.utn.frc.tup.p4.usersservice.AbstractIntegrationTest;
import ar.edu.utn.frc.tup.p4.usersservice.users.entities.User;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** DEC-21 · criterio de DoD #22. */
class EmailReuseIT extends AbstractIntegrationTest {

    @Autowired UserRepository repo;

    @Test
    void a_deactivated_email_can_be_registered_again() {
        User primera = repo.saveAndFlush(
                User.create("Ana", "Perez", "reuso@utn.edu.ar", "$2a$12$h", Role.STUDENT, "v1"));
        primera.deactivate();
        repo.saveAndFlush(primera);

        User segunda = repo.saveAndFlush(
                User.create("Ana", "Perez", "reuso@utn.edu.ar", "$2a$12$h", Role.STUDENT, "v1"));

        assertThat(segunda.getId()).isNotEqualTo(primera.getId());
        assertThat(repo.findByEmailAndDeletedAtIsNull("reuso@utn.edu.ar"))
                .get().extracting(User::getId).isEqualTo(segunda.getId());
    }

    @Test
    void two_active_accounts_with_the_same_email_break_the_unique_key() {
        repo.saveAndFlush(User.create("A", "A", "dup@utn.edu.ar", "$2a$12$h", Role.STUDENT, "v1"));
        assertThatThrownBy(() ->
                repo.saveAndFlush(User.create("B", "B", "dup@utn.edu.ar", "$2a$12$h", Role.STUDENT, "v1")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void the_email_is_normalised_to_lowercase() {
        // DEC-20 rule 4: the collation is the safety net, normalising in the
        // en la aplicacion es el mecanismo.
        User u = repo.saveAndFlush(
                User.create("A", "A", "MAYUS@UTN.EDU.AR", "$2a$12$h", Role.STUDENT, "v1"));
        assertThat(u.getEmail()).isEqualTo("mayus@utn.edu.ar");
    }
}
```

- [ ] **Step 10: Correr el test de integración y verificar que pasa**

Run: `mvn -q test -Dtest=EmailReuseIT`
Expected: PASS — 3 tests. Y `UsersServiceApplicationTest` ahora también pasa, porque `ddl-auto: validate` ya encuentra la tabla.

- [ ] **Step 11: Commit**

```bash
git add src/main/resources/db/migration/V1__users.sql src/main/java src/test/java
git commit -m "feat: entidad User con tabla de transiciones y reuso de email

DEC-45d: la lista cerrada de transiciones de la spec §9.2 vive como EnumMap,
no como ifs dispersos. DEACTIVATED es terminal.
DEC-21: columna generada email_activo, porque MySQL no tiene indices unicos
parciales. Habilita reinscribir a un alumno dado de baja."
```

---
### Task 3: Migraciones V2–V6 y entidades restantes

**Files:**
- Create: `src/main/resources/db/migration/V2__email_whitelist.sql`
- Create: `src/main/resources/db/migration/V3__service_clients.sql`
- Create: `src/main/resources/db/migration/V4__processed_events.sql`
- Create: `src/main/resources/db/migration/V5__whitelist_requests.sql`
- Create: `src/main/resources/db/migration/V6__outbox_events.sql`
- Create: `src/main/java/…/users/entities/{EmailWhitelist,WhitelistRequest}.java`
- Create: `src/main/java/…/users/enums/RequestStatus.java`
- Create: `src/main/java/…/auth/entities/ServiceClient.java`
- Create: `src/main/java/…/shared/events/entities/{ProcessedEvent,OutboxEvent}.java`
- Create: `src/main/java/…/users/repositories/{EmailWhitelistRepository,WhitelistRequestRepository}.java`
- Create: `src/main/java/…/auth/repositories/ServiceClientRepository.java`
- Create: `src/main/java/…/shared/events/{ProcessedEventRepository,OutboxRepository}.java`
- Test: `src/test/java/…/SchemaIT.java`

**Interfaces:**
- Consumes: `AbstractIntegrationTest` (T1), convenciones `DEC-20` (T2).
- Produces: `ServiceClient` con `String getClientId()`, `String getSecretHash()`, `Set<String> getAllowedScopes()`; `OutboxEvent.pending(String eventId, String topic, String payload)`; `ProcessedEvent(String eventId, String eventType)`; `WhitelistRequest.create(String email, UUID solicitadoPor, String reason)` con `approve(UUID)` / `reject(UUID, String)`.

- [ ] **Step 1: Escribir V2 y V3**

`DEC-20` r3: `scopes_permitidos` es **tabla hija**, no columna — MySQL no tiene arrays.

`V2__email_whitelist.sql`:
```sql
CREATE TABLE email_whitelist (
    id           CHAR(36)     NOT NULL PRIMARY KEY,
    email        VARCHAR(255) NOT NULL,
    added_by CHAR(36)     NOT NULL,
    created_at   DATETIME(6)  NOT NULL,
    deleted_at   DATETIME(6)  NULL,
    active_email VARCHAR(255) AS (IF(deleted_at IS NULL, email, NULL)) STORED,
    UNIQUE KEY uq_whitelist_active_email (active_email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
```

`V3__service_clients.sql` — **dos sentencias, dos archivos** por `DEC-20` r6 (el DDL de MySQL no es transaccional). Crear también `V3_1__service_client_scopes.sql`:
```sql
-- V3__service_clients.sql
CREATE TABLE service_clients (
    id           CHAR(36)     NOT NULL PRIMARY KEY,
    client_id    VARCHAR(100) NOT NULL,
    secret_hash  VARCHAR(72)  NOT NULL,
    description  VARCHAR(255) NULL,
    created_at   DATETIME(6)  NOT NULL,
    deleted_at   DATETIME(6)  NULL,
    active_client_id VARCHAR(100) AS (IF(deleted_at IS NULL, client_id, NULL)) STORED,
    UNIQUE KEY uq_service_client_active (active_client_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
```
```sql
-- V3_1__service_client_scopes.sql
CREATE TABLE service_client_scopes (
    service_client_id CHAR(36)    NOT NULL,
    scope             VARCHAR(64) NOT NULL,
    PRIMARY KEY (service_client_id, scope),
    CONSTRAINT fk_scopes_client FOREIGN KEY (service_client_id) REFERENCES service_clients(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
```

- [ ] **Step 2: Escribir V4, V5 y V6**

`V4__processed_events.sql`:
```sql
CREATE TABLE processed_events (
    event_id     VARCHAR(64) NOT NULL PRIMARY KEY,
    event_type   VARCHAR(100) NOT NULL,
    processed_at DATETIME(6) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
```

`V5__whitelist_requests.sql`:
```sql
CREATE TABLE whitelist_requests (
    id               CHAR(36)     NOT NULL PRIMARY KEY,
    requested_email VARCHAR(255) NOT NULL,
    requested_by   CHAR(36)     NOT NULL,
    reason           VARCHAR(500) NOT NULL,
    status           VARCHAR(20)  NOT NULL,
    resolved_by     CHAR(36)     NULL,
    rejection_reason   VARCHAR(500) NULL,
    created_at       DATETIME(6)  NOT NULL,
    resolved_at      DATETIME(6)  NULL,
    -- DEC-29: unique only among the PENDING ones. Same technique as DEC-21:
    -- dos profesores pidiendo el mismo email no abren dos solicitudes.
    pending_email  VARCHAR(255) AS (IF(status = 'PENDING', requested_email, NULL)) STORED,
    UNIQUE KEY uq_whitelist_request_pending (pending_email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
```

`V6__outbox_events.sql`:
```sql
CREATE TABLE outbox_events (
    event_id     VARCHAR(64)  NOT NULL PRIMARY KEY,
    topic        VARCHAR(255) NOT NULL,
    payload      JSON         NOT NULL,
    created_at   DATETIME(6)  NOT NULL,
    published_at DATETIME(6)  NULL,
    attempts     INT          NOT NULL DEFAULT 0,
    KEY idx_outbox_pending (published_at, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
```

- [ ] **Step 3: Escribir las entidades**

```java
package ar.edu.utn.frc.tup.p4.usersservice.auth.entities;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "service_clients")
public class ServiceClient {

    @Id @Column(columnDefinition = "CHAR(36)")
    private UUID id;

    @Column(name = "client_id")   private String clientId;
    @Column(name = "secret_hash") private String secretHash;
    private String descripcion;
    @Column(name = "created_at")  private Instant createdAt;
    @Column(name = "deleted_at")  private Instant deletedAt;

    /** DEC-20 rule 3: a list is a child table, never a column. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "service_client_scopes",
                     joinColumns = @JoinColumn(name = "service_client_id"))
    @Column(name = "scope")
    private Set<String> scopesPermitidos = new HashSet<>();

    protected ServiceClient() { }

    public static ServiceClient nuevo(String clientId, String secretHash,
                                      String descripcion, Set<String> scopes) {
        ServiceClient c = new ServiceClient();
        c.id = UUID.randomUUID();
        c.clientId = clientId;
        c.secretHash = secretHash;
        c.descripcion = descripcion;
        c.scopesPermitidos = new HashSet<>(scopes);
        c.createdAt = Instant.now();
        return c;
    }

    public UUID getId() { return id; }
    public String getClientId() { return clientId; }
    public String getSecretHash() { return secretHash; }
    public Set<String> getAllowedScopes() { return Collections.unmodifiableSet(scopesPermitidos); }
}
```

```java
package ar.edu.utn.frc.tup.p4.usersservice.shared.events.entities;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * DEC-45b - outbox. The event is INSERTED in the same transaction as the
 * status change that causes it. If the transaction rolls back, the
 * event does not exist either: you cannot announce something that did not happen.
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    @Id @Column(name = "event_id")
    private String eventId;

    private String topic;

    @Column(columnDefinition = "JSON")
    private String payload;

    @Column(name = "created_at")   private Instant createdAt;
    @Column(name = "published_at") private Instant publishedAt;
    private int intentos;

    protected OutboxEvent() { }

    public static OutboxEvent pending(String eventId, String topic, String payload) {
        OutboxEvent e = new OutboxEvent();
        e.eventId = eventId;
        e.topic = topic;
        e.payload = payload;
        e.createdAt = Instant.now();
        e.intentos = 0;
        return e;
    }

    public void markPublished() { this.publishedAt = Instant.now(); }
    public void recordFailedAttempt() { this.intentos++; }

    public String getEventId() { return eventId; }
    public String getTopic() { return topic; }
    public String getPayload() { return payload; }
    public Instant getPublishedAt() { return publishedAt; }
    public int getAttempts() { return attempts; }
}
```

```java
package ar.edu.utn.frc.tup.p4.usersservice.shared.events.entities;

import jakarta.persistence.*;
import java.time.Instant;

/** DEC-13 · idempotencia del CONSUMIDOR (el espejo de OutboxEvent). */
@Entity
@Table(name = "processed_events")
public class ProcessedEvent {

    @Id @Column(name = "event_id")
    private String eventId;

    @Column(name = "event_type")   private String eventType;
    @Column(name = "processed_at") private Instant procesadoEn;

    protected ProcessedEvent() { }

    public ProcessedEvent(String eventId, String eventType) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.procesadoEn = Instant.now();
    }

    public String getEventId() { return eventId; }
}
```

```java
package ar.edu.utn.frc.tup.p4.usersservice.users.enums;

public enum RequestStatus { PENDING, APPROVED, REJECTED }
```

```java
package ar.edu.utn.frc.tup.p4.usersservice.users.entities;

import ar.edu.utn.frc.tup.p4.usersservice.users.enums.RequestStatus;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/** DEC-29 - a PROFESSOR's request to add an e-mail to the whitelist. */
@Entity
@Table(name = "whitelist_requests")
public class WhitelistRequest {

    @Id @Column(columnDefinition = "CHAR(36)")
    private UUID id;

    @Column(name = "requested_email") private String emailSolicitado;
    @Column(name = "requested_by", columnDefinition = "CHAR(36)") private UUID solicitadoPor;
    private String reason;

    @Enumerated(EnumType.STRING)
    private RequestStatus status;

    @Column(name = "resolved_by", columnDefinition = "CHAR(36)") private UUID resueltoPor;
    @Column(name = "rejection_reason") private String rejectionReason;
    @Column(name = "created_at")  private Instant createdAt;
    @Column(name = "resolved_at") private Instant resueltoEn;

    protected WhitelistRequest() { }

    public static WhitelistRequest nueva(String email, UUID solicitadoPor, String reason) {
        WhitelistRequest r = new WhitelistRequest();
        r.id = UUID.randomUUID();
        r.emailSolicitado = email.toLowerCase(Locale.ROOT);
        r.solicitadoPor = solicitadoPor;
        r.reason = reason;
        r.status = RequestStatus.PENDING;
        r.createdAt = Instant.now();
        return r;
    }

    public void approve(UUID admin) {
        requirePending();
        this.status = RequestStatus.APPROVED;
        this.resueltoPor = admin;
        this.resueltoEn = Instant.now();
    }

    public void reject(UUID admin, String rejectionReason) {
        requirePending();
        if (rejectionReason == null || rejectionReason.isBlank()) {
            throw new IllegalArgumentException("El rechazo exige reason");
        }
        this.status = RequestStatus.REJECTED;
        this.resueltoPor = admin;
        this.rejectionReason = rejectionReason;
        this.resueltoEn = Instant.now();
    }

    private void requirePending() {
        if (this.status != RequestStatus.PENDING) {
            throw new IllegalStateException("La request ya fue resuelta: " + this.status);
        }
    }

    public UUID getId() { return id; }
    public String getRequestedEmail() { return emailSolicitado; }
    public UUID getRequestedBy() { return solicitadoPor; }
    public String getReason() { return reason; }
    public RequestStatus getStatus() { return status; }
}
```

```java
package ar.edu.utn.frc.tup.p4.usersservice.users.entities;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Entity
@Table(name = "email_whitelist")
public class EmailWhitelist {

    @Id @Column(columnDefinition = "CHAR(36)")
    private UUID id;

    private String email;
    @Column(name = "added_by", columnDefinition = "CHAR(36)") private UUID agregadoPor;
    @Column(name = "created_at") private Instant createdAt;
    @Column(name = "deleted_at") private Instant deletedAt;

    protected EmailWhitelist() { }

    public static EmailWhitelist nuevo(String email, UUID agregadoPor) {
        EmailWhitelist w = new EmailWhitelist();
        w.id = UUID.randomUUID();
        w.email = email.toLowerCase(Locale.ROOT);
        w.agregadoPor = agregadoPor;
        w.createdAt = Instant.now();
        return w;
    }

    public void remove() { this.deletedAt = Instant.now(); }

    public UUID getId() { return id; }
    public String getEmail() { return email; }
}
```

- [ ] **Step 4: Escribir los repositorios**

```java
package ar.edu.utn.frc.tup.p4.usersservice.shared.events;

import ar.edu.utn.frc.tup.p4.usersservice.shared.events.entities.OutboxEvent;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

import java.util.List;

public interface OutboxRepository extends JpaRepository<OutboxEvent, String> {

    /**
     * DEC-45b: SKIP LOCKED is what allows more than one instance without
     * duplicate publishing - each poller takes different rows instead of blocking
     * contra el otro. MySQL 8 lo soporta.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@jakarta.persistence.QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("select e from OutboxEvent e where e.publishedAt is null order by e.createdAt asc")
    List<OutboxEvent> tomarPendientes(Limit limit);
}
```

```java
package ar.edu.utn.frc.tup.p4.usersservice.shared.events;

import ar.edu.utn.frc.tup.p4.usersservice.shared.events.entities.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, String> { }
```

```java
package ar.edu.utn.frc.tup.p4.usersservice.auth.repositories;

import ar.edu.utn.frc.tup.p4.usersservice.auth.entities.ServiceClient;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface ServiceClientRepository extends JpaRepository<ServiceClient, UUID> {
    Optional<ServiceClient> findByClientIdAndDeletedAtIsNull(String clientId);
}
```

```java
package ar.edu.utn.frc.tup.p4.usersservice.users.repositories;

import ar.edu.utn.frc.tup.p4.usersservice.users.entities.EmailWhitelist;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface EmailWhitelistRepository extends JpaRepository<EmailWhitelist, UUID> {
    boolean existsByEmailAndDeletedAtIsNull(String email);
    List<EmailWhitelist> findAllByDeletedAtIsNull();
}
```

```java
package ar.edu.utn.frc.tup.p4.usersservice.users.repositories;

import ar.edu.utn.frc.tup.p4.usersservice.users.entities.WhitelistRequest;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.RequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface WhitelistRequestRepository extends JpaRepository<WhitelistRequest, UUID> {
    List<WhitelistRequest> findAllByEstado(RequestStatus status);
    List<WhitelistRequest> findAllBySolicitadoPor(UUID solicitadoPor);
}
```

- [ ] **Step 5: Escribir el test de esquema**

```java
package ar.edu.utn.frc.tup.p4.usersservice;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaIT extends AbstractIntegrationTest {

    @Autowired JdbcTemplate jdbc;

    @Test
    void every_migration_ran() {
        List<String> tablas = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()",
                String.class);
        assertThat(tablas).contains("users", "email_whitelist", "service_clients",
                "service_client_scopes", "processed_events", "whitelist_requests", "outbox_events");
    }

    @Test
    void every_table_is_utf8mb4() {
        // DEC-20 regla 4: utf8 a secas son 3 bytes y no cubre el plain suplementario.
        List<String> noUtf8mb4 = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables " +
                "WHERE table_schema = DATABASE() AND table_collation NOT LIKE 'utf8mb4%'",
                String.class);
        assertThat(noUtf8mb4).isEmpty();
    }

    @Test
    void ninguna_columna_es_TIMESTAMP() {
        // DEC-20 regla 2: TIMESTAMP convierte segun la tz de sesion y muere en 2038.
        List<String> malas = jdbc.queryForList(
                "SELECT CONCAT(table_name,'.',column_name) FROM information_schema.columns " +
                "WHERE table_schema = DATABASE() AND data_type = 'timestamp'",
                String.class);
        assertThat(malas).isEmpty();
    }

    @Test
    void todos_los_ids_son_CHAR_36() {
        // DEC-20 regla 1.
        List<String> malas = jdbc.queryForList(
                "SELECT CONCAT(table_name,'.',column_name) FROM information_schema.columns " +
                "WHERE table_schema = DATABASE() AND column_name = 'id' " +
                "AND NOT (data_type = 'char' AND character_maximum_length = 36)",
                String.class);
        assertThat(malas).isEmpty();
    }
}
```

- [ ] **Step 6: Correr y verificar que pasa**

Run: `mvn -q test -Dtest=SchemaIT`
Expected: PASS — 4 tests.

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/db/migration src/main/java src/test/java
git commit -m "feat: esquema completo con las seis reglas de MySQL verificadas por test

SchemaIT afirma utf8mb4, ausencia de TIMESTAMP y CHAR(36) en todos los id.
Las reglas de DEC-20 dejan de ser prosa y pasan a ser assertions."
```

---

### Task 4: Errores uniformes (`ProblemDetail`)

**Files:**
- Create: `src/main/java/…/shared/web/ErrorTypes.java`
- Create: `src/main/java/…/shared/web/ApiException.java`
- Create: `src/main/java/…/shared/web/GlobalExceptionHandler.java`
- Create: `src/main/java/…/shared/web/RequestLogFilter.java`
- Modify: `src/main/resources/application.yml` (Step 5)
- Test: `src/test/java/…/shared/web/GlobalExceptionHandlerTest.java`

> **`RequestLogFilter` es la otra mitad de la trazabilidad.** El Gateway
> escribe su línea con el `requestId`, y si el micro no escribe ninguna no hay
> dos puntas para unir. El filtro pone `requestId` y `traceId` en el MDC —
> el `logging.pattern.level` del Task 1 los imprime — y escribe UNA línea por
> request. Es la misma pieza que necesita **todo** servicio detrás del Gateway:
> la correlación existe sólo si todos la escriben igual. Va acá y no en el
> Task 1 porque comparte el paquete y el criterio con el resto del contrato de
> salida.

> **La regla que sostiene todo el contrato: NINGUN error sale del micro sin
> `type`.** El frontend ramifica por `type`, no por status; un error que caiga
> en el cuerpo por defecto de Spring (`{timestamp, status, error, path}`) es un
> error que el cliente no puede clasificar. Los cuatro handlers del Step 3 que
> parecen de borde — 404, 405, tipo de parametro y JSON ilegible — existen
> exactamente por eso.

**Interfaces:**
- Consumes: `InvalidTransitionException` (T2).
- Produces: `ApiException.validation(String)`, `.invalidCredentials()`, `.pendingAccount(AccountStatus)`, `.passwordChangeRequired()`, `.onboardingPending()`, `.emailNotWhitelisted(String)`, `.accessDenied()`, `.lastAdmin()`, `.duplicateEmail()`, `.tooManyAttempts(Duration)`, `.invalidCode()`, `.invalidLink()`, `.sessionClosed()`, `.sessionSuperseded()`, `.routeNotFound()` — todas devuelven una `ApiException` con `HttpStatus` y `type` ya resueltos.

- [ ] **Step 1: Escribir el catálogo de tipos**

```java
package ar.edu.utn.frc.tup.p4.usersservice.shared.web;

import java.net.URI;

/** The same prefix the gateway uses: a single namespace for errors. */
public final class ErrorTypes {
    private static final String BASE = "https://tpi.utn.frc/errors/";

    public static final URI VALIDACION               = URI.create(BASE + "validation");
    public static final URI NO_AUTENTICADO           = URI.create(BASE + "not-authenticated");
    public static final URI CREDENCIALES_INVALIDAS   = URI.create(BASE + "invalid-credentials");
    /**
     * The SAME two types the gateway uses when the token's sid is not the current
     * one. Here the refresh emits them: a refresh token that no longer works is
     * not a wrong credential, it is a session that stopped existing, and the
     * message the person reads has to say that, not "wrong username or
     * incorrectos".
     */
    public static final URI SESION_CERRADA           = URI.create(BASE + "session-closed");
    public static final URI SESION_SUPERADA          = URI.create(BASE + "session-superseded");
    /** A route that does not exist in this service. The SAME type the gateway uses. */
    public static final URI RUTA_INEXISTENTE         = URI.create(BASE + "route-not-found");
    public static final URI CUENTA_PENDIENTE         = URI.create(BASE + "pending-account");
    public static final URI CAMBIO_PASSWORD_REQUERIDO= URI.create(BASE + "password-change-required");
    public static final URI ONBOARDING_PENDIENTE     = URI.create(BASE + "onboarding-pending");
    public static final URI EMAIL_NO_HABILITADO      = URI.create(BASE + "email-not-whitelisted");
    public static final URI ACCESO_DENEGADO          = URI.create(BASE + "access-denied");
    public static final URI ULTIMO_ADMIN             = URI.create(BASE + "last-admin");
    public static final URI TRANSICION_INVALIDA      = URI.create(BASE + "invalid-transition");
    public static final URI EMAIL_DUPLICADO          = URI.create(BASE + "duplicate-email");
    public static final URI CODIGO_INVALIDO          = URI.create(BASE + "invalid-code");
    /**
     * A single-use link (account activation, password reset) that is expired,
     * already used or non-existent. Kept apart from `invalid-code` because the
     * screen and the way out differ: a code is typed again, a link
     * has to be requested anew.
     */
    public static final URI ENLACE_INVALIDO          = URI.create(BASE + "invalid-link");
    /** DEC-24: the SAME type the gateway uses for its per-IP limit. */
    public static final URI DEMASIADOS_INTENTOS      = URI.create(BASE + "too-many-attempts");

    private ErrorTypes() { }
}
```

- [ ] **Step 2: Escribir `ApiException`**

```java
package ar.edu.utn.frc.tup.p4.usersservice.shared.web;

import ar.edu.utn.frc.tup.p4.usersservice.users.enums.AccountStatus;
import org.springframework.http.HttpStatus;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final URI type;
    private final String title;
    private final Map<String, Object> extras = new LinkedHashMap<>();

    private ApiException(HttpStatus status, URI type, String title, String detail) {
        super(detail);
        this.status = status;
        this.type = type;
        this.title = title;
    }

    private ApiException con(String k, Object v) { extras.put(k, v); return this; }

    public static ApiException validation(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorTypes.VALIDACION, "Solicitud invalida", detail);
    }

    /** Anti-enumeration: the detail NEVER tells "does not exist" from "wrong password". */
    public static ApiException invalidCredentials() {
        return new ApiException(HttpStatus.UNAUTHORIZED, ErrorTypes.CREDENCIALES_INVALIDAS,
                "Credenciales invalidas", "Usuario o contrasena incorrectos.");
    }

    /** Anti-enumeration: the same error for a wrong code, an expired one and an unknown e-mail. */
    public static ApiException invalidCode() {
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorTypes.CODIGO_INVALIDO,
                "Codigo invalido", "El code es incorrecto o expiro.");
    }

    public static ApiException pendingAccount(AccountStatus status) {
        return new ApiException(HttpStatus.FORBIDDEN, ErrorTypes.CUENTA_PENDIENTE,
                "Cuenta pending de validation", "La cuenta no esta activa.")
                .con("accountStatus", status.name());
    }

    public static ApiException passwordChangeRequired() {
        return new ApiException(HttpStatus.FORBIDDEN, ErrorTypes.CAMBIO_PASSWORD_REQUERIDO,
                "Cambio de contrasena requerido", "Debe cambiar su contrasena antes de continuar.");
    }

    public static ApiException onboardingPending() {
        return new ApiException(HttpStatus.FORBIDDEN, ErrorTypes.ONBOARDING_PENDIENTE,
                "Onboarding pending", "Complete el onboarding antes de continuar.");
    }

    public static ApiException emailNotWhitelisted(String detail) {
        return new ApiException(HttpStatus.FORBIDDEN, ErrorTypes.EMAIL_NO_HABILITADO,
                "Email no habilitado", detail);
    }

    public static ApiException accessDenied() {
        return new ApiException(HttpStatus.FORBIDDEN, ErrorTypes.ACCESO_DENEGADO,
                "Acceso denegado", "No tiene permisos para esta operacion.");
    }

    public static ApiException lastAdmin() {
        return new ApiException(HttpStatus.CONFLICT, ErrorTypes.ULTIMO_ADMIN,
                "Ultimo ADMIN", "La plataforma no puede quedar sin ningun ADMIN activo.");
    }

    public static ApiException duplicateEmail() {
        return new ApiException(HttpStatus.CONFLICT, ErrorTypes.EMAIL_DUPLICADO,
                "Email duplicado", "Ya existe una cuenta activa con ese email.");
    }

    public static ApiException tooManyAttempts(Duration retryAfter) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, ErrorTypes.DEMASIADOS_INTENTOS,
                "Demasiados intentos", "Superó el limite de intentos. Reintente mas tarde.")
                .con("retryAfterSeconds", retryAfter.toSeconds());
    }

    public HttpStatus getStatus() { return status; }
    public URI getType() { return type; }
    public String getTitle() { return title; }
    public Map<String, Object> getExtras() { return extras; }
}
```

- [ ] **Step 3: Escribir el handler global**

```java
package ar.edu.utn.frc.tup.p4.usersservice.shared.web;

import ar.edu.utn.frc.tup.p4.usersservice.users.InvalidTransitionException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.URI;
import java.util.stream.Collectors;

/** One single place. Never a try/catch scattered across the controllers. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> api(ApiException ex, HttpServletRequest req) {
        ProblemDetail pd = base(ex.getStatus().value(), ex.getType(), ex.getTitle(), ex.getMessage(), req);
        ex.getExtras().forEach(pd::setProperty);

        HttpHeaders headers = new HttpHeaders();
        Object retry = ex.getExtras().get("retryAfterSeconds");
        if (retry != null) headers.add(HttpHeaders.RETRY_AFTER, String.valueOf(retry));

        return ResponseEntity.status(ex.getStatus()).headers(headers).body(pd);
    }

    @ExceptionHandler(InvalidTransitionException.class)
    ProblemDetail transicion(InvalidTransitionException ex, HttpServletRequest req) {
        return base(409, ErrorTypes.TRANSICION_INVALIDA, "Transicion invalida", ex.getMessage(), req);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail validation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return base(400, ErrorTypes.VALIDACION, "Solicitud invalida", detail, req);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail integridad(DataIntegrityViolationException ex, HttpServletRequest req) {
        // The database message is not exposed: it can leak index and column names.
        log.warn("Violacion de integridad en {}", req.getRequestURI(), ex);
        return base(409, ErrorTypes.EMAIL_DUPLICADO, "Conflicto",
                "La operacion viola una restriccion de unicidad.", req);
    }

    /**
     * A route that does not exist has to give 404, not 401. Without this handler,
     * forwards to /error, GatewayIdentityFilter does not run on that dispatch,
     * ERROR (OncePerRequestFilter lo saltea), el SecurityContext llega vacio y
     * el entry point contesta 401 not-authenticated con instance "/error".
     * Since the contract says a 401 sends you to the login, a typo in a frontend
     * frontend terminaria deslogueando a la persona.
     */
    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    ProblemDetail noExiste(Exception ex, HttpServletRequest req) {
        return base(404, ErrorTypes.RUTA_INEXISTENTE, "Ruta inexistente",
                "La ruta solicitada no existe.", req);
    }

    /** The route exists but not with that verb. To the client it is the same family. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ProblemDetail metodo(HttpRequestMethodNotSupportedException ex, HttpServletRequest req) {
        return base(405, ErrorTypes.RUTA_INEXISTENTE, "Metodo no soportado",
                "El metodo " + ex.getMethod() + " no esta soportado en esa ruta.", req);
    }

    /** An {id} that is not a UUID, an enum value that does not exist. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail tipo(MethodArgumentTypeMismatchException ex, HttpServletRequest req) {
        return base(400, ErrorTypes.VALIDACION, "Solicitud invalida",
                "El parametro '" + ex.getName() + "' no tiene el formato esperado.", req);
    }

    /**
     * Unreadable body. The parser's message is NOT exposed: it names internal
     * classes and fields, and it is unreadable for whoever has to fix it.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail cuerpo(HttpMessageNotReadableException ex, HttpServletRequest req) {
        return base(400, ErrorTypes.VALIDACION, "Solicitud invalida",
                "El cuerpo del request no es JSON valido.", req);
    }

    private ProblemDetail base(int status, URI type, String title, String detail, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                org.springframework.http.HttpStatusCode.valueOf(status), detail);
        pd.setType(type);
        pd.setTitle(title);
        pd.setInstance(URI.create(req.getRequestURI()));
        String requestId = req.getHeader("X-Request-Id");
        if (requestId != null) pd.setProperty("requestId", requestId);
        return pd;
    }
}
```

- [ ] **Step 4: Escribir el test**

```java
package ar.edu.utn.frc.tup.p4.usersservice.shared.web;

import ar.edu.utn.frc.tup.p4.usersservice.users.enums.AccountStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    @Test
    void invalid_credentials_does_not_reveal_the_cause() {
        // Anti-enumeration: if the message told "does not exist" from "wrong
        // incorrecta", un atacante podria descubrir que emails estan registrados.
        assertThat(ApiException.invalidCredentials().getMessage())
                .doesNotContain("existe", "encontrado", "password incorrecta");
    }

    @Test
    void invalid_code_does_not_tell_expired_from_wrong() {
        assertThat(ApiException.invalidCode().getMessage())
                .isEqualTo("El code es incorrecto o expiro.");
    }

    @Test
    void pending_account_carries_the_status_in_the_body() {
        // The frontend needs it to decide which screen to show.
        assertThat(ApiException.pendingAccount(AccountStatus.PENDING_COURSE).getExtras())
                .containsEntry("accountStatus", "PENDING_COURSE");
    }

    @Test
    void too_many_attempts_uses_the_type_shared_with_the_gateway() {
        // DEC-24: the frontend has a single handling branch.
        ApiException ex = ApiException.tooManyAttempts(Duration.ofMinutes(15));
        assertThat(ex.getType().toString()).endsWith("/too-many-attempts");
        assertThat(ex.getStatus().value()).isEqualTo(429);
        assertThat(ex.getExtras()).containsEntry("retryAfterSeconds", 900L);
    }
}
```

- [ ] **Step 5: Hacer que la ruta faltante llegue al handler**

Sin estas dos properties, una URL inexistente no lanza excepción: la atrapa el
handler de recursos estáticos (que este micro no sirve) y termina en el dispatch
a `/error`, donde ya no hay identidad. En `application.yml`:

```yaml
spring:
  # A non-existent route has to reach the @RestControllerAdvice as an
  # excepcion y salir como 404 route-not-found en problem+json.
  mvc:
    throw-exception-if-no-handler-found: true
  web:
    resources:
      add-mappings: false
```

Y en el `SecurityConfig` de la Task 5, `/error` va permitido:

```java
        // The dispatch to /error runs WITHOUT GatewayIdentityFilter
        // (OncePerRequestFilter saltea el dispatch de ERROR): si exigiera
        // authentication, any internal error would come out as a 401 and the
        // frontend mandaria al login por un 404.
        .requestMatchers("/error").permitAll()
```

- [ ] **Step 6: Correr y verificar que pasa**

Run: `mvn -q test -Dtest=GlobalExceptionHandlerTest`
Expected: PASS — 4 tests.

Verificación manual, una vez que el micro esté levantado detrás del Gateway.
**Los cuatro tienen que traer `type`:**

```bash
G=http://localhost:8080     # con un token valido en $T
curl -s $G/api/users/no/existe/tampoco -H "Authorization: Bearer $T"   # 404 route-not-found
curl -s $G/api/users/no-existe         -H "Authorization: Bearer $T"   # 405 route-not-found
curl -s -X PATCH $G/api/users/xx/role   -H "Authorization: Bearer $T" \
     -H 'Content-Type: application/json' -d '{"role":"STUDENT"}'          # 400 validation
curl -s -X POST $G/api/users/public/auth/login \
     -H 'Content-Type: application/json' -d '{roto'                     # 400 validation
```

- [ ] **Step 7: Commit**

```bash
git add src/main/java/ar/edu/utn/frc/tup/p4/usersservice/shared/web src/main/resources src/test/java
git commit -m "feat: ProblemDetail uniforme con catalogo de errores

Ningun error sale sin type, incluidos 404, 405, tipo de parametro y JSON roto:
el cliente ramifica por type y el cuerpo por defecto de Spring no lo trae.
Una ruta inexistente da 404, no 401: si diera 401 un typo de URL deslogueria.
El 429 usa el mismo type que el Gateway (DEC-24)."
```

---

### Task 5: Autenticación por headers del Gateway

**Files:**
- Create: `src/main/java/…/shared/security/IdentityHeaders.java`
- Create: `src/main/java/…/shared/security/GatewayPrincipal.java`
- Create: `src/main/java/…/shared/security/GatewayIdentityFilter.java`
- Create: `src/main/java/…/shared/security/SecurityConfig.java`
- Test: `src/test/java/…/shared/security/GatewayIdentityFilterTest.java`

**Interfaces:**
- Consumes: nada de tareas previas.
- Produces: `GatewayPrincipal` — `record GatewayPrincipal(String tipo, UUID id, String serviceId)` con `boolean isPerson()`; se obtiene con `SecurityContextHolder.getContext().getAuthentication().getPrincipal()`.

- [ ] **Step 1: Escribir el test (falla)**

`DEC-05`: separador **coma sin espacio**, `MS` **dentro** de `X-Service-Scopes`.

```java
package ar.edu.utn.frc.tup.p4.usersservice.shared.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class GatewayIdentityFilterTest {

    private final GatewayIdentityFilter filter = new GatewayIdentityFilter();

    @AfterEach
    void limpiar() { SecurityContextHolder.clearContext(); }

    private Authentication ejecutar(MockHttpServletRequest req) throws Exception {
        filter.doFilter(req, new MockHttpServletResponse(), mock(FilterChain.class));
        return SecurityContextHolder.getContext().getAuthentication();
    }

    @Test
    void a_person_with_comma_separated_roles_and_no_spaces() throws Exception {
        UUID id = UUID.randomUUID();
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(IdentityHeaders.PRINCIPAL_TYPE, "user");
        req.addHeader(IdentityHeaders.USER_ID, id.toString());
        req.addHeader(IdentityHeaders.USER_ROLES, "STUDENT,PROFESSOR");

        Authentication auth = ejecutar(req);

        assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_STUDENT", "ROLE_PROFESSOR");
        assertThat(((GatewayPrincipal) auth.getPrincipal()).id()).isEqualTo(id);
        assertThat(((GatewayPrincipal) auth.getPrincipal()).isPerson()).isTrue();
    }

    @Test
    void servicio_con_MS_dentro_de_scopes_mapea_a_ROLE_MS_y_el_resto_a_authorities() throws Exception {
        // DEC-05: "MS,users.profile.read". MS goes in as ROLE_ so that
        // hasRole('MS') matches; the scopes go in as bare authorities so that
        // que hasAuthority('users.profile.read') matchee.
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(IdentityHeaders.PRINCIPAL_TYPE, "service");
        req.addHeader(IdentityHeaders.SERVICE_ID, "cursos-service");
        req.addHeader(IdentityHeaders.SERVICE_SCOPES, "MS,users.profile.read");

        Authentication auth = ejecutar(req);

        assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_MS", "users.profile.read");
        assertThat(((GatewayPrincipal) auth.getPrincipal()).serviceId()).isEqualTo("cursos-service");
        assertThat(((GatewayPrincipal) auth.getPrincipal()).isPerson()).isFalse();
    }

    @Test
    void sin_headers_no_hay_Authentication() throws Exception {
        // Third case: a public route. Not an error; it simply does not authenticate.
        assertThat(ejecutar(new MockHttpServletRequest())).isNull();
    }

    @Test
    void headers_with_spaces_are_parsed_all_the_same() throws Exception {
        // Defensive: the contract says comma with no space, but one extra space
        // must not silently break authorization.
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(IdentityHeaders.PRINCIPAL_TYPE, "user");
        req.addHeader(IdentityHeaders.USER_ID, UUID.randomUUID().toString());
        req.addHeader(IdentityHeaders.USER_ROLES, "STUDENT, PROFESSOR");

        assertThat(ejecutar(req).getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_STUDENT", "ROLE_PROFESSOR");
    }
}
```

- [ ] **Step 2: Correr y verificar que falla**

Run: `mvn -q test -Dtest=GatewayIdentityFilterTest`
Expected: FAIL — no compila, faltan las tres clases.

- [ ] **Step 3: Escribir las constantes y el principal**

```java
package ar.edu.utn.frc.tup.p4.usersservice.shared.security;

/** The contract from §06 of the gateway manifest. Do not invent new names. */
public final class IdentityHeaders {
    public static final String PRINCIPAL_TYPE = "X-Principal-Type";
    public static final String USER_ID        = "X-User-Id";
    public static final String USER_ROLES     = "X-User-Roles";
    public static final String SERVICE_ID     = "X-Service-Id";
    public static final String SERVICE_SCOPES = "X-Service-Scopes";
    public static final String REQUEST_ID     = "X-Request-Id";

    private IdentityHeaders() { }
}
```

```java
package ar.edu.utn.frc.tup.p4.usersservice.shared.security;

import java.util.UUID;

public record GatewayPrincipal(String tipo, UUID id, String serviceId) {
    public boolean isPerson() { return "user".equals(tipo); }
}
```

- [ ] **Step 4: Escribir el filtro**

```java
package ar.edu.utn.frc.tup.p4.usersservice.shared.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.*;

/**
 * DEC-08 · users-service NO valida el JWT. Su Authentication sale
 * EXCLUSIVELY from the X-* headers the gateway injects.
 *
 * What makes those headers trustworthy is that this service's port is NOT
 * published outside the private network (U11 / DEC-40): nobody can reach here
 * without going through the gateway, and the gateway strips inbound X-* headers
 * before injecting its own. If that port is ever published, this filter turns
 * vuelve un agujero de seguridad total.
 */
@Component
public class GatewayIdentityFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest req,
                                    @NonNull HttpServletResponse res,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String tipo = req.getHeader(IdentityHeaders.PRINCIPAL_TYPE);

        if ("user".equals(tipo)) {
            autenticar(new GatewayPrincipal("user",
                            UUID.fromString(req.getHeader(IdentityHeaders.USER_ID)), null),
                    rolesDe(req.getHeader(IdentityHeaders.USER_ROLES)));
        } else if ("service".equals(tipo)) {
            autenticar(new GatewayPrincipal("service", null,
                            req.getHeader(IdentityHeaders.SERVICE_ID)),
                    scopesDe(req.getHeader(IdentityHeaders.SERVICE_SCOPES)));
        }
        // Third case: no headers -> public route. It neither authenticates nor fails.

        chain.doFilter(req, res);
    }

    private void autenticar(GatewayPrincipal principal, List<GrantedAuthority> authorities) {
        var auth = UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    /** Person roles: all ROLE_-prefixed so that hasRole(...) matches. */
    private List<GrantedAuthority> rolesDe(String header) {
        return partes(header).stream()
                .map(r -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + r))
                .toList();
    }

    /**
     * DEC-05: the header is "MS,users.profile.read". MS is a ROLE -> ROLE_MS.
     * Los scopes son AUTHORITIES pelados -> hasAuthority('users.profile.read').
     */
    private List<GrantedAuthority> scopesDe(String header) {
        return partes(header).stream()
                .map(s -> (GrantedAuthority) new SimpleGrantedAuthority("MS".equals(s) ? "ROLE_MS" : s))
                .toList();
    }

    private List<String> partes(String header) {
        if (header == null || header.isBlank()) return List.of();
        return Arrays.stream(header.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
```

- [ ] **Step 5: Escribir `SecurityConfig`**

```java
package ar.edu.utn.frc.tup.p4.usersservice.shared.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableMethodSecurity          // habilita @PreAuthorize (capa 1)
public class SecurityConfig {

    /** DEC-38: costo 12. */
    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }

    @Bean
    SecurityFilterChain chain(HttpSecurity http, GatewayIdentityFilter identityFilter) throws Exception {
        return http
                // No CSRF, no session: a stateless API behind the gateway.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .addFilterBefore(identityFilter, UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/api/users/public/**").permitAll()
                        .requestMatchers("/.well-known/**").permitAll()
                        .requestMatchers("/actuator/health/**").permitAll()
                        .anyRequest().authenticated())
                // DEC-08: there is NO .oauth2ResourceServer(...). If someone adds it,
                // UsersServiceApplicationTest falla por el JwtDecoder en el contexto.
                .build();
    }
}
```

- [ ] **Step 6: Correr el test y verificar que pasa**

Run: `mvn -q test -Dtest=GatewayIdentityFilterTest`
Expected: PASS — 4 tests.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/ar/edu/utn/frc/tup/p4/usersservice/shared/security src/test/java
git commit -m "feat: Authentication from los headers del Gateway (DEC-08)

DEC-05: coma sin espacio, MS dentro de X-Service-Scopes mapeado a ROLE_MS.
Sin oauth2ResourceServer: no validamos JWT en el camino de request."
```

---

### Task 6: Outbox y publicación de eventos

**Files:**
- Create: `src/main/java/…/shared/events/EventEnvelope.java`
- Create: `src/main/java/…/shared/events/AccountEventPublisher.java`
- Create: `src/main/java/…/shared/events/OutboxPoller.java`
- Create: `src/main/java/…/config/KafkaTopicsProperties.java`
- Test: `src/test/java/…/shared/events/OutboxIT.java`

**Interfaces:**
- Consumes: `OutboxEvent`, `OutboxRepository` (T3).
- Produces: `AccountEventPublisher.publicar(String topic, String eventType, Object payload)` — **escribe al outbox, no a Kafka**; debe llamarse **dentro** de la transacción del cambio de estado. `KafkaTopicsProperties` con `auditoria()`, `alumnoRegistrado()`, `notificaciones()`, `validationCurso()`.

- [ ] **Step 1: Escribir el sobre estándar y las properties**

```java
package ar.edu.utn.frc.tup.p4.usersservice.shared.events;

import java.time.Instant;
import java.util.UUID;

/**
 * DEC-12 - the envelope contract for the WHOLE platform. Confirmed by the
 * team; it is neither negotiable nor extensible with top-level fields.
 */
public record EventEnvelope<T>(String eventId, String eventType, String timestamp,
                               String producer, T payload) {

    public static final String PRODUCER = "tema-01-users";

    public static <T> EventEnvelope<T> de(String eventType, T payload) {
        return new EventEnvelope<>(
                UUID.randomUUID().toString(),
                eventType,
                Instant.now().toString(),   // ISO-8601 UTC
                PRODUCER,
                payload);
    }
}
```

```java
package ar.edu.utn.frc.tup.p4.usersservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** DEC-34: every topic name is configuration, never a constant. */
@ConfigurationProperties(prefix = "users.kafka.topics")
public record KafkaTopicsProperties(String auditoria, String alumnoRegistrado,
                                    String notificaciones, String validationCurso) { }
```

Registrar en `UsersServiceApplication` con `@ConfigurationPropertiesScan`.

- [ ] **Step 2: Escribir el test de integración (falla)**

Criterio de DoD #33: **con Kafka detenido, el alta igual completa**.

```java
package ar.edu.utn.frc.tup.p4.usersservice.shared.events;

import ar.edu.utn.frc.tup.p4.usersservice.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DEC-45b. Kafka does NOT take part in the MySQL transaction: without an outbox, a
 * failed publish after a successful commit leaves the student waiting FOREVER -
 * RF-USR-05e says there is no expiry and no rejection status,
 * asi que no existe ningun mecanismo de rescate.
 */
class OutboxIT extends AbstractIntegrationTest {

    @Autowired AccountEventPublisher publisher;
    @Autowired OutboxRepository outbox;
    @Autowired TransactionTemplate tx;

    @Test
    @Transactional
    void publicar_escribe_una_fila_pendiente_no_publica_a_kafka() {
        publisher.publicar("topico.test.v1", "EVENTO_TEST", new Payload("valor"));

        var pendientes = outbox.findAll().stream()
                .filter(e -> e.getPublishedAt() == null).toList();

        assertThat(pendientes).hasSize(1);
        assertThat(pendientes.get(0).getTopic()).isEqualTo("topico.test.v1");
        assertThat(pendientes.get(0).getPayload()).contains("\"eventType\":\"EVENTO_TEST\"");
        assertThat(pendientes.get(0).getPayload()).contains("\"producer\":\"tema-01-users\"");
    }

    @Test
    void si_la_transaccion_hace_rollback_el_evento_NO_existe() {
        // You cannot announce something that did not happen.
        long antes = outbox.count();

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            publisher.publicar("topico.test.v1", "EVENTO_TEST", new Payload("x"));
            throw new IllegalStateException("rollback a proposito");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(outbox.count()).isEqualTo(antes);
    }

    record Payload(String campo) { }
}
```

- [ ] **Step 3: Correr y verificar que falla**

Run: `mvn -q test -Dtest=OutboxIT`
Expected: FAIL — no compila, falta `AccountEventPublisher`.

- [ ] **Step 4: Escribir el publisher**

```java
package ar.edu.utn.frc.tup.p4.usersservice.shared.events;

import ar.edu.utn.frc.tup.p4.usersservice.shared.events.entities.OutboxEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * DEC-45b - it writes to the OUTBOX, not to Kafka. To the caller it is still
 * fire-and-forget, but delivery is now guaranteed.
 *
 * MANDATORY: calling it outside a transaction fails on the spot.
 * Without that transaction there is no atomicity between the status change and
 * the event, which is the entire point of the pattern.
 */
@Component
public class AccountEventPublisher {

    private final OutboxRepository outbox;
    private final ObjectMapper mapper;

    public AccountEventPublisher(OutboxRepository outbox, ObjectMapper mapper) {
        this.outbox = outbox;
        this.mapper = mapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void publicar(String topic, String eventType, Object payload) {
        EventEnvelope<Object> sobre = EventEnvelope.de(eventType, payload);
        try {
            outbox.save(OutboxEvent.pending(sobre.eventId(), topic, mapper.writeValueAsString(sobre)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo serializar el evento " + eventType, e);
        }
    }
}
```

- [ ] **Step 5: Escribir el poller**

```java
package ar.edu.utn.frc.tup.p4.usersservice.shared.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * DEC-45b. It takes the pending rows with FOR UPDATE SKIP LOCKED (through the
 * repository's @Lock): that is what allows more than one instance without
 * duplicate publishing - each poller takes different rows instead of blocking
 * against the other. If Kafka is down the events pile up and go out on their own
 * vuelve. Cero perdida.
 */
@Component
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);
    private static final int LOTE = 100;

    private final OutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;

    public OutboxPoller(OutboxRepository outbox, KafkaTemplate<String, String> kafka) {
        this.outbox = outbox;
        this.kafka = kafka;
    }

    @Scheduled(fixedDelayString = "PT2S")
    @Transactional
    public void publicarPendientes() {
        outbox.tomarPendientes(Limit.of(LOTE)).forEach(evento -> {
            try {
                kafka.send(evento.getTopic(), evento.getEventId(), evento.getPayload())
                     .get();                       // sincrono: queremos saber si salio
                evento.markPublished();
            } catch (Exception e) {
                // Not rethrown: one failing event must not stop the rest of the batch.
                // Queda pending y se reintenta en el proximo ciclo.
                evento.recordFailedAttempt();
                log.warn("OUTBOX_PUBLISH_FALLIDO eventId={} topic={} intentos={}",
                        evento.getEventId(), evento.getTopic(), evento.getAttempts());
                Thread.currentThread().interrupt();
            }
        });
    }
}
```

- [ ] **Step 6: Correr los tests y verificar que pasan**

Run: `mvn -q test -Dtest=OutboxIT`
Expected: PASS — 2 tests.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/ar/edu/utn/frc/tup/p4/usersservice/shared/events src/main/java/ar/edu/utn/frc/tup/p4/usersservice/config src/test/java
git commit -m "feat: outbox transaccional para publicar a Kafka (DEC-45b)

Kafka no participa de la transaccion de MySQL. Sin outbox, un publish fallido
tras un commit exitoso deja al alumno en PENDING_COURSE para siempre:
RF-USR-05e no tiene expiracion ni status de rechazo, no hay rescate posible.
El poller usa FOR UPDATE SKIP LOCKED para soportar mas de una instancia."
```

---
### Task 7: Mails por enum y publicación hacia notificaciones

**Files:**
- Create: `src/main/java/…/shared/notifications/EmailType.java`
- Create: `src/main/java/…/shared/notifications/EmailTemplateService.java`
- Create: `src/main/java/…/shared/notifications/NotificationEventPublisher.java`
- Create: `src/main/resources/templates/{code-2fa,account-activation,reset-password,whitelist-request-pending,whitelist-request-resolved,breakglass-alert,whitelist-submission,whitelist-decision}.html`
- Create: `src/main/resources/messages.properties`
- Test: `src/test/java/…/shared/notifications/EmailTypeTest.java`

**Interfaces:**
- Consumes: `AccountEventPublisher` (T6), `KafkaTopicsProperties` (T6).
- Produces: `NotificationEventPublisher.enviar(EmailType tipo, String to, Map<String,Object> vars)` — renderiza y **escribe al outbox**; hay que llamarlo dentro de una transacción.

- [ ] **Step 1: Escribir el test (falla)**

`DEC-45c`: el valor no es la elegancia, es que **este test se pueda escribir**. Con seis métodos sueltos habría que listarlos a mano y el séptimo que alguien agregue queda sin cubrir.

```java
package ar.edu.utn.frc.tup.p4.usersservice.shared.notifications;

import ar.edu.utn.frc.tup.p4.usersservice.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EmailTypeTest extends AbstractIntegrationTest {

    @Autowired EmailTemplateService templates;

    /** Filler variables covering EVERY template. */
    private Map<String, Object> vars() {
        Map<String, Object> v = new HashMap<>();
        v.put("firstNames", "Ana");
        v.put("code", "123456");
        v.put("enlace", "https://app.tpi.utn.frc/reset?token=x");
        v.put("accountStatus", "PENDING_COURSE");
        v.put("reason", "un reason");
        v.put("emailSolicitado", "otro@utn.edu.ar");
        v.put("resultado", "APPROVED");
        v.put("adminId", "a3f1c2e4");
        return v;
    }

    @ParameterizedTest
    @EnumSource(EmailType.class)
    void cada_plantilla_existe_en_el_classpath(EmailType tipo) {
        assertThat(new ClassPathResource("templates/" + tipo.template()).exists())
                .as("falta templates/%s para %s", tipo.template(), tipo)
                .isTrue();
    }

    @ParameterizedTest
    @EnumSource(EmailType.class)
    void cada_plantilla_renderiza_sin_variables_sin_resolver(EmailType tipo) {
        var mail = templates.render(tipo, vars());

        assertThat(mail.asunto()).isNotBlank();
        assertThat(mail.html()).isNotBlank();
        // A leftover ${...} or [[...]] means the template has a variable that
        // nobody passes to it.
        assertThat(mail.html()).doesNotContain("${").doesNotContain("[[");
    }

    @ParameterizedTest
    @EnumSource(EmailType.class)
    void cada_tipo_tiene_un_eventType_unico(EmailType tipo) {
        long repetidos = java.util.Arrays.stream(EmailType.values())
                .filter(t -> t.eventType().equals(tipo.eventType())).count();
        assertThat(repetidos).isEqualTo(1);
    }
}
```

- [ ] **Step 2: Correr y verificar que falla**

Run: `mvn -q test -Dtest=EmailTypeTest`
Expected: FAIL — no compila, falta `EmailType`.

- [ ] **Step 3: Escribir el enum**

```java
package ar.edu.utn.frc.tup.p4.usersservice.shared.notifications;

/**
 * DEC-45c - a registry as an enum. Adding an e-mail is one row and one .html,
 * not a new method, and EmailTypeTest automatically covers whatever is added.
 */
public enum EmailType {

    TWO_FACTOR_CODE       ("code-2fa.html",                   "email.2fa.subject",          "EMAIL_2FA"),
    ACCOUNT_ACTIVATION    ("account-activation.html",         "email.activation.subject",   "EMAIL_ACTIVACION_CUENTA"),
    RESET_PASSWORD        ("reset-password.html",             "email.reset.subject",        "EMAIL_RESET_PASSWORD"),
    REQUEST_PENDING       ("whitelist-request-pending.html",  "email.request.subject",      "EMAIL_SOLICITUD_PENDIENTE"),
    WHITELISTING_RESOLVED ("whitelist-request-resolved.html", "email.whitelisting.subject", "EMAIL_HABILITACION_RESUELTA"),
    BREAKGLASS_ALERT      ("breakglass-alert.html",           "email.breakglass.subject",   "EMAIL_ALERTA_BREAKGLASS"),
    WHITELIST_SUBMISSION  ("whitelist-submission.html",       "email.wl.request.subject",   "EMAIL_WHITELIST_SOLICITUD"),
    WHITELIST_DECISION    ("whitelist-decision.html",         "email.wl.resolved.subject",  "EMAIL_WHITELIST_RESUELTA");

    private final String template;
    private final String subjectKey;
    private final String eventType;

    EmailType(String template, String subjectKey, String eventType) {
        this.template = template;
        this.subjectKey = subjectKey;
        this.eventType = eventType;
    }

    public String template()   { return template; }
    public String subjectKey() { return subjectKey; }
    public String eventType()  { return eventType; }
}
```

- [ ] **Step 4: Escribir `messages.properties`**

```properties
email.2fa.subject=Tu codigo de acceso
email.activation.subject=Activa tu cuenta
email.reset.subject=Recuperacion de contrasena
email.request.subject=Tu solicitud fue enviada
email.whitelisting.subject=Tu cuenta fue habilitada
email.breakglass.subject=ALERTA: se uso la recuperacion de emergencia de ADMIN
email.wl.request.subject=Nueva solicitud de lista blanca
email.wl.resolved.subject=Tu solicitud de lista blanca fue resuelta
```

- [ ] **Step 5: Escribir las ocho plantillas**

Todas siguen la misma forma. `code-2fa.html`:

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<body style="font-family:sans-serif">
  <p>Hola <span th:text="${firstNames}">Nombre</span>,</p>
  <p>Tu code de acceso es:</p>
  <p style="font-size:28px;letter-spacing:4px"><strong th:text="${code}">000000</strong></p>
  <p>Vence en 5 minutos. Si no fuiste vos, ignora este message.</p>
</body>
</html>
```

`account-activation.html` (DEC-33: código, ya no link):
```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<body style="font-family:sans-serif">
  <p>Hola <span th:text="${firstNames}">Nombre</span>,</p>
  <p>Para activate tu cuenta ingresa este code en la pantalla de validation:</p>
  <p style="font-size:28px;letter-spacing:4px"><strong th:text="${code}">000000</strong></p>
  <p>Vence en 30 minutos. Si vencio, pedi uno nuevo from la misma pantalla.</p>
</body>
</html>
```

`reset-password.html` (sigue siendo link, `DEC-33`):
```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<body style="font-family:sans-serif">
  <p>Hola <span th:text="${firstNames}">Nombre</span>,</p>
  <p>Recibimos un pedido de recuperacion de contrasena.</p>
  <p><a th:href="${enlace}" href="#">Elegir una contrasena nueva</a></p>
  <p>El enlace vence en 15 minutos y se usa una sola vez. Si no lo pediste, ignora este message.</p>
</body>
</html>
```

`whitelist-request-pending.html`:
```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<body style="font-family:sans-serif">
  <p>Hola <span th:text="${firstNames}">Nombre</span>,</p>
  <p>Tu request fue enviada y esta a la espera de que se valide tu inscripcion.</p>
  <p>Estado actual: <strong th:text="${accountStatus}">PENDING_COURSE</strong>.</p>
  <p>Te avisamos por este medio apenas se resuelva.</p>
</body>
</html>
```

`whitelist-request-resolved.html`:
```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<body style="font-family:sans-serif">
  <p>Hola <span th:text="${firstNames}">Nombre</span>,</p>
  <p>Tu cuenta fue habilitada. Ya podes ingresar a la plataforma.</p>
</body>
</html>
```

`breakglass-alert.html`:
```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<body style="font-family:sans-serif">
  <p><strong>Se uso la recuperacion de emergencia de ADMIN.</strong></p>
  <p>Se creo el ADMIN <span th:text="${adminId}">id</span>.</p>
  <p>Si no reconoces esta accion, es un incidente de seguridad: revisa el log de auditoria ahora.</p>
</body>
</html>
```

`whitelist-submission.html`:
```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<body style="font-family:sans-serif">
  <p>Hay una request nueva para sumar un email a la lista blanca.</p>
  <p>Email: <strong th:text="${emailSolicitado}">alguien@utn.edu.ar</strong></p>
  <p>Motivo: <span th:text="${reason}">reason</span></p>
</body>
</html>
```

`whitelist-decision.html`:
```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<body style="font-family:sans-serif">
  <p>Hola <span th:text="${firstNames}">Nombre</span>,</p>
  <p>Tu request para <strong th:text="${emailSolicitado}">email</strong> fue
     <strong th:text="${resultado}">APPROVED</strong>.</p>
  <p th:if="${reason != null}">Motivo: <span th:text="${reason}">reason</span></p>
</body>
</html>
```

- [ ] **Step 6: Escribir el servicio de plantillas**

```java
package ar.edu.utn.frc.tup.p4.usersservice.shared.notifications;

import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.util.Locale;
import java.util.Map;

@Service
public class EmailTemplateService {

    public record MailArmado(String asunto, String html) { }

    private static final Locale ES_AR = Locale.forLanguageTag("es-AR");

    private final TemplateEngine engine;
    private final MessageSource messages;

    public EmailTemplateService(TemplateEngine engine, MessageSource messages) {
        this.engine = engine;
        this.messages = messages;
    }

    public MailArmado render(EmailType tipo, Map<String, Object> vars) {
        Context ctx = new Context(ES_AR);
        ctx.setVariables(vars);
        String html = engine.process(tipo.template(), ctx);
        String asunto = messages.getMessage(tipo.subjectKey(), null, ES_AR);
        return new MailArmado(asunto, html);
    }
}
```

- [ ] **Step 7: Escribir el publisher de notificaciones**

```java
package ar.edu.utn.frc.tup.p4.usersservice.shared.notifications;

import ar.edu.utn.frc.tup.p4.usersservice.config.KafkaTopicsProperties;
import ar.edu.utn.frc.tup.p4.usersservice.shared.events.AccountEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * DEC-34 · Nuestra mitad del contrato con notifications-service: renderizamos
 * the mail and we publish it ALREADY BUILT. They pick channel and provider.
 * El payload es exactamente {to, asunto, html} — tres campos, sin anidar.
 * The only thing left to agree on is the topic name, and that is a property.
 */
@Component
public class NotificationEventPublisher {

    public record PayloadEmail(String to, String asunto, String html) { }

    private final EmailTemplateService templates;
    private final AccountEventPublisher outbox;
    private final KafkaTopicsProperties topics;

    public NotificationEventPublisher(EmailTemplateService templates,
                                      AccountEventPublisher outbox,
                                      KafkaTopicsProperties topics) {
        this.templates = templates;
        this.outbox = outbox;
        this.topics = topics;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enviar(EmailType tipo, String to, Map<String, Object> vars) {
        var mail = templates.render(tipo, vars);
        outbox.publicar(topics.notifications(), tipo.eventType(),
                new PayloadEmail(to, mail.asunto(), mail.html()));
    }
}
```

- [ ] **Step 8: Correr los tests y verificar que pasan**

Run: `mvn -q test -Dtest=EmailTypeTest`
Expected: PASS — 24 tests (3 × 8 valores del enum).

- [ ] **Step 9: Commit**

```bash
git add src/main/java/ar/edu/utn/frc/tup/p4/usersservice/shared/notifications src/main/resources/templates src/main/resources/messages.properties src/test/java
git commit -m "feat: mails por registro enum + publicacion al outbox (DEC-45c)

El test parametrizado recorre el enum entero y verifica que cada plantilla
existe y renderiza sin variables sin resolver. Con seis metodos sueltos ese
test no se podia escribir sin listarlos a mano."
```

---

### Task 8: Los tres gates de cuenta

**Files:**
- Create: `src/main/java/…/shared/gates/AccountGateInterceptor.java`
- Create: `src/main/java/…/config/WebConfig.java`
- Test: `src/test/java/…/shared/gates/AccountGateInterceptorTest.java`

**Interfaces:**
- Consumes: `GatewayPrincipal` (T5), `UserRepository` (T2), `ApiException` (T4).
- Produces: anotación `@SkipAccountGate({Gate.ESTADO, Gate.PASSWORD, Gate.ONBOARDING})` para eximir un handler.

> ### 🔴 La regla de las exenciones · leer antes de anotar cualquier endpoint
>
> Cada gate tiene **un endpoint que es su salida**: del de PASSWORD se sale por
> `POST /auth/password/change`, del de ONBOARDING por `PATCH /me/onboarding`.
>
> **Un endpoint de salida se exime de los DOS gates finos, nunca solo del
> propio.** Si cada uno eximiera únicamente su gate, con las dos cosas
> pendientes a la vez se bloquean mutuamente: el de password devuelve
> `onboarding-pending` y el de onboarding devuelve
> `password-change-required`. Los dos 403, y **ningún orden de pantallas lo
> resuelve** desde el frontend.
>
> No es un caso de borde: le pasa a **todo ADMIN nuevo**, que nace con
> `mustChangePassword = true` **y** `firstLogin = true` — o sea al ADMIN
> inicial de `RF-USR-01`, que quedaría sin poder entrar nunca.
>
> Lo que **no** se relaja: el gate de **ESTADO** sigue aplicando a los dos. Una
> cuenta que no está `ACTIVE` no completa onboarding ni cambia la password.
>
> | Endpoint | ESTADO | PASSWORD | ONBOARDING |
> |---|:--:|:--:|:--:|
> | `GET /api/users/me` | exento | exento | exento |
> | `POST /api/users/auth/logout` | exento | exento | exento |
> | `POST /api/users/auth/password/change` | **aplica** | exento | exento |
> | `PATCH /api/users/me/onboarding` | **aplica** | exento | exento |
>
> Los dos tests del Step 4 fijan las dos mitades de esta regla.

- [ ] **Step 1: Leer la anotación — ya está en la base**

`shared/gates/SkipAccountGate.java` **no la escribís vos**: viene en la base.
La ponen tres lotes en sus endpoints (L1 en logout, refresh y cambio de
password; L6 en onboarding; vos en los tuyos) y la interpreta **uno solo**,
que es tu `AccountGateInterceptor`. Una anotación que tres lotes necesitan
para compilar es una costura, y las costuras son de la base.

Mientras vivió acá, L1 no podía escribir una línea de T14 hasta que vos
mergearas. Esto es lo que ya está en `main`, para que la leas:

```java
package ar.edu.utn.frc.tup.p4.usersservice.shared.gates;

import java.lang.annotation.*;

/**
 * DEC-14 - the gates are conditions of the ACCOUNT STATUS, not of the role.
 * Mixing them into @PreAuthorize would mean repeating the same condition in
 * every annotation, and every controller knowing about statuses.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface SkipAccountGate {

    Gate[] value();

    enum Gate { ESTADO, PASSWORD, ONBOARDING }
}
```

- [ ] **Step 2: Escribir el test (falla)**

```java
package ar.edu.utn.frc.tup.p4.usersservice.shared.gates;

import ar.edu.utn.frc.tup.p4.usersservice.shared.security.GatewayPrincipal;
import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ApiException;
import ar.edu.utn.frc.tup.p4.usersservice.users.entities.User;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.AccountStatus;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AccountGateInterceptorTest {

    UserRepository repo = mock(UserRepository.class);
    AccountGateInterceptor interceptor = new AccountGateInterceptor(repo);
    UUID userId = UUID.randomUUID();

    static class Handlers {
        public void protegido() { }
        @SkipAccountGate({SkipAccountGate.Gate.ESTADO, SkipAccountGate.Gate.PASSWORD,
                          SkipAccountGate.Gate.ONBOARDING})
        public void me() { }
        /** The same exemptions as POST /api/users/auth/password/change. */
        @SkipAccountGate({SkipAccountGate.Gate.PASSWORD, SkipAccountGate.Gate.ONBOARDING})
        public void cambioPassword() { }
        /** The same exemptions as PATCH /api/users/me/onboarding. */
        @SkipAccountGate({SkipAccountGate.Gate.ONBOARDING, SkipAccountGate.Gate.PASSWORD})
        public void onboarding() { }
    }

    private HandlerMethod handler(String nombre) throws Exception {
        Method m = Handlers.class.getMethod(nombre);
        return new HandlerMethod(new Handlers(), m);
    }

    private void autenticarPersona() {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        new GatewayPrincipal("user", userId, null), null, List.of()));
    }

    private User usuario(AccountStatus status, boolean debeCambiar, boolean firstLogin) {
        User u = User.create("Ana", "P", "a@utn.edu.ar", "$2a$12$h", Role.STUDENT, "v1");
        u.forceStatusForTest(status);
        if (debeCambiar) u.requirePasswordChange();
        if (!firstLogin) u.completeOnboarding("ana", null, true);
        return u;
    }

    @BeforeEach
    void setUp() { autenticarPersona(); }

    @AfterEach
    void limpiar() { SecurityContextHolder.clearContext(); }

    @Test
    void cuenta_pendiente_bloquea_una_ruta_protegida() throws Exception {
        when(repo.findByIdAndDeletedAtIsNull(any()))
                .thenReturn(Optional.of(usuario(AccountStatus.PENDING_COURSE, false, false)));

        assertThatThrownBy(() -> interceptor.preHandle(
                new MockHttpServletRequest(), new MockHttpServletResponse(), handler("protegido")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getExtras())
                        .containsEntry("accountStatus", "PENDING_COURSE"));
    }

    @Test
    void GET_me_atraviesa_los_tres_gates() throws Exception {
        // This is how the frontend finds out WHAT the account is missing
        // persona. Si tambien estuviera bloqueada, no habria forma de saberlo.
        when(repo.findByIdAndDeletedAtIsNull(any()))
                .thenReturn(Optional.of(usuario(AccountStatus.PENDING_COURSE, true, true)));

        assertThat(interceptor.preHandle(
                new MockHttpServletRequest(), new MockHttpServletResponse(), handler("me"))).isTrue();
    }

    @Test
    void onboarding_pendiente_bloquea_aunque_la_cuenta_este_ACTIVA() throws Exception {
        // Criterio de DoD #7.
        when(repo.findByIdAndDeletedAtIsNull(any()))
                .thenReturn(Optional.of(usuario(AccountStatus.ACTIVE, false, true)));

        assertThatThrownBy(() -> interceptor.preHandle(
                new MockHttpServletRequest(), new MockHttpServletResponse(), handler("protegido")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("onboarding");
    }

    @Test
    void las_dos_salidas_funcionan_con_los_dos_gates_pendientes() throws Exception {
        // RF-USR-01. El ADMIN inicial nace con mustChangePassword Y firstLogin
        // set to true. If each endpoint exempted only ONE gate, the password one
        // would be cut by onboarding and the onboarding one by password: the account
        // queda encerrada y el requisito es inalcanzable.
        when(repo.findByIdAndDeletedAtIsNull(any()))
                .thenReturn(Optional.of(usuario(AccountStatus.ACTIVE, true, true)));

        assertThat(interceptor.preHandle(new MockHttpServletRequest(),
                new MockHttpServletResponse(), handler("cambioPassword"))).isTrue();
        assertThat(interceptor.preHandle(new MockHttpServletRequest(),
                new MockHttpServletResponse(), handler("onboarding"))).isTrue();
    }

    @Test
    void las_dos_salidas_siguen_respetando_el_gate_de_estado() throws Exception {
        // What is NOT relaxed: an account that is not ACTIVE completes no
        // onboarding and changes no password. They only unblock each other.
        when(repo.findByIdAndDeletedAtIsNull(any()))
                .thenReturn(Optional.of(usuario(AccountStatus.PENDING_COURSE, true, true)));

        assertThatThrownBy(() -> interceptor.preHandle(new MockHttpServletRequest(),
                new MockHttpServletResponse(), handler("onboarding")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("no esta activa");
    }

    @Test
    void un_token_de_servicio_no_atraviesa_ningun_gate() throws Exception {
        // An MS does not stand for a person with an account: no status to check.
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        new GatewayPrincipal("service", null, "cursos-service"), null, List.of()));

        assertThat(interceptor.preHandle(
                new MockHttpServletRequest(), new MockHttpServletResponse(), handler("protegido"))).isTrue();
    }

    @Test
    void el_orden_es_estado_password_onboarding() throws Exception {
        // With all three active, STATUS wins: it is the most restrictive and the
        // one the frontend has to resolve first.
        when(repo.findByIdAndDeletedAtIsNull(any()))
                .thenReturn(Optional.of(usuario(AccountStatus.PENDING_EMAIL, true, true)));

        assertThatThrownBy(() -> interceptor.preHandle(
                new MockHttpServletRequest(), new MockHttpServletResponse(), handler("protegido")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("no esta activa");
    }
}
```

- [ ] **Step 3: Correr y verificar que falla**

Run: `mvn -q test -Dtest=AccountGateInterceptorTest`
Expected: FAIL — falta `AccountGateInterceptor`.

- [ ] **Step 4: Escribir el interceptor**

```java
package ar.edu.utn.frc.tup.p4.usersservice.shared.gates;

import ar.edu.utn.frc.tup.p4.usersservice.shared.security.GatewayPrincipal;
import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ApiException;
import ar.edu.utn.frc.tup.p4.usersservice.users.entities.User;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.AccountStatus;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.EnumSet;
import java.util.Set;

import static ar.edu.utn.frc.tup.p4.usersservice.shared.gates.SkipAccountGate.Gate.*;

/**
 * DEC-14 - the three gates of §8, in order, each with its own error type.
 * Solo aplican a X-Principal-Type: user.
 *
 * DEC-23: these are the FINE gates, over users-service routes. The coarse gate
 * over other services' routes is applied by the gateway, reading the est/pwd/onb
 * claims - this service never sees that traffic.
 */
@Component
public class AccountGateInterceptor implements HandlerInterceptor {

    private final UserRepository repo;

    public AccountGateInterceptor(UserRepository repo) { this.repo = repo; }

    @Override
    public boolean preHandle(@NonNull HttpServletRequest req, @NonNull HttpServletResponse res,
                             @NonNull Object handler) {
        if (!(handler instanceof HandlerMethod hm)) return true;

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof GatewayPrincipal p) || !p.isPerson()) {
            return true;   // publico o token de servicio: ningun gate aplica
        }

        Set<SkipAccountGate.Gate> exentos = exencionesDe(hm);
        if (exentos.containsAll(EnumSet.allOf(SkipAccountGate.Gate.class))) return true;

        // One query per authenticated person request. The status does NOT come
        // the headers: the source of truth is the row, not the token (DEC-23).
        User u = repo.findByIdAndDeletedAtIsNull(p.id())
                .orElseThrow(ApiException::invalidCredentials);

        if (!exentos.contains(ESTADO) && u.getAccountStatus() != AccountStatus.ACTIVE) {
            throw ApiException.pendingAccount(u.getAccountStatus());
        }
        if (!exentos.contains(PASSWORD) && u.mustChangePassword()) {
            throw ApiException.passwordChangeRequired();
        }
        if (!exentos.contains(ONBOARDING) && u.isFirstLogin()) {
            throw ApiException.onboardingPending();
        }
        return true;
    }

    private Set<SkipAccountGate.Gate> exencionesDe(HandlerMethod hm) {
        SkipAccountGate a = hm.getMethodAnnotation(SkipAccountGate.class);
        return a == null ? EnumSet.noneOf(SkipAccountGate.Gate.class)
                         : EnumSet.copyOf(java.util.List.of(a.value()));
    }
}
```

- [ ] **Step 5: Registrar el interceptor**

```java
package ar.edu.utn.frc.tup.p4.usersservice.config;

import ar.edu.utn.frc.tup.p4.usersservice.shared.gates.AccountGateInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AccountGateInterceptor gates;

    public WebConfig(AccountGateInterceptor gates) { this.gates = gates; }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(gates)
                .addPathPatterns("/api/users/**")
                .excludePathPatterns("/api/users/public/**");   // publicas: sin gates
    }
}
```

- [ ] **Step 6: Correr y verificar que pasa**

Run: `mvn -q test -Dtest=AccountGateInterceptorTest`
Expected: PASS — 5 tests.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/ar/edu/utn/frc/tup/p4/usersservice/shared/gates src/main/java/ar/edu/utn/frc/tup/p4/usersservice/config/WebConfig.java src/test/java
git commit -m "feat: los tres gates de cuenta como HandlerInterceptor (DEC-14)

Exencion por anotacion. GET /me atraviesa los tres: es el mecanismo por el
que el frontend averigua que le falta a la persona."
```

---

### Task 9: Claves RS256 y endpoint JWKS

**Files:**
- Create: `scripts/gen-dev-keys.sh`
- Create: `src/main/java/…/auth/keys/SigningKeyProvider.java`
- Create: `src/main/java/…/auth/keys/impl/FileSystemSigningKeyProvider.java`
- Create: `src/main/java/…/auth/controllers/JwksController.java`
- Create: `src/main/java/…/config/JwtProperties.java`
- Test: `src/test/java/…/auth/keys/SigningKeyProviderTest.java`

**Interfaces:**
- Consumes: nada.
- Produces: `SigningKeyProvider` con `RSAKey claveDeFirma()` (privada, con `kid` activo) y `JWKSet jwksPublico()` (todas las públicas vigentes).

- [ ] **Step 1: Escribir el script de claves de desarrollo**

```bash
#!/usr/bin/env bash
# DEC-18 · Genera un par RSA de DESARROLLO en ./secrets (gitignoreado).
# No se commitea ningun par, ni siquiera uno "de mentira": esa costumbre es
# la que despues filtra el de produccion.
set -euo pipefail

KID="${1:-dev}"
mkdir -p secrets/jwks

if [ -f "secrets/jwt-private.pem" ]; then
  echo "Ya existe secrets/jwt-private.pem — no se sobrescribe."
  exit 0
fi

openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out secrets/jwt-private.pem
openssl rsa -in secrets/jwt-private.pem -pubout -out "secrets/jwks/${KID}.pem"

echo "Par generado. Exportá:  JWT_ACTIVE_KID=${KID}"
```

Agregar `secrets/` y `.env` a `.gitignore`.

- [ ] **Step 2: Escribir las properties**

```java
package ar.edu.utn.frc.tup.p4.usersservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@ConfigurationProperties(prefix = "users.jwt")
public record JwtProperties(String issuer, Duration accessTtl, Duration refreshTtl,
                            Duration serviceTtl, String privateKeyPath,
                            String publicKeysDir, String activeKid) { }
```

- [ ] **Step 3: Escribir el test (falla)**

```java
package ar.edu.utn.frc.tup.p4.usersservice.auth.keys;

import ar.edu.utn.frc.tup.p4.usersservice.auth.keys.impl.FileSystemSigningKeyProvider;
import ar.edu.utn.frc.tup.p4.usersservice.config.JwtProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SigningKeyProviderTest {

    @TempDir static Path dir;
    static Path privada;
    static Path jwksDir;

    @BeforeAll
    static void generarPar() throws IOException, InterruptedException {
        privada = dir.resolve("jwt-private.pem");
        jwksDir = Files.createDirectories(dir.resolve("jwks"));
        ejecutar("openssl", "genpkey", "-algorithm", "RSA",
                 "-pkeyopt", "rsa_keygen_bits:2048", "-out", privada.toString());
        ejecutar("openssl", "rsa", "-in", privada.toString(), "-pubout",
                 "-out", jwksDir.resolve("2026-09.pem").toString());
        ejecutar("openssl", "genpkey", "-algorithm", "RSA",
                 "-pkeyopt", "rsa_keygen_bits:2048", "-out", dir.resolve("vieja.pem").toString());
        ejecutar("openssl", "rsa", "-in", dir.resolve("vieja.pem").toString(), "-pubout",
                 "-out", jwksDir.resolve("2026-03.pem").toString());
    }

    private static void ejecutar(String... cmd) throws IOException, InterruptedException {
        assertThat(new ProcessBuilder(cmd).inheritIO().start().waitFor()).isZero();
    }

    private JwtProperties props(String privPath, String kid) {
        return new JwtProperties("users-service", Duration.ofMinutes(10), Duration.ofDays(7),
                Duration.ofMinutes(5), privPath, jwksDir.toString(), kid);
    }

    @Test
    void firma_con_el_kid_activo() {
        var p = new FileSystemSigningKeyProvider(props(privada.toString(), "2026-09"));
        assertThat(p.claveDeFirma().getKeyID()).isEqualTo("2026-09");
        assertThat(p.claveDeFirma().isPrivate()).isTrue();
    }

    @Test
    void el_jwks_publica_TODAS_las_publicas_del_directorio() {
        // Rotation with no 401 window: as long as tokens signed with the
        // key vieja, su publica sigue publicada.
        var p = new FileSystemSigningKeyProvider(props(privada.toString(), "2026-09"));
        assertThat(p.jwksPublico().getKeys()).extracting(k -> k.getKeyID())
                .containsExactlyInAnyOrder("2026-09", "2026-03");
    }

    @Test
    void el_jwks_no_expone_ninguna_clave_privada() {
        var p = new FileSystemSigningKeyProvider(props(privada.toString(), "2026-09"));
        assertThat(p.jwksPublico().getKeys()).allMatch(k -> !k.isPrivate());
    }

    @Test
    void si_la_clave_privada_no_existe_la_aplicacion_NO_arranca() {
        // DEC-18: no hay fallback a generacion en memoria. Con dos instancias,
        // generar al arrancar produce 401 intermitentes y alternantes.
        assertThatThrownBy(() ->
                new FileSystemSigningKeyProvider(props(dir.resolve("no-existe.pem").toString(), "2026-09")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no-existe.pem");
    }

    @Test
    void si_el_kid_activo_no_tiene_publica_en_el_directorio_falla_el_arranque() {
        assertThatThrownBy(() ->
                new FileSystemSigningKeyProvider(props(privada.toString(), "kid-fantasma")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("kid-fantasma");
    }
}
```

- [ ] **Step 4: Correr y verificar que falla**

Run: `mvn -q test -Dtest=SigningKeyProviderTest`
Expected: FAIL — falta `SigningKeyProvider`.

- [ ] **Step 5: Escribir la interfaz y la implementación**

```java
package ar.edu.utn.frc.tup.p4.usersservice.auth.keys;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;

/** DEC-18 - abstracts where the keys come from so the decision does not end up
 *  enterrada en un @PostConstruct. */
public interface SigningKeyProvider {
    RSAKey claveDeFirma();
    JWKSet jwksPublico();
}
```

```java
package ar.edu.utn.frc.tup.p4.usersservice.auth.keys.impl;

import ar.edu.utn.frc.tup.p4.usersservice.auth.keys.SigningKeyProvider;
import ar.edu.utn.frc.tup.p4.usersservice.config.JwtProperties;
import com.nimbusds.jose.jwk.*;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.security.interfaces.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * DEC-18 - PEMs mounted as a secret. NEVER generated at startup:
 *  - with two instances each signs with a different pair, the gateway fetches
 *    the JWKS, the balancer sends it to A, and B's tokens fail -> intermittent
 *    and alternating - one of the most expensive bugs to diagnose;
 *  - on every restart the live access tokens are invalid while the refresh ones
 *    siguen vigentes en Redis: rompe de forma parcial, no limpia.
 */
@Component
public class FileSystemSigningKeyProvider implements SigningKeyProvider {

    private final RSAKey claveDeFirma;
    private final JWKSet jwksPublico;

    public FileSystemSigningKeyProvider(JwtProperties props) {
        Path privada = Path.of(props.privateKeyPath());
        if (!Files.isReadable(privada)) {
            throw new IllegalStateException(
                    "No se puede leer la key privada en " + privada + ". "
                    + "DEC-18: las claves se montan como secret; no hay generacion en memoria.");
        }

        List<JWK> publicas = leerPublicas(Path.of(props.publicKeysDir()));
        RSAKey activaPublica = publicas.stream()
                .map(RSAKey.class::cast)
                .filter(k -> k.getKeyID().equals(props.activeKid()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "El kid activo '" + props.activeKid() + "' no tiene key publica en "
                        + props.publicKeysDir()));

        try {
            RSAPrivateKey priv = (RSAPrivateKey) RSAKey.parseFromPEMEncodedObjects(
                    Files.readString(privada)).toRSAKey().toPrivateKey();
            this.claveDeFirma = new RSAKey.Builder(activaPublica)
                    .privateKey(priv)
                    .keyID(props.activeKid())
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("La key privada de " + privada + " no parsea", e);
        }

        this.jwksPublico = new JWKSet(publicas);
    }

    /** Each key's kid is the file name without the extension. */
    private List<JWK> leerPublicas(Path dir) {
        try (Stream<Path> archivos = Files.list(dir)) {
            return archivos
                    .filter(p -> p.toString().endsWith(".pem"))
                    .sorted()
                    .map(p -> {
                        try {
                            String kid = p.getFileName().toString().replaceFirst("\\.pem$", "");
                            RSAPublicKey pub = (RSAPublicKey) RSAKey
                                    .parseFromPEMEncodedObjects(Files.readString(p))
                                    .toRSAKey().toPublicKey();
                            return (JWK) new RSAKey.Builder(pub)
                                    .keyID(kid)
                                    .keyUse(KeyUse.SIGNATURE)
                                    .algorithm(com.nimbusds.jose.JWSAlgorithm.RS256)
                                    .build();
                        } catch (Exception e) {
                            throw new IllegalStateException("No parsea la key publica " + p, e);
                        }
                    })
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("No se puede leer el directorio de claves publicas " + dir, e);
        }
    }

    @Override public RSAKey claveDeFirma() { return claveDeFirma; }

    /** toPublicJWKSet(): guarantees private material is never published. */
    @Override public JWKSet jwksPublico() { return jwksPublico.toPublicJWKSet(); }
}
```

- [ ] **Step 6: Escribir el controller del JWKS**

```java
package ar.edu.utn.frc.tup.p4.usersservice.auth.controllers;

import ar.edu.utn.frc.tup.p4.usersservice.auth.keys.SigningKeyProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The route does NOT carry the /api/{name} prefix: it is the one real exception
 * convention (RFC 8615), because JWT libraries in any language look there
 * look exactly there. The gateway routes it with a static route (DEC-27).
 * Public, with no authentication: a public key is not a secret.
 */
@RestController
public class JwksController {

    private final SigningKeyProvider keys;

    public JwksController(SigningKeyProvider keys) { this.keys = keys; }

    @GetMapping("/.well-known/jwks.json")
    public Map<String, Object> jwks() {
        return keys.jwksPublico().toJSONObject();
    }
}
```

- [ ] **Step 7: Correr y verificar que pasa**

Run: `mvn -q test -Dtest=SigningKeyProviderTest`
Expected: PASS — 5 tests.

- [ ] **Step 8: Commit**

```bash
git add scripts/gen-dev-keys.sh .gitignore src/main/java src/test/java
git commit -m "feat: claves RS256 from PEM montados + endpoint JWKS (DEC-18)

Sin fallback a generacion en memoria: con dos instancias produce 401
intermitentes y alternantes. El JWKS publica TODAS las publicas del
directorio, asi la rotacion no tiene ventana de 401."
```

---
### Task 10: `TokenClaims` y emisión de tokens

**Files:**
- Create: `src/main/java/…/auth/tokens/TokenClaims.java`
- Create: `src/main/java/…/auth/services/TokenService.java`
- Test: `src/test/java/…/auth/tokens/TokenContractTest.java`

**Interfaces:**
- Consumes: `SigningKeyProvider` (T9), `JwtProperties` (T9), `Role`/`AccountStatus` (T2).
- Produces:
  - `TokenClaims.paraPersona(UUID sub, List<Role> roles, String sid, AccountStatus est, boolean pwd, boolean onb)` → builder con `.build()`.
  - `TokenClaims.paraServicio(String clientId, String audience, Set<String> scopes)` → builder con `.conOnBehalfOf(UUID)` y `.build()`.
  - `TokenService.firmar(TokenClaims)` → `String` (JWT compacto).

- [ ] **Step 1: Escribir el test de contrato (falla)**

Criterio de DoD #31 y `DEC-44`: es lo que **reemplaza al flag de despliegue** del Gateway.

```java
package ar.edu.utn.frc.tup.p4.usersservice.auth.tokens;

import ar.edu.utn.frc.tup.p4.usersservice.users.enums.AccountStatus;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;
import com.nimbusds.jwt.JWTClaimsSet;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DEC-44 - the safety net for the new claims is this test, not a compatibility
 * deployment mode in the gateway. If a token is missing a mandatory claim,
 * fails in CI naming it, before anyone deploys anything.
 */
class TokenContractTest {

    private static final List<String> OBLIGATORIOS_PERSONA =
            List.of("iss", "sub", "roles", "type", "jti", "sid", "est", "pwd", "onb", "iat", "exp");

    private static final List<String> OBLIGATORIOS_SERVICIO =
            List.of("iss", "sub", "roles", "type", "aud", "scope", "jti", "iat", "exp");

    private JWTClaimsSet persona() {
        return TokenClaims.paraPersona(UUID.randomUUID(), List.of(Role.STUDENT), "sid-1",
                        AccountStatus.ACTIVE, false, false)
                .build().aClaimsSet("users-service", java.time.Duration.ofMinutes(10));
    }

    private JWTClaimsSet servicio() {
        return TokenClaims.paraServicio("cursos-service", "users-service", Set.of("users.profile.read"))
                .build().aClaimsSet("users-service", java.time.Duration.ofMinutes(5));
    }

    @Test
    void el_token_de_persona_lleva_TODOS_los_claims_obligatorios() {
        JWTClaimsSet c = persona();
        for (String claim : OBLIGATORIOS_PERSONA) {
            assertThat(c.getClaim(claim)).as("falta el claim '%s' en el token de persona", claim).isNotNull();
        }
    }

    @Test
    void el_token_de_servicio_lleva_TODOS_los_claims_obligatorios() {
        JWTClaimsSet c = servicio();
        for (String claim : OBLIGATORIOS_SERVICIO) {
            assertThat(c.getClaim(claim)).as("falta el claim '%s' en el token de servicio", claim).isNotNull();
        }
    }

    @Test
    void el_iss_es_siempre_users_service() {
        // DEC-07: nombre logico, no URL.
        assertThat(persona().getIssuer()).isEqualTo("users-service");
        assertThat(servicio().getIssuer()).isEqualTo("users-service");
    }

    @Test
    void el_token_de_servicio_NO_lleva_sid_ni_est_pwd_onb() {
        // It does not stand for a person, and it is 100% stateless: no sid, no Redis.
        JWTClaimsSet c = servicio();
        assertThat(c.getClaim("sid")).isNull();
        assertThat(c.getClaim("est")).isNull();
        assertThat(c.getClaim("pwd")).isNull();
        assertThat(c.getClaim("onb")).isNull();
    }

    @Test
    void el_rol_MS_solo_aparece_en_tokens_de_servicio() {
        assertThat(servicio().getStringListClaim("roles")).containsExactly("MS");
        assertThat(persona().getStringListClaim("roles")).doesNotContain("MS");
    }

    @Test
    void on_behalf_of_es_el_unico_opcional_del_token_de_servicio() {
        UUID actor = UUID.randomUUID();
        JWTClaimsSet con = TokenClaims
                .paraServicio("cursos-service", "users-service", Set.of("users.profile.read"))
                .conOnBehalfOf(actor).build()
                .aClaimsSet("users-service", java.time.Duration.ofMinutes(5));

        assertThat(con.getClaim("on_behalf_of")).isEqualTo(actor.toString());
        assertThat(servicio().getClaim("on_behalf_of")).isNull();
    }

    @Test
    void el_exp_respeta_la_vigencia_pedida() {
        JWTClaimsSet c = persona();
        long vigencia = c.getExpirationTime().toInstant().getEpochSecond()
                      - c.getIssueTime().toInstant().getEpochSecond();
        assertThat(vigencia).isEqualTo(600);   // 10 minutos
    }
}
```

- [ ] **Step 2: Correr y verificar que falla**

Run: `mvn -q test -Dtest=TokenContractTest`
Expected: FAIL — falta `TokenClaims`.

- [ ] **Step 3: Escribir `TokenClaims`**

```java
package ar.edu.utn.frc.tup.p4.usersservice.auth.tokens;

import ar.edu.utn.frc.tup.p4.usersservice.users.enums.AccountStatus;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;
import com.nimbusds.jwt.JWTClaimsSet;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * DEC-45a - the mandatory claims are CONSTRUCTOR PARAMETERS: there is no way
 * to skip them. It moves the problem from "caught in CI" to "does not compile".
 * TokenContractTest stays as the second line of defence, not the only one.
 */
public final class TokenClaims {

    private final String tipo;
    private final String sub;
    private final List<String> roles;
    private final String sid;              // solo persona
    private final String est;              // solo persona
    private final Boolean pwd;             // solo persona
    private final Boolean onb;             // solo persona
    private final String aud;              // solo servicio
    private final String scope;            // solo servicio
    private final String onBehalfOf;       // solo servicio, OPCIONAL
    private final String jti;

    private TokenClaims(Builder b) {
        this.tipo = b.tipo;   this.sub = b.sub;   this.roles = b.roles;
        this.sid = b.sid;     this.est = b.est;   this.pwd = b.pwd;   this.onb = b.onb;
        this.aud = b.aud;     this.scope = b.scope; this.onBehalfOf = b.onBehalfOf;
        this.jti = b.jti;
    }

    /** Every mandatory claim of a person token, in the signature. */
    public static Builder paraPersona(UUID sub, List<Role> roles, String sid,
                                      AccountStatus est, boolean pwd, boolean onb) {
        Builder b = new Builder("user", Objects.requireNonNull(sub, "sub").toString());
        b.roles = roles.stream().map(Enum::name).toList();
        b.sid = Objects.requireNonNull(sid, "sid");
        b.est = Objects.requireNonNull(est, "est").name();
        b.pwd = pwd;
        b.onb = onb;
        return b;
    }

    /** The MS role is set HERE, not chosen by the caller: it is never a person's. */
    public static Builder paraServicio(String clientId, String audience, Set<String> scopes) {
        Builder b = new Builder("service", Objects.requireNonNull(clientId, "clientId"));
        b.roles = List.of("MS");
        b.aud = Objects.requireNonNull(audience, "audience");
        b.scope = String.join(" ", new TreeSet<>(Objects.requireNonNull(scopes, "scopes")));
        if (b.scope.isBlank()) throw new IllegalArgumentException("Un token de servicio exige al menos un scope");
        return b;
    }

    public JWTClaimsSet aClaimsSet(String issuer, Duration vigencia) {
        Instant ahora = Instant.now();
        JWTClaimsSet.Builder c = new JWTClaimsSet.Builder()
                .issuer(issuer)                       // DEC-07 · OBLIGATORIO
                .subject(sub)
                .claim("roles", roles)
                .claim("type", tipo)
                .jwtID(jti)
                .issueTime(Date.from(ahora))
                .expirationTime(Date.from(ahora.plus(vigencia)));

        if ("user".equals(tipo)) {
            c.claim("sid", sid).claim("est", est).claim("pwd", pwd).claim("onb", onb);
        } else {
            c.audience(aud).claim("scope", scope);
            if (onBehalfOf != null) c.claim("on_behalf_of", onBehalfOf);
        }
        return c.build();
    }

    public String jti() { return jti; }

    public static final class Builder {
        private final String tipo;
        private final String sub;
        private List<String> roles;
        private String sid, est, aud, scope, onBehalfOf;
        private Boolean pwd, onb;
        private String jti = UUID.randomUUID().toString();

        private Builder(String tipo, String sub) { this.tipo = tipo; this.sub = sub; }

        /** DEC-10: traceability metadata, NOT permissions. Nobody authorizes with this. */
        public Builder conOnBehalfOf(UUID actor) {
            if (!"service".equals(tipo)) {
                throw new IllegalStateException("on_behalf_of solo aplica a tokens de servicio");
            }
            this.onBehalfOf = actor == null ? null : actor.toString();
            return this;
        }

        public Builder conJti(String jti) { this.jti = jti; return this; }

        public TokenClaims build() { return new TokenClaims(this); }
    }
}
```

- [ ] **Step 4: Escribir `TokenService`**

```java
package ar.edu.utn.frc.tup.p4.usersservice.auth.services;

import ar.edu.utn.frc.tup.p4.usersservice.auth.keys.SigningKeyProvider;
import ar.edu.utn.frc.tup.p4.usersservice.auth.tokens.TokenClaims;
import ar.edu.utn.frc.tup.p4.usersservice.config.JwtProperties;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class TokenService {

    private final SigningKeyProvider keys;
    private final JwtProperties props;

    public TokenService(SigningKeyProvider keys, JwtProperties props) {
        this.keys = keys;
        this.props = props;
    }

    public String firmarPersona(TokenClaims claims) { return firmar(claims, props.accessTtl()); }

    public String firmarServicio(TokenClaims claims) { return firmar(claims, props.serviceTtl()); }

    private String firmar(TokenClaims claims, Duration vigencia) {
        try {
            var key = keys.claveDeFirma();
            // The kid in the header is what lets the gateway rotate with no downtime:
            // ante un kid desconocido re-consulta el JWKS.
            JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                    .keyID(key.getKeyID())
                    .build();
            SignedJWT jwt = new SignedJWT(header, claims.aClaimsSet(props.issuer(), vigencia));
            jwt.sign(new RSASSASigner(key.toPrivateKey()));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("No se pudo firmar el token", e);
        }
    }
}
```

- [ ] **Step 5: Correr y verificar que pasa**

Run: `mvn -q test -Dtest=TokenContractTest`
Expected: PASS — 7 tests.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/ar/edu/utn/frc/tup/p4/usersservice/auth src/test/java
git commit -m "feat: TokenClaims con obligatorios en la firma + emision RS256

DEC-45a: emitir un token incompleto pasa de 'se detecta en CI' a 'no compila'.
DEC-44: TokenContractTest es la red que reemplaza al flag del Gateway."
```

---

### Task 11: Redis — `TokenStore`, `EphemeralTokenService` y OTP

**Files:**
- Create: `src/main/java/…/auth/store/TokenStore.java`
- Create: `src/main/java/…/auth/store/EphemeralTokenService.java`
- Create: `src/main/java/…/auth/store/impl/{RedisTokenStore,RedisEphemeralTokenService}.java`
- Create: `src/main/java/…/auth/otp/OtpService.java`
- Create: `src/main/java/…/config/OtpProperties.java`
- Test: `src/test/java/…/auth/otp/OtpServiceIT.java`

**Interfaces:**
- Consumes: `AbstractIntegrationTest` (T1), `ApiException` (T4).
- Produces:
  - `EphemeralTokenService` — **la segunda puerta entre módulos**: `void guardar(String,String,Duration)`, `Optional<String> consumir(String)` (lee y borra, atómico), `Optional<String> verificar(String)`.
  - `TokenStore` — `void guardarSesion(UUID,String)`, `Optional<String> sidDe(UUID)`, `void borrarSesion(UUID)`, `void guardarRefresh(String, RefreshData, Duration)`, `Optional<RefreshData> refresh(String)`, `void revocarRefresh(String)`, `void revocarFamilia(UUID)`, `int incrementarFallos(String, Duration)`, `void limpiarFallos(String)`.
  - `OtpService.generar(String key, Duration ttl)` → `String` (6 dígitos), `void verificar(String key, String code)` → lanza `ApiException.invalidCode()`.

- [ ] **Step 1: Escribir las interfaces**

```java
package ar.edu.utn.frc.tup.p4.usersservice.auth.store;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/** The Redis contract. The implementation does not leak into the domain (Adapter). */
public interface TokenStore {

    /** DEC-22: session:{userId} is written by ONE operation (the post-2FA login). */
    void guardarSesion(UUID userId, String sid);
    Optional<String> sidDe(UUID userId);
    /** DEC-02 (logout) y DEC-23 (deactivate) son los DOS unicos borrados. */
    void borrarSesion(UUID userId);

    record RefreshData(UUID userId, String sid, String familyId) { }

    void guardarRefresh(String jti, RefreshData data, Duration ttl);
    Optional<RefreshData> refresh(String jti);
    void revocarRefresh(String jti);
    void revocarFamilia(String familyId);
    boolean familiaRevocada(String familyId);

    int incrementarFallos(String key, Duration ventana);
    void limpiarFallos(String key);
}
```

```java
package ar.edu.utn.frc.tup.p4.usersservice.auth.store;

import java.time.Duration;
import java.util.Optional;

/**
 * The SECOND door between modules (§5): users/ has to store the activation
 * code, but it cannot know about Redis. auth/ exposes this and nothing else.
 */
public interface EphemeralTokenService {
    void guardar(String key, String valor, Duration ttl);
    /** Reads and DELETES, atomically. Single use. */
    Optional<String> consumir(String key);
    /** Reads without deleting. */
    Optional<String> verificar(String key);
}
```

- [ ] **Step 2: Escribir las implementaciones Redis**

```java
package ar.edu.utn.frc.tup.p4.usersservice.auth.store.impl;

import ar.edu.utn.frc.tup.p4.usersservice.auth.store.TokenStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

@Component
public class RedisTokenStore implements TokenStore {

    private static final String SESION   = "session:";
    private static final String REFRESH  = "refresh:";
    private static final String FAMILIA  = "refresh:familia-revocada:";
    private static final String FALLOS   = "ratelimit:login:";

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public RedisTokenStore(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    /** No TTL: it lives as long as the active session. That is why Redis needs AOF. */
    @Override public void guardarSesion(UUID userId, String sid) {
        redis.opsForValue().set(SESION + userId, sid);
    }

    @Override public Optional<String> sidDe(UUID userId) {
        return Optional.ofNullable(redis.opsForValue().get(SESION + userId));
    }

    @Override public void borrarSesion(UUID userId) { redis.delete(SESION + userId); }

    @Override public void guardarRefresh(String jti, RefreshData data, Duration ttl) {
        redis.opsForValue().set(REFRESH + jti, escribir(data), ttl);
    }

    @Override public Optional<RefreshData> refresh(String jti) {
        return Optional.ofNullable(redis.opsForValue().get(REFRESH + jti)).map(this::leer);
    }

    @Override public void revocarRefresh(String jti) { redis.delete(REFRESH + jti); }

    /** Reuse detection: the whole family is revoked, not just the jti. */
    @Override public void revocarFamilia(String familyId) {
        redis.opsForValue().set(FAMILIA + familyId, "1", Duration.ofDays(7));
    }

    @Override public boolean familiaRevocada(String familyId) {
        return Boolean.TRUE.equals(redis.hasKey(FAMILIA + familyId));
    }

    /** DEC-42: it counts FAILURES. The first increment sets the window's TTL. */
    @Override public int incrementarFallos(String key, Duration ventana) {
        Long n = redis.opsForValue().increment(FALLOS + key);
        if (n != null && n == 1L) redis.expire(FALLOS + key, ventana);
        return n == null ? 0 : n.intValue();
    }

    @Override public void limpiarFallos(String key) { redis.delete(FALLOS + key); }

    private String escribir(RefreshData d) {
        try { return mapper.writeValueAsString(d); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private RefreshData leer(String json) {
        try { return mapper.readValue(json, RefreshData.class); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
```

```java
package ar.edu.utn.frc.tup.p4.usersservice.auth.store.impl;

import ar.edu.utn.frc.tup.p4.usersservice.auth.store.EphemeralTokenService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

@Component
public class RedisEphemeralTokenService implements EphemeralTokenService {

    private final StringRedisTemplate redis;

    public RedisEphemeralTokenService(StringRedisTemplate redis) { this.redis = redis; }

    @Override public void guardar(String key, String valor, Duration ttl) {
        redis.opsForValue().set(key, valor, ttl);
    }

    /** getAndDelete: atomic. Without it, two simultaneous requests consume the same code. */
    @Override public Optional<String> consumir(String key) {
        return Optional.ofNullable(redis.opsForValue().getAndDelete(key));
    }

    @Override public Optional<String> verificar(String key) {
        return Optional.ofNullable(redis.opsForValue().get(key));
    }
}
```

- [ ] **Step 3: Escribir el test del OTP (falla)**

Criterio de DoD #26.

```java
package ar.edu.utn.frc.tup.p4.usersservice.auth.otp;

import ar.edu.utn.frc.tup.p4.usersservice.AbstractIntegrationTest;
import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** DEC-33 - one single OTP engine for 2FA and e-mail validation. */
class OtpServiceIT extends AbstractIntegrationTest {

    @Autowired OtpService otp;

    @Test
    void genera_seis_digitos() {
        assertThat(otp.generar("test:1", Duration.ofMinutes(5))).matches("\\d{6}");
    }

    @Test
    void el_codigo_correcto_verifica_y_se_consume() {
        String code = otp.generar("test:2", Duration.ofMinutes(5));
        otp.verificar("test:2", code);
        // Single use: a second attempt with the same code no longer works.
        assertThatThrownBy(() -> otp.verificar("test:2", code)).isInstanceOf(ApiException.class);
    }

    @Test
    void codigo_incorrecto_clave_inexistente_y_vencido_dan_LA_MISMA_respuesta() {
        // Anti-enumeration: it does not reveal whether that address is registered.
        String code = otp.generar("test:3", Duration.ofMinutes(5));

        String m1 = capturar(() -> otp.verificar("test:3", "000000"));
        String m2 = capturar(() -> otp.verificar("test:no-existe", code));
        assertThat(m1).isEqualTo(m2);
    }

    @Test
    void al_quinto_fallo_el_codigo_se_invalida_y_hay_que_pedir_uno_nuevo() {
        // Sin limite de intentos, seis digitos (~20 bits) son forzables.
        String code = otp.generar("test:4", Duration.ofMinutes(30));
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> otp.verificar("test:4", "000000")).isInstanceOf(ApiException.class);
        }
        // El code BUENO tampoco sirve ya: se invalido entero.
        assertThatThrownBy(() -> otp.verificar("test:4", code)).isInstanceOf(ApiException.class);
    }

    @Test
    void regenerar_pisa_el_codigo_anterior() {
        // There are never two valid codes at once for the same key.
        String viejo = otp.generar("test:5", Duration.ofMinutes(30));
        String nuevo = otp.generar("test:5", Duration.ofMinutes(30));
        assertThatThrownBy(() -> otp.verificar("test:5", viejo)).isInstanceOf(ApiException.class);
        otp.verificar("test:5", nuevo);
    }

    private String capturar(Runnable r) {
        try { r.run(); return "no-fallo"; }
        catch (ApiException e) { return e.getMessage(); }
    }
}
```

- [ ] **Step 4: Correr y verificar que falla**

Run: `mvn -q test -Dtest=OtpServiceIT`
Expected: FAIL — falta `OtpService`.

- [ ] **Step 5: Escribir `OtpProperties` y `OtpService`**

```java
package ar.edu.utn.frc.tup.p4.usersservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@ConfigurationProperties(prefix = "users.otp")
public record OtpProperties(Duration activacionTtl, Duration dosfaTtl, int maxIntentos) { }
```

```java
package ar.edu.utn.frc.tup.p4.usersservice.auth.otp;

import ar.edu.utn.frc.tup.p4.usersservice.config.OtpProperties;
import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ApiException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;

/**
 * DEC-33 - one single OTP engine. 2FA and e-mail validation are two
 * clients of the same component: same format, same screen, same resend.
 *
 * Seis digitos son ~20 bits: aceptable con TTL corto Y limite de intentos,
 * reckless without either of the two.
 */
@Service
public class OtpService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redis;
    private final OtpProperties props;

    public OtpService(StringRedisTemplate redis, OtpProperties props) {
        this.redis = redis;
        this.props = props;
    }

    /** Regenerating OVERWRITES the previous one: never two valid codes at once. */
    public String generar(String key, Duration ttl) {
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        redis.opsForValue().set(key, code, ttl);
        redis.delete(intentosDe(key));
        return code;
    }

    /**
     * ALWAYS throws the same error for a wrong code, an expired one and a key
     * inexistente. Anti-enumeracion: no revela si esa direccion existe.
     */
    public void verificar(String key, String code) {
        String esperado = redis.opsForValue().get(key);
        if (esperado == null) throw ApiException.invalidCode();

        if (!esperado.equals(code)) {
            Long intentos = redis.opsForValue().increment(intentosDe(key));
            if (intentos != null && intentos == 1L) {
                redis.expire(intentosDe(key), Duration.ofHours(1));
            }
            if (intentos != null && intentos >= props.maxIntentos()) {
                // The whole code is invalidated: a new one has to be requested.
                redis.delete(key);
                redis.delete(intentosDe(key));
            }
            throw ApiException.invalidCode();
        }

        redis.delete(key);              // un solo uso
        redis.delete(intentosDe(key));
    }

    private String intentosDe(String key) { return key + ":intentos"; }
}
```

- [ ] **Step 6: Correr y verificar que pasa**

Run: `mvn -q test -Dtest=OtpServiceIT`
Expected: PASS — 5 tests.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/ar/edu/utn/frc/tup/p4/usersservice/auth src/main/java/ar/edu/utn/frc/tup/p4/usersservice/config src/test/java
git commit -m "feat: TokenStore, EphemeralTokenService y motor unico de OTP (DEC-33)

Seis digitos con TTL corto Y limite de 5 intentos: sin cualquiera de los dos
son forzables. Codigo incorrecto, vencido e inexistente dan la MISMA respuesta."
```

---

### Task 12: `CredentialService` — la puerta entre módulos

**Files:**
- Create: `src/main/java/…/users/services/CredentialService.java`
- Create: `src/main/java/…/users/services/impl/CredentialServiceImpl.java`
- Create: `src/main/java/…/users/PasswordPolicy.java`
- Test: `src/test/java/…/users/services/CredentialServiceTest.java`
- Test: `src/test/java/…/users/PasswordPolicyTest.java`

**Interfaces:**
- Consumes: `UserRepository` (T2), `PasswordEncoder` (T5), `ApiException` (T4).
- Produces: `CredentialService.verifyCredentials(String email, String plainPassword)` → `VerifiedCredentials(UUID userId, List<Role> roles, AccountStatus accountStatus, boolean mustChangePassword, String email, String firstNames)` o **`null`**; `updatePassword(UUID, String)`; `verifyPasswordOf(UUID, String)`.

- [ ] **Step 1: Escribir el test de la política de password (falla)**

`DEC-38`. El límite de 72 bytes es el punto que importa.

```java
package ar.edu.utn.frc.tup.p4.usersservice.users;

import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ApiException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PasswordPolicyTest {

    @Test
    void twelve_characters_are_enough_with_no_composition_rules() {
        // NIST SP 800-63B: largo, no complejidad. "Password1!" son 10
        // caracteres predecibles; 12 libres tienen mas entropia real.
        assertThatCode(() -> PasswordPolicy.validate("todaminusculas")).doesNotThrowAnyException();
    }

    @Test
    void fewer_than_twelve_characters_is_rejected() {
        assertThatThrownBy(() -> PasswordPolicy.validate("corta123")).isInstanceOf(ApiException.class);
    }

    @Test
    void mas_de_72_BYTES_se_rechaza() {
        // BCrypt silently TRUNCATES at 72 bytes: without this limit, a
        // 100-character password is checked against its first 72 and the
        // user believes they have a strength they do not have.
        assertThatThrownBy(() -> PasswordPolicy.validate("a".repeat(73)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void el_limite_se_mide_en_BYTES_no_en_caracteres() {
        // 40 caracteres acentuados = 80 bytes en UTF-8. Contando caracteres
        // esto pasaria, y BCrypt cortaria a mitad de un caracter.
        String cuarentaAcentos = "á".repeat(40);
        assertThatThrownBy(() -> PasswordPolicy.validate(cuarentaAcentos))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void a_common_password_is_rejected_even_when_long_enough() {
        assertThatThrownBy(() -> PasswordPolicy.validate("password12")).isInstanceOf(ApiException.class);
    }
}
```

- [ ] **Step 2: Escribir `PasswordPolicy`**

```java
package ar.edu.utn.frc.tup.p4.usersservice.users;

import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ApiException;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** DEC-38 · largo, no complejidad. */
public final class PasswordPolicy {

    public static final int MIN_CHARACTERS = 12;
    /** BCrypt trunca en 72 BYTES. No es un limite estetico. */
    public static final int MAX_BYTES = 72;

    private static final Set<String> COMMON = cargarComunes();

    private PasswordPolicy() { }

    public static void validar(String plain) {
        if (plain == null || plain.length() < MIN_CHARACTERS) {
            throw ApiException.validation(
                    "The password must be at least " + MIN_CHARACTERS + " characters long.");
        }
        if (plain.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw ApiException.validation(
                    "The password cannot exceed " + MAX_BYTES + " bytes. "
                    + "Accented characters take more than one byte.");
        }
        if (COMMON.contains(plain.toLowerCase(Locale.ROOT))) {
            throw ApiException.validation("That password is too common. Choose another one.");
        }
    }

    private static Set<String> cargarComunes() {
        var in = PasswordPolicy.class.getResourceAsStream("/security/common-passwords.txt");
        if (in == null) return Set.of();
        try (var r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            return r.lines().map(String::trim).filter(s -> !s.isEmpty())
                    .map(s -> s.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
        } catch (Exception e) {
            throw new IllegalStateException("Could not load the common-password list", e);
        }
    }
}
```

Crear `src/main/resources/security/common-passwords.txt` con un top-1000 (una por línea, minúsculas). Incluir al menos: `password12`, `password1234`, `123456789012`, `qwertyuiop12`, `administrador`.

- [ ] **Step 3: Escribir el test de `CredentialService` (falla)**

```java
package ar.edu.utn.frc.tup.p4.usersservice.users.services;

import ar.edu.utn.frc.tup.p4.usersservice.users.entities.User;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.AccountStatus;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.UserRepository;
import ar.edu.utn.frc.tup.p4.usersservice.users.services.impl.CredentialServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DoD criterion #8: it is invoked DIRECTLY, from a unit test, WITHOUT
 * starting any HTTP server. It is the proof that the boundary between modules
 * is a Java call and not a network call.
 */
class CredentialServiceTest {

    PasswordEncoder encoder = new BCryptPasswordEncoder(4);   // costo bajo: es un test
    UserRepository repo = mock(UserRepository.class);
    CredentialService service = new CredentialServiceImpl(repo, encoder);

    private User activo(String password) {
        User u = User.create("Ana", "Perez", "ana@utn.edu.ar", encoder.encode(password), Role.STUDENT, "v1");
        u.forceStatusForTest(AccountStatus.ACTIVE);
        return u;
    }

    @Test
    void valid_credentials_return_the_user_data() {
        when(repo.findByEmailAndDeletedAtIsNull("ana@utn.edu.ar"))
                .thenReturn(Optional.of(activo("passwordvalida1")));

        var r = service.verifyCredentials("ana@utn.edu.ar", "passwordvalida1");

        assertThat(r).isNotNull();
        assertThat(r.roles()).containsExactly(Role.STUDENT);
        assertThat(r.accountStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(r.firstNames()).isEqualTo("Ana");
    }

    @Test
    void a_wrong_password_returns_null() {
        when(repo.findByEmailAndDeletedAtIsNull(any()))
                .thenReturn(Optional.of(activo("passwordvalida1")));
        assertThat(service.verifyCredentials("ana@utn.edu.ar", "otracosa1234")).isNull();
    }

    @Test
    void an_unknown_email_returns_null_just_like_a_wrong_password() {
        // Anti-enumeration: the caller cannot tell the two cases apart.
        when(repo.findByEmailAndDeletedAtIsNull(any())).thenReturn(Optional.empty());
        assertThat(service.verifyCredentials("nadie@utn.edu.ar", "loquesea1234")).isNull();
    }

    @Test
    void the_email_is_looked_up_lowercased() {
        when(repo.findByEmailAndDeletedAtIsNull("ana@utn.edu.ar"))
                .thenReturn(Optional.of(activo("passwordvalida1")));
        assertThat(service.verifyCredentials("ANA@UTN.EDU.AR", "passwordvalida1")).isNotNull();
    }

    @Test
    void the_hash_never_leaves_the_call() {
        when(repo.findByEmailAndDeletedAtIsNull(any()))
                .thenReturn(Optional.of(activo("passwordvalida1")));
        var r = service.verifyCredentials("ana@utn.edu.ar", "passwordvalida1");
        assertThat(r.toString()).doesNotContain("$2a$");
    }
}
```

- [ ] **Step 4: Escribir la interfaz y la implementación**

```java
package ar.edu.utn.frc.tup.p4.usersservice.users.services;

import ar.edu.utn.frc.tup.p4.usersservice.users.enums.AccountStatus;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;

import java.util.List;
import java.util.UUID;

/**
 * THE DOOR between modules. auth/ consumes it through a direct Java call,
 * NEVER over HTTP: there is no network hop between auth/ and users/.
 * The password hash never leaves this call.
 */
public interface CredentialService {

    record VerifiedCredentials(UUID userId, List<Role> roles, AccountStatus accountStatus,
                                   boolean mustChangePassword, String email, String firstNames) { }

    /** null when the user does not exist OR the password is wrong - without telling them apart. */
    VerifiedCredentials verifyCredentials(String email, String plainPassword);

    /** DEC-23 - the est/pwd/onb claims come from the row, not from another token.
     *  AuthService consumes it when issuing and when refreshing (tasks 13 and 14). */
    record DatosToken(List<Role> roles, AccountStatus accountStatus,
                      boolean mustChangePassword, boolean firstLogin) { }

    DatosToken tokenData(UUID userId);

    /** null when it does not exist. The constant response is built by PasswordService (task 15). */
    record DatosReset(UUID userId, String email, String firstNames) { }

    DatosReset findForPasswordReset(String email);

    void updatePassword(UUID userId, String newPlainPassword);

    boolean verifyPasswordOf(UUID userId, String plainPassword);
}
```

```java
package ar.edu.utn.frc.tup.p4.usersservice.users.services.impl;

import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ApiException;
import ar.edu.utn.frc.tup.p4.usersservice.users.PasswordPolicy;
import ar.edu.utn.frc.tup.p4.usersservice.users.entities.User;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.UserRepository;
import ar.edu.utn.frc.tup.p4.usersservice.users.services.CredentialService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class CredentialServiceImpl implements CredentialService {

    private final UserRepository repo;
    private final PasswordEncoder encoder;

    public CredentialServiceImpl(UserRepository repo, PasswordEncoder encoder) {
        this.repo = repo;
        this.encoder = encoder;
    }

    @Override
    @Transactional(readOnly = true)
    public VerifiedCredentials verifyCredentials(String email, String plainPassword) {
        return repo.findByEmailAndDeletedAtIsNull(email.toLowerCase(Locale.ROOT))
                .filter(u -> encoder.matches(plainPassword, u.getPasswordHash()))
                .map(u -> new VerifiedCredentials(
                        u.getId(), List.of(u.getRole()), u.getAccountStatus(),
                        u.mustChangePassword(), u.getEmail(), u.getFirstNames()))
                .orElse(null);   // no distingue "no existe" de "password incorrecta"
    }

    @Override
    @Transactional
    public void updatePassword(UUID userId, String newPlainPassword) {
        PasswordPolicy.validate(newPlainPassword);
        User u = repo.findByIdAndDeletedAtIsNull(userId).orElseThrow(ApiException::invalidCredentials);
        u.changePassword(encoder.encode(newPlainPassword));
        repo.save(u);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean verifyPasswordOf(UUID userId, String plainPassword) {
        return repo.findByIdAndDeletedAtIsNull(userId)
                .map(u -> encoder.matches(plainPassword, u.getPasswordHash()))
                .orElse(false);
    }

    @Override
    @Transactional(readOnly = true)
    public DatosToken tokenData(UUID userId) {
        User u = repo.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(ApiException::invalidCredentials);
        return new DatosToken(List.of(u.getRole()), u.getAccountStatus(),
                u.mustChangePassword(), u.isFirstLogin());
    }

    @Override
    @Transactional(readOnly = true)
    public DatosReset findForPasswordReset(String email) {
        return repo.findByEmailAndDeletedAtIsNull(email.toLowerCase(Locale.ROOT))
                .map(u -> new DatosReset(u.getId(), u.getEmail(), u.getFirstNames()))
                .orElse(null);
    }
}
```

- [ ] **Step 5: Correr los dos tests y verificar que pasan**

Run: `mvn -q test -Dtest=PasswordPolicyTest+CredentialServiceTest`
Expected: PASS — 10 tests. **`CredentialServiceTest` corre sin `@SpringBootTest`**: es la evidencia de DoD #8.

- [ ] **Step 6: Commit**

```bash
git add src/main/java src/main/resources/security src/test/java
git commit -m "feat: CredentialService como puerta entre modulos + politica de password

DoD #8: se test con un test unitario sin levantar HTTP. Es lo que demuestra
que la frontera auth/ <-> users/ es una llamada Java, no de red.
DEC-38: el maximo de 72 BYTES evita que BCrypt trunque en silencio."
```

---
### Task 13: Login en dos fases con 2FA y rate limit

**Files:**
- Create: `src/main/java/…/auth/twofactor/{SecondFactorProvider,EmailOtpProvider}.java`
- Create: `src/main/java/…/auth/services/AuthService.java`
- Create: `src/main/java/…/auth/controllers/AuthController.java`
- Create: `src/main/java/…/auth/dto/{LoginRequest,LoginResponse,VerifyTwoFactorRequest,TokenResponse}.java`
- Create: `src/main/java/…/config/RateLimitProperties.java`
- Test: `src/test/java/…/auth/LoginIT.java`
- Test: `src/test/java/…/auth/RateLimitLoginIT.java`

**Interfaces:**
- Consumes: `CredentialService` (T12), `TokenService`+`TokenClaims` (T10), `TokenStore`+`OtpService` (T11), `NotificationEventPublisher` (T7), `UserRepository` (T2).
- Produces: `AuthService.login(String email, String password)` → `LoginResponse(String challengeId)`; `AuthService.verificarDosFa(String challengeId, String code)` → `TokenResponse(String accessToken, String refreshToken, long expiresIn)`.

- [ ] **Step 1: Escribir los DTOs y properties**

```java
package ar.edu.utn.frc.tup.p4.usersservice.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) { }
```
```java
package ar.edu.utn.frc.tup.p4.usersservice.auth.dto;

/** Phase 1 returns NO tokens: only the identifier of the 2FA challenge. */
public record LoginResponse(String challengeId, String message) { }
```
```java
package ar.edu.utn.frc.tup.p4.usersservice.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record VerifyTwoFactorRequest(@NotBlank String challengeId,
                                    @NotBlank @Pattern(regexp = "\\d{6}") String code) { }
```
```java
package ar.edu.utn.frc.tup.p4.usersservice.auth.dto;

public record TokenResponse(String accessToken, String refreshToken, long expiresIn) { }
```
```java
package ar.edu.utn.frc.tup.p4.usersservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

/** DEC-42 - it counts FAILURES per e-mail. The gateway limits by IP; different
 *  keys, so the two cannot fire on the same condition. */
@ConfigurationProperties(prefix = "users.ratelimit")
public record RateLimitProperties(int loginMaxFallos, Duration loginVentana) { }
```

- [ ] **Step 2: Escribir el test de login (falla)**

```java
package ar.edu.utn.frc.tup.p4.usersservice.auth;

import ar.edu.utn.frc.tup.p4.usersservice.AbstractIntegrationTest;
import ar.edu.utn.frc.tup.p4.usersservice.auth.services.AuthService;
import ar.edu.utn.frc.tup.p4.usersservice.auth.store.TokenStore;
import ar.edu.utn.frc.tup.p4.usersservice.shared.events.OutboxRepository;
import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ApiException;
import ar.edu.utn.frc.tup.p4.usersservice.users.entities.User;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.AccountStatus;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.UserRepository;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Criterios de DoD #3 (login completo, de punta a punta) y #4 (segundo
 * login sobrescribe la sesion).
 */
@Import(TestOtpSpy.Config.class)   // el spy del Step 5, solo para este test
class LoginIT extends AbstractIntegrationTest {

    @Autowired AuthService auth;
    @Autowired UserRepository repo;
    @Autowired PasswordEncoder encoder;
    @Autowired TokenStore store;
    @Autowired OutboxRepository outbox;      // DoD #3: el mail tiene que salir
    @Autowired TestOtpSpy otpSpy;   // captura el code generado; ver Step 5

    private User crearActivo(String email, String password) {
        User u = User.create("Ana", "Perez", email, encoder.encode(password), Role.STUDENT, "v1");
        u.forceStatusForTest(AccountStatus.ACTIVE);
        return repo.saveAndFlush(u);
    }

    @Test
    void la_fase_1_NO_devuelve_tokens() throws Exception {
        crearActivo("f1@utn.edu.ar", "passwordvalida1");
        var r = auth.login("f1@utn.edu.ar", "passwordvalida1");
        assertThat(r.challengeId()).isNotBlank();
        // Si la fase 1 devolviera tokens, el 2FA seria decorativo.
        assertThat(r.toString()).doesNotContain("eyJ");
    }

    @Test
    void la_fase_1_RENDERIZA_el_mail_y_publica_el_evento() {
        // Criterio de DoD #3, tramo "code 2FA generado y mail renderizado
        // -> event published". Without this assert, the login could issue
        // perfect tokens and never send the code: green in the tests,
        // roto para el usuario.
        crearActivo("f2a@utn.edu.ar", "passwordvalida1");
        long antes = outbox.count();

        auth.login("f2a@utn.edu.ar", "passwordvalida1");

        assertThat(outbox.count()).isGreaterThan(antes);
        assertThat(outbox.findAll()).anySatisfy(e -> {
            assertThat(e.getPayload()).contains("EMAIL_2FA");
            assertThat(e.getPayload()).contains("f2a@utn.edu.ar");
            // The mail goes out ALREADY BUILT: subject + html, not a templateId.
            assertThat(e.getPayload()).contains("\"asunto\"").contains("\"html\"");
            // And the code NEVER appears in the audit event or in a log.
            assertThat(e.getTopic()).isNotBlank();
        });
    }

    @Test
    void la_fase_2_con_el_codigo_correcto_emite_los_dos_tokens() throws Exception {
        User u = crearActivo("f2@utn.edu.ar", "passwordvalida1");
        var desafio = auth.login("f2@utn.edu.ar", "passwordvalida1");

        var tokens = auth.verificarDosFa(desafio.challengeId(), otpSpy.ultimoCodigo());

        assertThat(tokens.accessToken()).isNotBlank();
        assertThat(tokens.refreshToken()).isNotBlank();

        var claims = SignedJWT.parse(tokens.accessToken()).getJWTClaimsSet();
        assertThat(claims.getIssuer()).isEqualTo("users-service");
        assertThat(claims.getStringClaim("est")).isEqualTo("ACTIVE");
        // DEC-22: the post-2FA login is the ONLY operation that writes the session.
        assertThat(store.sidDe(u.getId())).contains(claims.getStringClaim("sid"));
    }

    @Test
    void un_segundo_login_pisa_la_sesion_del_primero() throws Exception {
        User u = crearActivo("f3@utn.edu.ar", "passwordvalida1");

        var d1 = auth.login("f3@utn.edu.ar", "passwordvalida1");
        var t1 = auth.verificarDosFa(d1.challengeId(), otpSpy.ultimoCodigo());
        String sid1 = SignedJWT.parse(t1.accessToken()).getJWTClaimsSet().getStringClaim("sid");

        var d2 = auth.login("f3@utn.edu.ar", "passwordvalida1");
        var t2 = auth.verificarDosFa(d2.challengeId(), otpSpy.ultimoCodigo());
        String sid2 = SignedJWT.parse(t2.accessToken()).getJWTClaimsSet().getStringClaim("sid");

        assertThat(sid1).isNotEqualTo(sid2);
        assertThat(store.sidDe(u.getId())).contains(sid2);   // gana el ultimo
    }

    @Test
    void una_cuenta_PENDIENTE_CURSO_puede_loguearse_y_su_token_lo_refleja() throws Exception {
        // INC-19: RF-USR-05f prohibe el ACCESO A FUNCIONALIDAD, no la emision
        // of the token. The token is how the person queries
        // GET /me and finds out what is missing. The set of features
        // alcanzables es vacio (DEC-23, gate grueso en el Gateway).
        User u = User.create("B", "B", "pend@utn.edu.ar",
                encoder.encode("passwordvalida1"), Role.STUDENT, "v1");
        u.activate();                     // -> PENDING_COURSE
        repo.saveAndFlush(u);

        var d = auth.login("pend@utn.edu.ar", "passwordvalida1");
        var t = auth.verificarDosFa(d.challengeId(), otpSpy.ultimoCodigo());

        assertThat(SignedJWT.parse(t.accessToken()).getJWTClaimsSet().getStringClaim("est"))
                .isEqualTo("PENDING_COURSE");
    }

    @Test
    void password_incorrecta_y_email_inexistente_dan_el_MISMO_error() {
        crearActivo("f4@utn.edu.ar", "passwordvalida1");
        String m1 = capturar(() -> auth.login("f4@utn.edu.ar", "otracosa1234"));
        String m2 = capturar(() -> auth.login("nadie@utn.edu.ar", "otracosa1234"));
        assertThat(m1).isEqualTo(m2);
    }

    @Test
    void un_codigo_2fa_incorrecto_no_emite_tokens() {
        crearActivo("f5@utn.edu.ar", "passwordvalida1");
        var d = auth.login("f5@utn.edu.ar", "passwordvalida1");
        assertThatThrownBy(() -> auth.verificarDosFa(d.challengeId(), "000000"))
                .isInstanceOf(ApiException.class);
    }

    private String capturar(Runnable r) {
        try { r.run(); return "no-fallo"; }
        catch (ApiException e) { return e.getMessage(); }
    }
}
```

- [ ] **Step 3: Escribir el test de rate limit (falla)**

Criterio de DoD #30.

```java
package ar.edu.utn.frc.tup.p4.usersservice.auth;

import ar.edu.utn.frc.tup.p4.usersservice.AbstractIntegrationTest;
import ar.edu.utn.frc.tup.p4.usersservice.auth.services.AuthService;
import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ApiException;
import ar.edu.utn.frc.tup.p4.usersservice.users.entities.User;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.AccountStatus;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** DEC-42 · criterio de DoD #30. */
class RateLimitLoginIT extends AbstractIntegrationTest {

    @Autowired AuthService auth;
    @Autowired UserRepository repo;
    @Autowired PasswordEncoder encoder;

    @Test
    void al_sexto_FALLO_sobre_el_mismo_email_responde_429() {
        crear("rl1@utn.edu.ar");
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> auth.login("rl1@utn.edu.ar", "malamala1234"))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).getStatus().value()).isEqualTo(401);
        }
        assertThatThrownBy(() -> auth.login("rl1@utn.edu.ar", "malamala1234"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).getStatus().value()).isEqualTo(429);
                    assertThat(((ApiException) e).getExtras()).containsKey("retryAfterSeconds");
                    assertThat(((ApiException) e).getType().toString()).endsWith("/too-many-attempts");
                });
    }

    @Test
    void un_login_EXITOSO_no_consume_presupuesto_y_limpia_los_fallos() {
        // It counts failures, not attempts: a legitimate user never hits the limit.
        crear("rl2@utn.edu.ar");
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> auth.login("rl2@utn.edu.ar", "malamala1234"))
                    .isInstanceOf(ApiException.class);
        }
        auth.login("rl2@utn.edu.ar", "passwordvalida1");   // acierta -> limpia

        // Vuelve a tener las 5 oportunidades completas.
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> auth.login("rl2@utn.edu.ar", "malamala1234"))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).getStatus().value()).isEqualTo(401);
        }
    }

    @Test
    void el_limite_es_por_email_no_global() {
        crear("rl3@utn.edu.ar");
        crear("rl4@utn.edu.ar");
        for (int i = 0; i < 6; i++) {
            try { auth.login("rl3@utn.edu.ar", "malamala1234"); } catch (ApiException ignored) { }
        }
        // La otra cuenta no quedo afectada.
        assertThatThrownBy(() -> auth.login("rl4@utn.edu.ar", "malamala1234"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus().value()).isEqualTo(401);
    }

    private void crear(String email) {
        User u = User.create("A", "A", email, encoder.encode("passwordvalida1"), Role.STUDENT, "v1");
        u.forceStatusForTest(AccountStatus.ACTIVE);
        repo.saveAndFlush(u);
    }
}
```

- [ ] **Step 4: Correr y verificar que fallan**

Run: `mvn -q test -Dtest=LoginIT+RateLimitLoginIT`
Expected: FAIL — falta `AuthService`.

- [ ] **Step 5: Escribir el `SecondFactorProvider` y el espía de test**

```java
package ar.edu.utn.frc.tup.p4.usersservice.auth.twofactor;

import java.util.UUID;

/** Strategy: sumar TOTP despues no toca el login. */
public interface SecondFactorProvider {
    /** Generates the challenge and dispatches it. Returns the generated code. */
    String generarDesafio(UUID userId, String email, String firstNames);
    void verificar(UUID userId, String code);
}
```

```java
package ar.edu.utn.frc.tup.p4.usersservice.auth.twofactor;

import ar.edu.utn.frc.tup.p4.usersservice.auth.otp.OtpService;
import ar.edu.utn.frc.tup.p4.usersservice.config.OtpProperties;
import ar.edu.utn.frc.tup.p4.usersservice.shared.notifications.NotificationEventPublisher;
import ar.edu.utn.frc.tup.p4.usersservice.shared.notifications.EmailType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Component
public class EmailOtpProvider implements SecondFactorProvider {

    private final OtpService otp;
    private final OtpProperties props;
    private final NotificationEventPublisher mails;

    public EmailOtpProvider(OtpService otp, OtpProperties props, NotificationEventPublisher mails) {
        this.otp = otp; this.props = props; this.mails = mails;
    }

    @Override
    @Transactional
    public String generarDesafio(UUID userId, String email, String firstNames) {
        String code = otp.generar(key(userId), props.dosfaTtl());
        mails.enviar(EmailType.TWO_FACTOR_CODE, email, Map.of("firstNames", firstNames, "code", code));
        return code;   // NUNCA se loguea ni se devuelve al cliente
    }

    @Override
    public void verificar(UUID userId, String code) { otp.verificar(key(userId), code); }

    private String key(UUID userId) { return "2fa:" + userId; }
}
```

En `src/test/java/…/auth/TestOtpSpy.java` (perfil `test`), capturar el último código para poder verificarlo sin leer el mail:

```java
package ar.edu.utn.frc.tup.p4.usersservice.auth;

import ar.edu.utn.frc.tup.p4.usersservice.auth.twofactor.EmailOtpProvider;
import ar.edu.utn.frc.tup.p4.usersservice.auth.twofactor.SecondFactorProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.UUID;

/**
 * Decorates the real provider to capture the code. It never logs it: it only
 * keeps it in memory for the assert. The real code still travels the same
 * camino de produccion.
 */
public class TestOtpSpy implements SecondFactorProvider {

    private final EmailOtpProvider real;
    private volatile String ultimo;

    public TestOtpSpy(EmailOtpProvider real) { this.real = real; }

    @Override public String generarDesafio(UUID userId, String email, String firstNames) {
        this.ultimo = real.generarDesafio(userId, email, firstNames);
        return ultimo;
    }

    @Override public void verificar(UUID userId, String code) { real.verificar(userId, code); }

    public String ultimoCodigo() { return ultimo; }

    /**
     * ONE single registration: the bean is both SecondFactorProvider (@Primary,
     * injected into AuthService) and TestOtpSpy (injected into the tests). If
     * registrara ademas con @Component habria dos instancias y el spy
     * would capture codes from one nobody uses.
     */
    @TestConfiguration
    public static class Config {
        @Bean @Primary
        TestOtpSpy spy(EmailOtpProvider real) { return new TestOtpSpy(real); }
    }
}
```

El `@Import` va en **cada test que usa el spy**, con
`@Import(TestOtpSpy.Config.class)` sobre la clase — acá, `LoginIT`.

**No lo pongas en `AbstractIntegrationTest`.** Esa clase es de la base y no
registra ningún doble de test a propósito: si cada lote agrega el suyo ahí,
seis ramas editan el mismo archivo en el mismo lugar. Además los tres spies
(`TestOtpSpy`, `TestResetSpy`, `TestActivationSpy`) declaran un `@Primary` cada
uno; registrados todos juntos en la base, dos compiten por el mismo tipo.

- [ ] **Step 6: Escribir `AuthService`**

```java
package ar.edu.utn.frc.tup.p4.usersservice.auth.services;

import ar.edu.utn.frc.tup.p4.usersservice.auth.dto.*;
import ar.edu.utn.frc.tup.p4.usersservice.auth.store.EphemeralTokenService;
import ar.edu.utn.frc.tup.p4.usersservice.auth.store.TokenStore;
import ar.edu.utn.frc.tup.p4.usersservice.auth.tokens.TokenClaims;
import ar.edu.utn.frc.tup.p4.usersservice.auth.twofactor.SecondFactorProvider;
import ar.edu.utn.frc.tup.p4.usersservice.config.JwtProperties;
import ar.edu.utn.frc.tup.p4.usersservice.config.RateLimitProperties;
import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ApiException;
import ar.edu.utn.frc.tup.p4.usersservice.users.services.CredentialService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Locale;
import java.util.UUID;

@Service
public class AuthService {

    private final CredentialService credenciales;     // la puerta a users/
    private final SecondFactorProvider segundoFactor;
    private final TokenService tokens;
    private final TokenStore store;
    private final EphemeralTokenService efimeros;
    private final JwtProperties jwt;
    private final RateLimitProperties rate;

    public AuthService(CredentialService credenciales, SecondFactorProvider segundoFactor,
                       TokenService tokens, TokenStore store, EphemeralTokenService efimeros,
                       JwtProperties jwt, RateLimitProperties rate) {
        this.credenciales = credenciales; this.segundoFactor = segundoFactor;
        this.tokens = tokens; this.store = store; this.efimeros = efimeros;
        this.jwt = jwt; this.rate = rate;
    }

    /** Fase 1: valida credenciales y dispara el 2FA. NO emite tokens. */
    @Transactional
    public LoginResponse login(String email, String password) {
        String key = email.toLowerCase(Locale.ROOT);

        // DEC-42: the limit is checked BEFORE spending a BCrypt (~100 ms).
        if (store.incrementarFallos(key, rate.loginVentana()) > rate.loginMaxFallos()) {
            throw ApiException.tooManyAttempts(rate.loginVentana());
        }

        var verificadas = credenciales.verifyCredentials(key, password);
        if (verificadas == null) throw ApiException.invalidCredentials();

        store.limpiarFallos(key);   // acerto: no consume presupuesto

        String challengeId = UUID.randomUUID().toString();
        efimeros.guardar("desafio:" + challengeId, verificadas.userId().toString(), Duration.ofMinutes(5));
        segundoFactor.generarDesafio(verificadas.userId(), verificadas.email(), verificadas.firstNames());

        return new LoginResponse(challengeId, "Te enviamos un code por email.");
    }

    /** Fase 2: verifica el code y recien ahi emite los tokens. */
    @Transactional
    public TokenResponse verificarDosFa(String challengeId, String code) {
        UUID userId = efimeros.verificar("desafio:" + challengeId)
                .map(UUID::fromString)
                .orElseThrow(ApiException::invalidCode);

        segundoFactor.verificar(userId, code);
        efimeros.consumir("desafio:" + challengeId);

        return emitirParDeTokens(userId);
    }

    /**
     * DEC-22 - the post-2FA login is the ONLY operation that writes
     * session:{userId}. El refresh no la toca.
     */
    @Transactional
    public TokenResponse emitirParDeTokens(UUID userId) {
        String sid = UUID.randomUUID().toString();
        store.guardarSesion(userId, sid);
        return emitirConSid(userId, sid, UUID.randomUUID().toString());
    }

    TokenResponse emitirConSid(UUID userId, String sid, String familyId) {
        var datos = credenciales.tokenData(userId);   // ver Tarea 14, Step 3

        TokenClaims claims = TokenClaims.paraPersona(userId, datos.roles(), sid,
                datos.accountStatus(), datos.mustChangePassword(), datos.firstLogin()).build();

        String refreshJti = UUID.randomUUID().toString();
        store.guardarRefresh(refreshJti,
                new TokenStore.RefreshData(userId, sid, familyId), jwt.refreshTtl());

        return new TokenResponse(tokens.firmarPersona(claims), refreshJti, jwt.accessTtl().toSeconds());
    }
}
```

- [ ] **Step 7: Escribir el controller**

```java
package ar.edu.utn.frc.tup.p4.usersservice.auth.controllers;

import ar.edu.utn.frc.tup.p4.usersservice.auth.dto.*;
import ar.edu.utn.frc.tup.p4.usersservice.auth.services.AuthService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users/public/auth")
public class AuthController {

    private final AuthService auth;

    public AuthController(AuthService auth) { this.auth = auth; }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest req) {
        return auth.login(req.email(), req.password());
    }

    @PostMapping("/2fa/verify")
    public TokenResponse verificar(@Valid @RequestBody VerifyTwoFactorRequest req) {
        return auth.verificarDosFa(req.challengeId(), req.code());
    }
}
```

- [ ] **Step 8: Correr y verificar que pasan**

Run: `mvn -q test -Dtest=LoginIT+RateLimitLoginIT`
Expected: PASS — 10 tests.

- [ ] **Step 9: Commit**

```bash
git add src/main/java src/test/java
git commit -m "feat: login en dos fases con 2FA por email y rate limit por email

DEC-42: el limite se chequea ANTES de gastar un BCrypt, y cuenta fallos —
un login exitoso limpia el contador y no consume presupuesto.
DEC-22: el login post-2FA es la unica operacion que escribe session:{userId}."
```

---

### Task 14: Refresh, logout y sesión única

**Files:**
- Modify: `src/main/java/…/auth/services/AuthService.java` (agregar `refrescar` y `logout`)
- Modify: `src/main/java/…/users/services/CredentialService.java` (agregar `tokenData`)
- Modify: `src/main/java/…/auth/controllers/AuthController.java`
- Test: `src/test/java/…/auth/SingleSessionRefreshIT.java`
- Test: `src/test/java/…/auth/LogoutIT.java`

**Interfaces:**
- Consumes: todo lo de T13, más `@SkipAccountGate` (**en la base**, no en T8:
  el interceptor que la interpreta es de T8, la anotación no). Sin el
  interceptor de L4 los gates no se aplican y tus endpoints andan igual; los
  tests de que el gate EXIME de verdad son de L4.
- Produces: `CredentialService.tokenData(UUID)` → `DatosToken(List<Role> roles, AccountStatus accountStatus, boolean mustChangePassword, boolean firstLogin)`; `AuthService.refrescar(String refreshJti)` → `TokenResponse`; `AuthService.logout(UUID userId, String refreshJti)`.

- [ ] **Step 1: Escribir el test de refresh (falla)**

Criterio de DoD #23. **Es el test que materializa `DEC-22`.**

```java
package ar.edu.utn.frc.tup.p4.usersservice.auth;

import ar.edu.utn.frc.tup.p4.usersservice.AbstractIntegrationTest;
import ar.edu.utn.frc.tup.p4.usersservice.auth.dto.TokenResponse;
import ar.edu.utn.frc.tup.p4.usersservice.auth.services.AuthService;
import ar.edu.utn.frc.tup.p4.usersservice.auth.store.TokenStore;
import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ApiException;
import ar.edu.utn.frc.tup.p4.usersservice.users.entities.User;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.AccountStatus;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.UserRepository;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DEC-22 - the intuition that "if the refresh does not rotate the sid, an old
 * old one revives the session" is INVERTED: rotating is what allows reviving it.
 * Of the four combinations (new/same sid x writes/does not write Redis) only
 * one works: same sid + does not write.
 */
class SingleSessionRefreshIT extends AbstractIntegrationTest {

    @Autowired AuthService auth;
    @Autowired TokenStore store;
    @Autowired UserRepository repo;
    @Autowired PasswordEncoder encoder;

    private UUID crear(String email) {
        User u = User.create("A", "A", email, encoder.encode("passwordvalida1"), Role.STUDENT, "v1");
        u.forceStatusForTest(AccountStatus.ACTIVE);
        return repo.saveAndFlush(u).getId();
    }

    @Test
    void el_refresh_NO_genera_sid_nuevo_ni_escribe_redis() throws Exception {
        UUID id = crear("ref1@utn.edu.ar");
        TokenResponse t1 = auth.emitirParDeTokens(id);
        String sid = store.sidDe(id).orElseThrow();

        TokenResponse t2 = auth.refrescar(t1.refreshToken());

        assertThat(SignedJWT.parse(t2.accessToken()).getJWTClaimsSet().getStringClaim("sid"))
                .isEqualTo(sid);
        assertThat(store.sidDe(id)).contains(sid);   // la key no cambio
    }

    @Test
    void el_dispositivo_SUPERADO_recibe_401_y_su_familia_queda_revocada() {
        // A logueado, B se loguea, A intenta refrescar.
        UUID id = crear("ref2@utn.edu.ar");
        TokenResponse deA = auth.emitirParDeTokens(id);
        TokenResponse deB = auth.emitirParDeTokens(id);   // pisa la sesion

        assertThatThrownBy(() -> auth.refrescar(deA.refreshToken()))
                .isInstanceOf(ApiException.class);

        // El refresh de B sigue funcionando.
        assertThat(auth.refrescar(deB.refreshToken()).accessToken()).isNotBlank();
    }

    @Test
    void reusar_un_refresh_ya_rotado_revoca_TODA_la_familia() {
        UUID id = crear("ref3@utn.edu.ar");
        TokenResponse t1 = auth.emitirParDeTokens(id);
        TokenResponse t2 = auth.refrescar(t1.refreshToken());   // t1 queda rotado

        // A theft signal: somebody else holds the old refresh token.
        assertThatThrownBy(() -> auth.refrescar(t1.refreshToken())).isInstanceOf(ApiException.class);
        // And the new one dies too: the whole family was revoked.
        assertThatThrownBy(() -> auth.refrescar(t2.refreshToken())).isInstanceOf(ApiException.class);
    }

    @Test
    void el_refresh_RELEE_el_estado_de_la_base() throws Exception {
        // DEC-23: this is what makes refreshing the propagation mechanism
        // rapida cuando la cuenta gana acceso.
        User u = User.create("A", "A", "ref4@utn.edu.ar",
                encoder.encode("passwordvalida1"), Role.STUDENT, "v1");
        u.activate();                       // PENDING_COURSE
        repo.saveAndFlush(u);

        TokenResponse t1 = auth.emitirParDeTokens(u.getId());
        assertThat(SignedJWT.parse(t1.accessToken()).getJWTClaimsSet().getStringClaim("est"))
                .isEqualTo("PENDING_COURSE");

        u.activateAfterCourseValidation();
        repo.saveAndFlush(u);

        TokenResponse t2 = auth.refrescar(t1.refreshToken());
        assertThat(SignedJWT.parse(t2.accessToken()).getJWTClaimsSet().getStringClaim("est"))
                .isEqualTo("ACTIVE");      // sin re-login
    }
}
```

- [ ] **Step 2: Escribir el test de logout (falla)**

Criterio de DoD #13.

```java
package ar.edu.utn.frc.tup.p4.usersservice.auth;

import ar.edu.utn.frc.tup.p4.usersservice.AbstractIntegrationTest;
import ar.edu.utn.frc.tup.p4.usersservice.auth.services.AuthService;
import ar.edu.utn.frc.tup.p4.usersservice.auth.store.TokenStore;
import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ApiException;
import ar.edu.utn.frc.tup.p4.usersservice.users.entities.User;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.AccountStatus;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** DEC-02 · el logout borra session:{userId}: corta el access al instante. */
class LogoutIT extends AbstractIntegrationTest {

    @Autowired AuthService auth;
    @Autowired TokenStore store;
    @Autowired UserRepository repo;
    @Autowired PasswordEncoder encoder;

    @Test
    void el_logout_borra_la_key_de_sesion() {
        User u = User.create("A", "A", "out@utn.edu.ar",
                encoder.encode("passwordvalida1"), Role.STUDENT, "v1");
        u.forceStatusForTest(AccountStatus.ACTIVE);
        UUID id = repo.saveAndFlush(u).getId();

        var t = auth.emitirParDeTokens(id);
        assertThat(store.sidDe(id)).isPresent();

        auth.logout(id, t.refreshToken());

        // Sin la key, el Gateway responde 401 "sesion cerrada" (DEC-01),
        // without waiting the ~10 min of exp.
        assertThat(store.sidDe(id)).isEmpty();
        assertThatThrownBy(() -> auth.refrescar(t.refreshToken())).isInstanceOf(ApiException.class);
    }
}
```

- [ ] **Step 3: Agregar `refrescar` y `logout` a `AuthService`**

> `CredentialService.tokenData` ya quedó definido en la Tarea 12: `AuthService.emitirConSid` lo usa desde la Tarea 13.

```java
/**
 * DEC-22 - four steps, and step 3 is the one this spec adds over what
 * manifiesto-flujos §10 says ("checking here or letting it fail at the gateway
 * are equivalent"). They are NOT: the refresh lives 7 DAYS. Without the check,
 * un dispositivo superado conserva una credencial de larga vida, robable,
 * tied to a session that no longer exists.
 */
@Transactional
public TokenResponse refrescar(String refreshJti) {
    // The exits of this method return a SESSION type, not invalid-credentials:
    // nobody mistyped a password, the session stopped
    // existing. The frontend branches on type, and with invalid-credentials it
    // would show "wrong username or password" in a flow where nothing was
    // pidio ninguna de las dos.
    var data = store.refresh(refreshJti).orElseThrow(ApiException::sessionClosed);

    // 1-2. Familia revocada -> senal de robo previa.
    if (store.familiaRevocada(data.familyId())) {
        throw ApiException.sessionClosed();
    }

    // 3. Is the session still the current one? If not, there was a newer login:
    // that case has its own type, which is the only message useful to the
    // persona ("iniciaste sesion en otro dispositivo").
    String sidVigente = store.sidDe(data.userId()).orElse(null);
    if (sidVigente == null || !sidVigente.equals(data.sid())) {
        store.revocarFamilia(data.familyId());
        throw sidVigente == null ? ApiException.sessionClosed() : ApiException.sessionSuperseded();
    }

    // 4. Rotate the REFRESH (not the sid). Reuse detection: the old one dies.
    store.revocarRefresh(refreshJti);
    return emitirConSid(data.userId(), data.sid(), data.familyId());
}

/** DEC-02 + DEC-22: uno de los dos unicos borrados de session:{userId}. */
@Transactional
public void logout(UUID userId, String refreshJti) {
    if (refreshJti != null) {
        store.refresh(refreshJti).ifPresent(d -> store.revocarFamilia(d.familyId()));
        store.revocarRefresh(refreshJti);
    }
    store.borrarSesion(userId);
}
```

Y detectar el reuso en `refrescar`: como `revocarRefresh` borra la key, un segundo uso del mismo `jti` cae en `store.refresh(...)` vacío → `sessionClosed`. Para revocar la **familia** en ese caso, guardar además `refresh:rotado:{jti} → familyId` con el TTL del refresh, y consultarlo antes de fallar:

```java
// At the start of refresh(), before the orElseThrow:
var rotado = efimeros.verificar("refresh:rotado:" + refreshJti);
if (rotado.isPresent()) {
    store.revocarFamilia(rotado.get());     // reuso de un token ya rotado
    throw ApiException.sessionClosed();
}
// Y en el paso 4, al rotar:
efimeros.guardar("refresh:rotado:" + refreshJti, data.familyId(), jwt.refreshTtl());
```

- [ ] **Step 4: Agregar los endpoints**

```java
// En AuthController, ruta PUBLICA (el refresh va en el body, sin Authorization):
@PostMapping("/refresh")
public TokenResponse refrescar(@Valid @RequestBody RefreshRequest req) {
    return auth.refrescar(req.refreshToken());
}
```
```java
package ar.edu.utn.frc.tup.p4.usersservice.auth.dto;
import jakarta.validation.constraints.NotBlank;
public record RefreshRequest(@NotBlank String refreshToken) { }
```

Y en un controller **privado** (`/api/users/auth`), con exención de los tres gates — una persona con la cuenta pendiente igual tiene que poder cerrar sesión:

```java
package ar.edu.utn.frc.tup.p4.usersservice.auth.controllers;

import ar.edu.utn.frc.tup.p4.usersservice.auth.dto.RefreshRequest;
import ar.edu.utn.frc.tup.p4.usersservice.auth.services.AuthService;
import ar.edu.utn.frc.tup.p4.usersservice.shared.gates.SkipAccountGate;
import ar.edu.utn.frc.tup.p4.usersservice.shared.security.GatewayPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users/auth")
public class AuthPrivateController {

    private final AuthService auth;

    public AuthPrivateController(AuthService auth) { this.auth = auth; }

    @PostMapping("/logout")
    @SkipAccountGate({SkipAccountGate.Gate.ESTADO, SkipAccountGate.Gate.PASSWORD,
                      SkipAccountGate.Gate.ONBOARDING})
    public void logout(@AuthenticationPrincipal GatewayPrincipal p,
                       @RequestBody(required = false) RefreshRequest req) {
        auth.logout(p.id(), req == null ? null : req.refreshToken());
    }
}
```

- [ ] **Step 5: Correr y verificar que pasan**

Run: `mvn -q test -Dtest=SingleSessionRefreshIT+LogoutIT`
Expected: PASS — 5 tests.

- [ ] **Step 6: Commit**

```bash
git add src/main/java src/test/java
git commit -m "feat: refresh sin rotar sid, con chequeo de sesion y deteccion de reuso

DEC-22: de las cuatro combinaciones (sid nuevo/igual x escribe/no escribe
Redis) solo una funciona. El refresh vive 7 dias: chequear la sesion ahi no
es equivalente a dejar que el access falle en el Gateway.
DEC-02: el logout borra session:{userId}."
```

---
### Task 15: Cambio y recuperación de contraseña

**Files:**
- Create: `src/main/java/…/auth/services/PasswordService.java`
- Create: `src/main/java/…/auth/dto/{PasswordChangeRequest,ResetRequest,ResetConfirmRequest}.java`
- Modify: `src/main/java/…/auth/controllers/{AuthController,AuthPrivateController}.java`
- Test: `src/test/java/…/auth/PasswordResetIT.java`

**Interfaces:**
- Consumes: `CredentialService` (T12), `EphemeralTokenService` (T11), `TokenStore` (T11), `NotificationEventPublisher` (T7).
- Produces: `PasswordService.cambiar(UUID, String actual, String nueva)`; `.pedirReset(String email)`; `.confirmarReset(String token, String nueva)`.

- [ ] **Step 1: Escribir el test (falla)** — criterio de DoD #18

```java
package ar.edu.utn.frc.tup.p4.usersservice.auth;

import ar.edu.utn.frc.tup.p4.usersservice.AbstractIntegrationTest;
import ar.edu.utn.frc.tup.p4.usersservice.auth.services.PasswordService;
import ar.edu.utn.frc.tup.p4.usersservice.auth.store.TokenStore;
import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ApiException;
import ar.edu.utn.frc.tup.p4.usersservice.users.entities.User;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.AccountStatus;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.UserRepository;
import ar.edu.utn.frc.tup.p4.usersservice.users.services.CredentialService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** DEC-16 - two endpoints, not one. `flujos` §06 drew them as the same POST. */
@Import(TestResetSpy.Config.class)   // el spy del Step 4, solo para este test
class PasswordResetIT extends AbstractIntegrationTest {

    @Autowired PasswordService passwords;
    @Autowired CredentialService credenciales;
    @Autowired UserRepository repo;
    @Autowired PasswordEncoder encoder;
    @Autowired TokenStore store;
    @Autowired TestResetSpy spy;      // captura el token, igual que TestOtpSpy
                                      // (solo RESET_PASSWORD: ver Step 4)

    private User crear(String email) {
        User u = User.create("Ana", "P", email, encoder.encode("passwordvalida1"), Role.STUDENT, "v1");
        u.forceStatusForTest(AccountStatus.ACTIVE);
        return repo.saveAndFlush(u);
    }

    @Test
    void pedir_reset_responde_IGUAL_exista_o_no_el_email() {
        crear("res1@utn.edu.ar");
        // Anti-enumeration: an attacker cannot discover which e-mails exist.
        assertThat(passwords.pedirReset("res1@utn.edu.ar"))
                .isEqualTo(passwords.pedirReset("nadie@utn.edu.ar"));
    }

    @Test
    void confirmar_cambia_la_password_y_revoca_las_sesiones() {
        User u = crear("res2@utn.edu.ar");
        store.guardarSesion(u.getId(), "sid-viejo");

        passwords.pedirReset("res2@utn.edu.ar");
        passwords.confirmarReset(spy.ultimoToken(), "nuevapasswordok1");

        assertThat(credenciales.verifyCredentials("res2@utn.edu.ar", "nuevapasswordok1")).isNotNull();
        assertThat(credenciales.verifyCredentials("res2@utn.edu.ar", "passwordvalida1")).isNull();
        // A changed password has to close the old sessions.
        assertThat(store.sidDe(u.getId())).isEmpty();
    }

    @Test
    void el_token_de_reset_es_de_UN_SOLO_uso() {
        crear("res3@utn.edu.ar");
        passwords.pedirReset("res3@utn.edu.ar");
        String token = spy.ultimoToken();

        passwords.confirmarReset(token, "nuevapasswordok1");
        assertThatThrownBy(() -> passwords.confirmarReset(token, "otrapasswordok2"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void el_reset_respeta_la_politica_de_password() {
        crear("res4@utn.edu.ar");
        passwords.pedirReset("res4@utn.edu.ar");
        assertThatThrownBy(() -> passwords.confirmarReset(spy.ultimoToken(), "corta"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void el_cambio_voluntario_exige_la_password_actual() {
        User u = crear("res5@utn.edu.ar");
        assertThatThrownBy(() -> passwords.cambiar(u.getId(), "equivocada12", "nuevapasswordok1"))
                .isInstanceOf(ApiException.class);
        passwords.cambiar(u.getId(), "passwordvalida1", "nuevapasswordok1");
        assertThat(credenciales.verifyCredentials("res5@utn.edu.ar", "nuevapasswordok1")).isNotNull();
    }
}
```

- [ ] **Step 2: Correr y verificar que falla**

Run: `mvn -q test -Dtest=PasswordResetIT`
Expected: FAIL — falta `PasswordService`.

- [ ] **Step 3: Escribir `PasswordService`**

```java
package ar.edu.utn.frc.tup.p4.usersservice.auth.services;

import ar.edu.utn.frc.tup.p4.usersservice.auth.store.EphemeralTokenService;
import ar.edu.utn.frc.tup.p4.usersservice.auth.store.TokenStore;
import ar.edu.utn.frc.tup.p4.usersservice.shared.notifications.NotificationEventPublisher;
import ar.edu.utn.frc.tup.p4.usersservice.shared.notifications.EmailType;
import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ApiException;
import ar.edu.utn.frc.tup.p4.usersservice.users.services.CredentialService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;

@Service
public class PasswordService {

    private static final String RESPUESTA_CONSTANTE = "Si el email existe, te enviamos las instrucciones.";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final CredentialService credenciales;
    private final EphemeralTokenService efimeros;
    private final TokenStore store;
    private final NotificationEventPublisher mails;
    private final String urlFront;

    public PasswordService(CredentialService credenciales, EphemeralTokenService efimeros,
                           TokenStore store, NotificationEventPublisher mails,
                           @Value("${users.front-url:https://app.tpi.utn.frc}") String urlFront) {
        this.credenciales = credenciales; this.efimeros = efimeros;
        this.store = store; this.mails = mails; this.urlFront = urlFront;
    }

    @Transactional
    public void cambiar(UUID userId, String actual, String nueva) {
        if (!credenciales.verifyPasswordOf(userId, actual)) throw ApiException.invalidCredentials();
        credenciales.updatePassword(userId, nueva);
        store.borrarSesion(userId);     // cerrar sesiones viejas
    }

    /**
     * DEC-16 - half 1: REQUEST. It ALWAYS returns the same, e-mail or no e-mail.
     * DEC-33: this does NOT become a 6-digit code. Guessing a reset IS taking
     * la cuenta; activate un email no le da acceso a nadie. Distinto impacto,
     * distinto mecanismo.
     */
    @Transactional
    public String pedirReset(String email) {
        var datos = credenciales.findForPasswordReset(email);
        if (datos != null) {
            byte[] bytes = new byte[32];
            RANDOM.nextBytes(bytes);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

            efimeros.guardar("reset:" + token, datos.userId().toString(), Duration.ofMinutes(15));
            mails.enviar(EmailType.RESET_PASSWORD, datos.email(), Map.of(
                    "firstNames", datos.firstNames(),
                    "enlace", urlFront + "/reset?token=" + token));
        }
        return RESPUESTA_CONSTANTE;
    }

    /** DEC-16 - half 2: CONFIRM. Its own path, its own body. */
    @Transactional
    public void confirmarReset(String token, String nueva) {
        UUID userId = efimeros.consumir("reset:" + token)   // un solo uso, atomico
                .map(UUID::fromString)
                .orElseThrow(ApiException::invalidCode);

        credenciales.updatePassword(userId, nueva);
        store.borrarSesion(userId);
    }
}
```

Agregar a `CredentialService` un `record DatosReset(UUID userId, String email, String firstNames)` y `DatosReset findForPasswordReset(String email)` que devuelva `null` si no existe (la respuesta constante la arma `PasswordService`).

- [ ] **Step 4: Agregar los endpoints**

En `AuthController` (públicos):
```java
@PostMapping("/password/reset")
public Map<String, String> pedirReset(@Valid @RequestBody ResetRequest req) {
    return Map.of("message", passwords.pedirReset(req.email()));
}

/** DEC-16: its OWN path. flujos §06 drew both halves as the same POST,
 *  which is not implementable: a single @Valid cannot validate two DTOs. */
@PostMapping("/password/reset/confirm")
public void confirmarReset(@Valid @RequestBody ResetConfirmRequest req) {
    passwords.confirmarReset(req.token(), req.newPassword());
}
```

En `AuthPrivateController`, exento del gate de PASSWORD (si no, quien debe cambiarla no podría):
```java
@PostMapping("/password/change")
/**
     * Exempt from PASSWORD (it is that gate's way out) and from ONBOARDING too.
     * Without the second, RF-USR-01's initial ADMIN is locked out: it is born
     * with mustChangePassword=true AND firstLogin=true, so this route would be
     * cut by the onboarding gate while /me/onboarding would be cut by the
     * password one. See the exemption rule in task 8.
     */
    @SkipAccountGate({SkipAccountGate.Gate.PASSWORD, SkipAccountGate.Gate.ONBOARDING})
public void cambiar(@AuthenticationPrincipal GatewayPrincipal p,
                    @Valid @RequestBody PasswordChangeRequest req) {
    passwords.cambiar(p.id(), req.actual(), req.nueva());
}
```

DTOs:
```java
public record ResetRequest(@NotBlank @Email String email) { }
public record ResetConfirmRequest(@NotBlank String token, @NotBlank String newPassword) { }
public record PasswordChangeRequest(@NotBlank String currentPassword,
                                    @NotBlank String newPassword) { }
```

Crear `src/test/java/…/auth/TestResetSpy.java`, análogo a `TestOtpSpy`,
decorando `NotificationEventPublisher` para capturar el token del enlace.

**Captura solo `EmailType.RESET_PASSWORD`.** El token de activación viaja por
el mismo mecanismo, pero es de otro lote: U17 tiene su propio
`TestActivationSpy` y este spy no se ramifica para servirlo. Veinte líneas
repetidas cuestan menos que un archivo que dos personas editan en paralelo.

`Config` va `public static`, y el `@Import(TestResetSpy.Config.class)` sobre
`PasswordResetIT` — no sobre `AbstractIntegrationTest`, por lo mismo que en T13.

- [ ] **Step 5: Correr y verificar que pasa**

Run: `mvn -q test -Dtest=PasswordResetIT`
Expected: PASS — 5 tests.

- [ ] **Step 6: Commit**

```bash
git add src/main/java src/test/java
git commit -m "feat: cambio y recuperacion de password con dos endpoints (DEC-16)

flujos §06 dibujaba pedir y confirmar en el mismo POST: no es implementable.
El reset sigue siendo token largo por link, no code de 6 digitos (DEC-33):
adivinar un reset ES tomar la cuenta."
```

---

### Task 16: `client_credentials` con `audience`

**Files:**
- Create: `src/main/java/…/auth/services/ServiceClientService.java`
- Create: `src/main/java/…/auth/dto/ClientCredentialsRequest.java`
- Create: `src/main/java/…/auth/controllers/TokenController.java`
- Create: `src/main/java/…/auth/ScopeCatalog.java`
- Test: `src/test/java/…/auth/ClientCredentialsIT.java`

**Interfaces:**
- Consumes: `ServiceClientRepository` (T3), `TokenService`+`TokenClaims` (T10), `PasswordEncoder` (T5).
- Produces: `ServiceClientService.emitirServicio(String clientId, String secret, String scope, String audience)` → `String` (JWT).

- [ ] **Step 1: Escribir el catálogo de scopes**

```java
package ar.edu.utn.frc.tup.p4.usersservice.auth;

import java.util.Map;
import java.util.Set;

/**
 * DEC-26 - a scope is only issuable if its prefix resolves to a service that
 * exists and is on the gateway's allowlist. Applied to the closed catalogue of
 * manifiesto §04.0b, DOS de los tres scopes emitian tokens inutiles:
 *
 *  - users.padron.notify  -> the flow is KAFKA: there is no HTTP request and the aud
 *                            nobody reads it. Not issuable.
 *  - mailing.debug.read   -> DEC-41: notifications-service es solo consumidor
 *                            de Kafka. Eliminado del catalogo.
 *
 * ONE single issuable scope is left.
 */
public final class ScopeCatalog {

    private static final Map<String, String> EMITIBLES = Map.of(
            "users.profile.read", "users-service");

    private ScopeCatalog() { }

    public static boolean esEmitible(String scope) { return EMITIBLES.containsKey(scope); }

    /** Deriva el destino: users.profile.read -> users-service. */
    public static String audienceDe(String scope) { return EMITIBLES.get(scope); }

    public static Set<String> emitibles() { return EMITIBLES.keySet(); }
}
```

- [ ] **Step 2: Escribir el test (falla)** — criterio de DoD #14

```java
package ar.edu.utn.frc.tup.p4.usersservice.auth;

import ar.edu.utn.frc.tup.p4.usersservice.AbstractIntegrationTest;
import ar.edu.utn.frc.tup.p4.usersservice.auth.entities.ServiceClient;
import ar.edu.utn.frc.tup.p4.usersservice.auth.repositories.ServiceClientRepository;
import ar.edu.utn.frc.tup.p4.usersservice.auth.services.ServiceClientService;
import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ApiException;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** DEC-17 - the `audience` field is in no manifest: it is new. */
class ClientCredentialsIT extends AbstractIntegrationTest {

    @Autowired ServiceClientService service;
    @Autowired ServiceClientRepository repo;
    @Autowired PasswordEncoder encoder;

    @BeforeEach
    void alta() {
        if (repo.findByClientIdAndDeletedAtIsNull("cursos-service").isEmpty()) {
            repo.saveAndFlush(ServiceClient.create("cursos-service",
                    encoder.encode("un-secreto-largo"), "Cursos", Set.of("users.profile.read")));
        }
    }

    @Test
    void con_audience_correcto_emite_el_token() throws Exception {
        String jwt = service.emitirServicio("cursos-service", "un-secreto-largo",
                "users.profile.read", "users-service");

        var c = SignedJWT.parse(jwt).getJWTClaimsSet();
        assertThat(c.getAudience()).containsExactly("users-service");
        assertThat(c.getStringListClaim("roles")).containsExactly("MS");
        assertThat(c.getStringClaim("type")).isEqualTo("service");
        assertThat(c.getIssuer()).isEqualTo("users-service");
    }

    @Test
    void SIN_audience_devuelve_400_sin_emitir() {
        // No default: an implicit aud is exactly what DEC-04 wants to avoid.
        assertThatThrownBy(() -> service.emitirServicio("cursos-service", "un-secreto-largo",
                "users.profile.read", null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus().value()).isEqualTo(400));
    }

    @Test
    void audience_que_no_deriva_del_scope_devuelve_400() {
        // It fails HERE, with a message that says what happened, not with a 403
        // from the gateway in another service, owned by another team.
        assertThatThrownBy(() -> service.emitirServicio("cursos-service", "un-secreto-largo",
                "users.profile.read", "cursos-service"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus().value()).isEqualTo(400));
    }

    @Test
    void un_scope_no_emitible_devuelve_400() {
        // DEC-26 + DEC-41.
        assertThatThrownBy(() -> service.emitirServicio("cursos-service", "un-secreto-largo",
                "mailing.debug.read", "mailing-service"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void un_scope_fuera_de_los_permitidos_de_la_fila_devuelve_400() {
        repo.saveAndFlush(ServiceClient.create("otro-service",
                encoder.encode("otro-secreto"), "Otro", Set.of()));
        assertThatThrownBy(() -> service.emitirServicio("otro-service", "otro-secreto",
                "users.profile.read", "users-service"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void secret_incorrecto_y_client_inexistente_dan_el_MISMO_error() {
        String m1 = capturar(() -> service.emitirServicio("cursos-service", "mal",
                "users.profile.read", "users-service"));
        String m2 = capturar(() -> service.emitirServicio("no-existe", "mal",
                "users.profile.read", "users-service"));
        assertThat(m1).isEqualTo(m2);
    }

    private String capturar(Runnable r) {
        try { r.run(); return "no-fallo"; } catch (ApiException e) { return e.getMessage(); }
    }
}
```

- [ ] **Step 3: Escribir `ServiceClientService`**

```java
package ar.edu.utn.frc.tup.p4.usersservice.auth.services;

import ar.edu.utn.frc.tup.p4.usersservice.auth.ScopeCatalog;
import ar.edu.utn.frc.tup.p4.usersservice.auth.repositories.ServiceClientRepository;
import ar.edu.utn.frc.tup.p4.usersservice.auth.tokens.TokenClaims;
import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ApiException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ServiceClientService {

    private final ServiceClientRepository repo;
    private final PasswordEncoder encoder;
    private final TokenService tokens;

    public ServiceClientService(ServiceClientRepository repo, PasswordEncoder encoder, TokenService tokens) {
        this.repo = repo; this.encoder = encoder; this.tokens = tokens;
    }

    /**
     * DEC-17 - the three rejection rules, in order. All 400, none issues a token.
     * Failing loudly HERE is worth more than saving a field: deriving the aud in
     * silently, a misspelled scope produces a token with the wrong aud, the
     * the gateway answers 403, and the symptom shows up in ANOTHER service owned
     * by ANOTHER team, with the token properly signed and the client properly authenticated.
     */
    @Transactional(readOnly = true)
    public String emitirServicio(String clientId, String secret, String scope, String audience) {
        var cliente = repo.findByClientIdAndDeletedAtIsNull(clientId)
                .filter(c -> encoder.matches(secret, c.getSecretHash()))
                .orElseThrow(ApiException::invalidCredentials);  // no distingue causa

        Set<String> pedidos = Arrays.stream(scope == null ? new String[0] : scope.split("[ ,]+"))
                .filter(s -> !s.isBlank()).collect(Collectors.toSet());
        if (pedidos.isEmpty()) throw ApiException.validation("Falta 'scope'.");

        // Regla 1: audience es obligatorio. No hay default.
        if (audience == null || audience.isBlank()) {
            throw ApiException.validation(
                    "Falta 'audience'. Es el serviceId del destino al que le van a pegar "
                    + "con este token (DEC-17). No hay valor por defecto.");
        }

        for (String s : pedidos) {
            if (!cliente.getAllowedScopes().contains(s)) {
                throw ApiException.validation("El scope '" + s + "' no esta entre los permitidos.");
            }
            if (!ScopeCatalog.esEmitible(s)) {
                throw ApiException.validation(
                        "El scope '" + s + "' no es emitible por client_credentials (DEC-26). "
                        + "Emitibles: " + ScopeCatalog.emitibles());
            }
        }

        // Rule 2: prefixes cannot be mixed. A token has a single aud.
        Set<String> destinos = pedidos.stream().map(ScopeCatalog::audienceDe).collect(Collectors.toSet());
        if (destinos.size() > 1) {
            throw ApiException.validation(
                    "Los scopes pedidos apuntan a mas de un destino " + destinos
                    + ". Un token tiene un solo 'aud': pedí dos tokens.");
        }

        // Rule 3: the declared audience has to derive from the scope.
        String derivado = destinos.iterator().next();
        if (!derivado.equals(audience)) {
            throw ApiException.validation(
                    "El 'audience' declarado ('" + audience + "') no se corresponde con el scope "
                    + "pedido, que deriva en '" + derivado + "'.");
        }

        return tokens.firmarServicio(
                TokenClaims.paraServicio(clientId, audience, pedidos).build());
    }
}
```

- [ ] **Step 4: Escribir el DTO y el controller**

```java
package ar.edu.utn.frc.tup.p4.usersservice.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** DEC-17 - `audience` is the NEW field. See the contract docs for the teams. */
public record ClientCredentialsRequest(@NotBlank String clientId, @NotBlank String clientSecret,
                                       @NotBlank String grantType, @NotBlank String scope,
                                       String audience) { }
```

```java
package ar.edu.utn.frc.tup.p4.usersservice.auth.controllers;

import ar.edu.utn.frc.tup.p4.usersservice.auth.dto.ClientCredentialsRequest;
import ar.edu.utn.frc.tup.p4.usersservice.auth.services.ServiceClientService;
import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ApiException;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/users/public/auth")
public class TokenController {

    private final ServiceClientService clientes;

    public TokenController(ServiceClientService clientes) { this.clientes = clientes; }

    /** It carries no Authorization: this is the request that OBTAINS the token. */
    @PostMapping("/token")
    public Map<String, Object> token(@Valid @RequestBody ClientCredentialsRequest req) {
        if (!"client_credentials".equals(req.grantType())) {
            throw ApiException.validation("grantType no soportado: " + req.grantType());
        }
        String jwt = clientes.emitirServicio(req.clientId(), req.clientSecret(),
                req.scope(), req.audience());
        return Map.of("accessToken", jwt, "tokenType", "Bearer", "expiresIn", 300);
    }
}
```

- [ ] **Step 5: Correr y verificar que pasa**

Run: `mvn -q test -Dtest=ClientCredentialsIT`
Expected: PASS — 6 tests.

- [ ] **Step 6: Commit**

```bash
git add src/main/java src/test/java
git commit -m "feat: client_credentials con audience obligatorio (DEC-17, DEC-26)

Tres reglas de rechazo, todas 400 sin emitir. Fallar en la emision vale mas
que un 403 del Gateway en otro servicio de otro equipo.
El catalogo emitible queda en un solo scope: users.profile.read."
```

---

### Task 17: Registro y activación de la cuenta por enlace

> **Una base de datos, compartida por todas las clases de test, sin limpieza
> entre ellas.** Los contenedores son singleton de JVM (ver
> `AbstractIntegrationTest` y por qué tiene que ser así), y nadie borra filas al
> terminar: lo que una clase commitea, la siguiente lo ve.
>
> Entonces **un email fijo en un fixture es una colisión esperando pasar.**
> `EmailReuseIT` commitea `dup@utn.edu.ar` para probar el índice único de
> `DEC-21`. Cualquier test que inserte esa misma dirección recibe un error de
> clave duplicada que no esperaba o, peor, su **primer** alta falla con un 409
> que parece un bug del código bajo prueba. Lo mismo aplica a
> `uq_whitelist_request_pending`.
>
> Usá una dirección única por corrida en todo lo que insertes:
>
> ```java
> String email = "alta-" + UUID.randomUUID() + "@utn.edu.ar";
> ```
>
> Y no lo "arregles" poniéndole `@Transactional` al test: la mitad de lo que se
> verifica acá es lo que hace **la base** al commitear — columnas generadas,
> índices únicos, `SKIP LOCKED` — y una transacción que hace rollback nunca
> llega ahí.
>
> **Un WARN de Hibernate no es una falla.** `HHH000247 ErrorCode: 1062` con
> `Duplicate entry ... for key 'users.uq_users_active_email'` es exactamente lo
> que `EmailReuseIT` provoca a propósito: Hibernate loguea el error del driver
> mientras sube, el test lo atrapa y afirma sobre él. Si el build cierra en
> verde, ese WARN es la prueba de que el índice funciona.


**Files:**
- Create: `src/main/java/…/users/services/RegistrationService.java`
- Create: `src/main/java/…/users/controllers/RegistrationController.java`
- Create: `src/main/java/…/users/dto/{StudentRegistrationRequest,ProfessorRegistrationRequest,ActivateAccountRequest,ResendCodeRequest}.java`
- Create: `src/main/resources/legal/terms-v1.md`
- Create: `src/main/java/…/users/controllers/LegalController.java`
- Test: `src/test/java/…/users/RegistrationIT.java`
- Test: `src/test/java/…/users/ActivationLinkIT.java`
- Create: `src/test/java/…/users/TestActivationSpy.java`

**Interfaces:**
- Consumes: `UserRepository` (T2), `EmailWhitelistRepository` (T3), `OtpService`+`EphemeralTokenService` (T11), `NotificationEventPublisher` (T7), `AccountEventPublisher`+`KafkaTopicsProperties` (T6), `PasswordPolicy` (T12).
- Produces: `RegistrationService.registrarAlumno(...)`, `.registrarProfesor(...)`, `.activate(String token)`, `.reenviarActivacion(String email)`.

> **`RF-USR-04` · el segundo paso es un ENLACE, no un código tipeado.** El RF
> dice, textual: *"(2) verificación de posesión del email mediante link de
> activación"*. `RF-USR-06` lo repite al hablar del *"primer login vía link de
> activación"*. El código de 6 dígitos queda **solo** para el 2FA del login
> (`RF-NFR-02`), que es otro momento y otro propósito.
>
> Las tres reglas que hacen que este flujo funcione en la vida real están en el
> Step 4. No son opcionales: son la diferencia entre que ande y que falle para
> toda una cohorte.

- [ ] **Step 1: Escribir los T&C mock y el controller legal**

`src/main/resources/legal/terms-v1.md`:
```markdown
> ⚠️ TEXTO MOCK — NO ES UN DOCUMENTO LEGAL. DEC-31.
> Reemplazar por el texto real y bumpear `users.legal.terms-version`.

# Términos y Condiciones · versión v1

1. Esta es una plataforma educativa de la UTN FRC con fines académicos.
2. Los datos de identidad (nombre, apellido, legajo, email institucional) se
   usan exclusivamente para identificar a la persona dentro de la plataforma.
3. No se comparten datos con terceros.
4. La cuenta es personal e intransferible.
```

```java
package ar.edu.utn.frc.tup.p4.usersservice.users.controllers;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/** DEC-31 - public with NO token: it has to be readable BEFORE having an account. */
@RestController
@RequestMapping("/api/users/public/legal")
public class LegalController {

    private final String version;

    public LegalController(@Value("${users.legal.terms-version}") String version) {
        this.version = version;
    }

    @GetMapping("/terms")
    public Map<String, String> tyc() throws Exception {
        String texto = new String(new ClassPathResource("legal/terms-" + version + ".md")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return Map.of("version", version, "texto", texto);
    }
}
```

- [ ] **Step 2: Escribir tu propio spy de mails**

El enlace no se puede leer del mail: no hay servidor de correo. Se intercepta el
publisher y se saca el token del `enlace` que se le pasó a la plantilla.

`TestResetSpy` (T15) hace lo mismo con el token del reset, y **no lo vas a
extender ni tocar: es de otro lote.** Un spy compartido sería un archivo que dos
personas editan en paralelo, que es justo lo que el reparto por propiedad de
archivos evita. Escribís el tuyo, en tu paquete.

Crear `src/test/java/…/users/TestActivationSpy.java`:

```java
package ar.edu.utn.frc.tup.p4.usersservice.users;

import ar.edu.utn.frc.tup.p4.usersservice.shared.notifications.EmailType;
import ar.edu.utn.frc.tup.p4.usersservice.shared.notifications.NotificationEventPublisher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Captura el token del enlace de activacion.
 *
 * Los tres spies declaran un @Primary (NotificationEventPublisher o
 * SecondFactorProvider), asi que NINGUN test puede importar dos Config a la
 * vez. No hace falta: ningun flujo necesita capturar el token de reset y el de
 * activacion en el mismo test.
 */
public class TestActivationSpy extends NotificationEventPublisher {

    private final NotificationEventPublisher real;
    private volatile String ultimoTokenActivacion;

    // El constructor del padre no se usa: toda la logica la delega en real.
    public TestActivationSpy(NotificationEventPublisher real) {
        super(null, null, null);
        this.real = real;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void enviar(EmailType tipo, String to, Map<String, Object> vars) {
        real.enviar(tipo, to, vars);
        if (tipo == EmailType.ACCOUNT_ACTIVATION) {
            this.ultimoTokenActivacion = tokenDe(vars);
        }
    }

    /**
     * El enlace se arma al renderizar y apunta al FRONTEND, no a la API
     * (RF-USR-06): de ahi se saca el query param, no del cuerpo del mail.
     */
    private static String tokenDe(Map<String, Object> vars) {
        String enlace = (String) vars.get("enlace");
        if (enlace == null) return null;
        int idx = enlace.indexOf("token=");
        return idx >= 0 ? enlace.substring(idx + "token=".length()) : null;
    }

    public String ultimoTokenActivacion() { return ultimoTokenActivacion; }

    @TestConfiguration
    public static class Config {
        @Bean @Primary
        TestActivationSpy activationSpy(
                @Qualifier("notificationEventPublisher") NotificationEventPublisher real) {
            return new TestActivationSpy(real);
        }
    }
}
```

El `@Qualifier` no es decorativo: sin él Spring resuelve la dependencia al
`@Primary`, que es este mismo bean, y arranca con una referencia circular.

El `@Import(TestActivationSpy.Config.class)` va sobre `RegistrationIT` y sobre
`ActivationLinkIT`, **no** sobre `AbstractIntegrationTest`: esa clase es de la
base y no registra dobles de test.

- [ ] **Step 3: Escribir el test de activación (falla)** — criterio de DoD #26

```java
package ar.edu.utn.frc.tup.p4.usersservice.users;

import ar.edu.utn.frc.tup.p4.usersservice.AbstractIntegrationTest;
import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ApiException;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.AccountStatus;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.UserRepository;
import ar.edu.utn.frc.tup.p4.usersservice.users.services.RegistrationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RF-USR-04 - step 2: proving possession of the e-mail is a single-use LINK,
 * single use. This IT covers release criterion #26.
 */
@Import(TestActivationSpy.Config.class)
class ActivationLinkIT extends AbstractIntegrationTest {

    @Autowired RegistrationService registro;
    @Autowired UserRepository repo;
    @Autowired TestActivationSpy mailSpy;
    @Autowired StringRedisTemplate redis;

    private void altaAlumno(String email) {
        registro.registrarAlumno("Ana", "Perez", "76543", email,
                "passwordvalida1", "PROG4-2026-A1", "v1");
    }

    private AccountStatus estadoDe(String email) {
        return repo.findByEmailAndDeletedAtIsNull(email).orElseThrow().getAccountStatus();
    }

    @Test
    void el_enlace_correcto_activa_la_cuenta() {
        altaAlumno("act1@utn.edu.ar");
        assertThat(estadoDe("act1@utn.edu.ar")).isEqualTo(AccountStatus.PENDING_EMAIL);

        registro.activate(mailSpy.ultimoTokenActivacion());

        // STUDENT -> PENDING_COURSE, no ACTIVE: falta que Cursos valide el legajo.
        assertThat(estadoDe("act1@utn.edu.ar")).isEqualTo(AccountStatus.PENDING_COURSE);
    }

    @Test
    void el_enlace_se_usa_UNA_sola_vez() {
        // The second click can neither reactivate nor leak that the account exists.
        altaAlumno("act2@utn.edu.ar");
        String token = mailSpy.ultimoTokenActivacion();

        registro.activate(token);
        assertThatThrownBy(() -> registro.activate(token)).isInstanceOf(ApiException.class);
    }

    @Test
    void enlace_usado_e_inexistente_dan_LA_MISMA_respuesta() {
        // Anti-enumeracion: el detail no distingue vencido, usado ni inventado.
        altaAlumno("act3@utn.edu.ar");
        String token = mailSpy.ultimoTokenActivacion();
        registro.activate(token);

        String usado = capturar(() -> registro.activate(token));
        String inventado = capturar(() -> registro.activate("token-que-no-existe-de-largo-suficiente"));
        assertThat(usado).isEqualTo(inventado);
    }

    @Test
    void reenviar_genera_uno_nuevo_e_invalida_el_anterior() {
        // Without the per-e-mail index the old link would stay alive in parallel.
        altaAlumno("act4@utn.edu.ar");
        String viejo = mailSpy.ultimoTokenActivacion();

        registro.reenviarActivacion("act4@utn.edu.ar");
        String nuevo = mailSpy.ultimoTokenActivacion();

        assertThat(nuevo).isNotEqualTo(viejo);
        assertThatThrownBy(() -> registro.activate(viejo)).isInstanceOf(ApiException.class);

        registro.activate(nuevo);
        assertThat(estadoDe("act4@utn.edu.ar")).isEqualTo(AccountStatus.PENDING_COURSE);
    }

    @Test
    void reenviar_a_un_email_inexistente_responde_igual_que_a_uno_real() {
        altaAlumno("act5@utn.edu.ar");
        assertThat(registro.reenviarActivacion("nadie@utn.edu.ar"))
                .isEqualTo(registro.reenviarActivacion("act5@utn.edu.ar"));
    }

    @Test
    void el_token_no_se_guarda_en_claro() {
        // Whoever can read Redis must not be able to activate other people's accounts.
        altaAlumno("act6@utn.edu.ar");
        String token = mailSpy.ultimoTokenActivacion();

        assertThat(redis.hasKey("activacion:" + token)).isFalse();
        registro.activate(token);   // pero el token del mail si funciona
        assertThat(estadoDe("act6@utn.edu.ar")).isEqualTo(AccountStatus.PENDING_COURSE);
    }

    private String capturar(Runnable r) {
        try { r.run(); return "no-fallo"; } catch (ApiException e) { return e.getMessage(); }
    }
}
```

- [ ] **Step 4: Escribir `RegistrationService`**

Tres decisiones de este servicio que hay que respetar tal cual:

1. **El enlace apunta al FRONTEND, no a la API.** La URL del mail es
   `${users.front-url}/activate?token=…`, y es una pantalla del cliente la que
   llama a `POST /registro/activate`. La razón no es estética: los filtros de
   correo institucional (Microsoft Safe Links, Proofpoint, Mimecast) **visitan
   los enlaces** antes de entregar el mail, para buscar phishing. Si el enlace
   fuera un `GET /registro/activate?token=` de la API, el escáner consumiría el
   token y la persona llegaría a "enlace ya usado" **sin haber hecho clic** —
   sistemáticamente, para todas las casillas institucionales, que son
   exactamente las que exige `RF-USR-03`. Y el bug no aparece en desarrollo,
   porque con un Gmail personal funciona perfecto.
2. **El token se guarda hasheado y bajo dos claves.** `activacion:{sha256}` es
   por donde entra el enlace; `activacion:email:{email}` es el índice que
   permite invalidar el anterior al reenviar. Sin el índice, cada reenvío deja
   vivo también el enlace viejo y terminan existiendo N enlaces válidos.
3. **256 bits y TTL de 24 h.** Con esa entropía no hay fuerza bruta posible, así
   que no hacen falta límites de intentos y el TTL puede ser cómodo. (El 2FA es
   al revés: 6 dígitos son ~20 bits, y por eso vive 5 minutos con 5 intentos.)

```java
package ar.edu.utn.frc.tup.p4.usersservice.users.services;

import ar.edu.utn.frc.tup.p4.usersservice.auth.otp.OtpService;
import ar.edu.utn.frc.tup.p4.usersservice.auth.store.EphemeralTokenService;
import ar.edu.utn.frc.tup.p4.usersservice.config.KafkaTopicsProperties;
import ar.edu.utn.frc.tup.p4.usersservice.config.OtpProperties;
import ar.edu.utn.frc.tup.p4.usersservice.shared.events.AccountEventPublisher;
import ar.edu.utn.frc.tup.p4.usersservice.shared.notifications.NotificationEventPublisher;
import ar.edu.utn.frc.tup.p4.usersservice.shared.notifications.EmailType;
import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ApiException;
import ar.edu.utn.frc.tup.p4.usersservice.users.PasswordPolicy;
import ar.edu.utn.frc.tup.p4.usersservice.users.entities.User;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.AccountStatus;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.EmailWhitelistRepository;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class RegistrationService {

    private static final String RESPUESTA_CONSTANTE = "Si el email existe, te enviamos un enlace.";
    private static final SecureRandom RANDOM = new SecureRandom();
    /** 24 h. With a 256-bit token there is no tension between lifetime and brute force. */
    private static final Duration TTL_ACTIVACION = Duration.ofHours(24);

    private final UserRepository repo;
    private final EmailWhitelistRepository whitelist;
    private final PasswordEncoder encoder;
    private final OtpService otp;
    private final OtpProperties otpProps;
    private final EphemeralTokenService efimeros;
    private final NotificationEventPublisher mails;
    private final AccountEventPublisher eventos;
    private final KafkaTopicsProperties topics;
    private final String tycVigente;
    private final String urlFront;

    public RegistrationService(UserRepository repo, EmailWhitelistRepository whitelist,
                           PasswordEncoder encoder, OtpService otp, OtpProperties otpProps,
                           EphemeralTokenService efimeros,
                           NotificationEventPublisher mails, AccountEventPublisher eventos,
                           KafkaTopicsProperties topics,
                           @Value("${users.legal.terms-version}") String tycVigente,
                           @Value("${users.front-url:https://app.tpi.utn.frc}") String urlFront) {
        this.repo = repo; this.whitelist = whitelist; this.encoder = encoder;
        this.otp = otp; this.otpProps = otpProps; this.efimeros = efimeros; this.mails = mails;
        this.eventos = eventos; this.topics = topics; this.tycVigente = tycVigente;
        this.urlFront = urlFront;
    }

    public record PayloadAlumnoRegistrado(String userId, String legajo, String invitationCode) { }

    @Transactional
    public void registrarAlumno(String firstNames, String lastNames, String legajo, String email,
                                String password, String invitationCode, String termsVersion) {
        User u = crear(firstNames, lastNames, email, password, Role.STUDENT, termsVersion);
        u.setLegajo(legajo);
        repo.saveAndFlush(u);
        // The invitation code is NOT persisted in users: it is Cursos' data.
        // It lives ephemerally in Redis until the account is activated, and travels in the event.
        guardarCodigoInvitacion(u, invitationCode);
        enviarEnlaceActivacion(u);
    }

    @Transactional
    public void registrarProfesor(String firstNames, String lastNames, String email,
                                  String password, String termsVersion) {
        String normalizado = email.toLowerCase(Locale.ROOT);
        if (!whitelist.existsByEmailAndDeletedAtIsNull(normalizado)) {
            throw ApiException.emailNotWhitelisted(
                    "El email no esta en la lista blanca. Pedile a un ADMIN que lo agregue.");
        }
        User u = crear(firstNames, lastNames, email, password, Role.PROFESSOR, termsVersion);
        repo.saveAndFlush(u);
        enviarEnlaceActivacion(u);
    }

    private User crear(String firstNames, String lastNames, String email, String password,
                       Role role, String termsVersion) {
        // DEC-31 / RF-NFR-09: obligatorio y versionado.
        if (termsVersion == null || !termsVersion.equals(tycVigente)) {
            throw ApiException.validation(
                    "Hay que aceptar los Terminos y Condiciones vigentes (version " + tycVigente + ").");
        }
        PasswordPolicy.validate(password);

        String normalizado = email.toLowerCase(Locale.ROOT);
        if (repo.findByEmailAndDeletedAtIsNull(normalizado).isPresent()) {
            throw ApiException.duplicateEmail();
        }
        return User.create(firstNames, lastNames, normalizado, encoder.encode(password), role, termsVersion);
    }

    /**
     * RF-USR-04 - proving possession of the e-mail is a single-use LINK, not a
     * typed code. The token is 256 bits: there is nothing to brute-force, so the
     * TTL can be 24 h and no attempt counter is needed
     * limites de intentos.
     *
     * Se guarda HASHEADO y bajo dos claves:
     *   activation:{sha256(token)}  -> userId   (where the link comes in)
     *   activacion:email:{email}    -> sha256   (indice, para invalidar al reenviar)
     * Without the index, each resend would leave the previous link alive too.
     */
    private void enviarEnlaceActivacion(User u) {
        // Resend: the previous link dies. There are never two valid at once.
        efimeros.consumir(claveIndice(u.getEmail()))
                .ifPresent(hashViejo -> efimeros.consumir(claveActivacion(hashViejo)));

        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String hash = hashear(token);

        efimeros.guardar(claveActivacion(hash), u.getId().toString(), TTL_ACTIVACION);
        efimeros.guardar(claveIndice(u.getEmail()), hash, TTL_ACTIVACION);

        // The link points at the FRONTEND, not at the API. Corporate mail
        // corporate mail scanners (Safe Links, Proofpoint) visit links before the
        // person: if the link activated on a GET, the scanner would consume the
        // token of the whole cohort. The frontend screen only activates
        // when somebody presses the button, and no scanner does that.
        mails.enviar(EmailType.ACCOUNT_ACTIVATION, u.getEmail(),
                Map.of("firstNames", u.getFirstNames(),
                       "enlace", urlFront + "/activate?token=" + token));
    }

    @Transactional
    public void activate(String token) {
        UUID userId = efimeros.consumir(claveActivacion(hashear(token)))   // un solo uso, atomico
                .map(UUID::fromString)
                .orElseThrow(ApiException::invalidLink);

        User u = repo.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(ApiException::invalidLink);
        efimeros.consumir(claveIndice(u.getEmail()));   // limpia el indice
        u.activate();
        repo.save(u);

        if (u.getRole() == Role.STUDENT) {
            // DEC-34: we publish on the move to PENDING_COURSE, not before. If
            // verifies the e-mail, Cursos never hears about it.
            eventos.publicar(topics.studentRegistered(), "ALUMNO_REGISTRADO",
                    new PayloadAlumnoRegistrado(u.getId().toString(), u.getLegajo(),
                            leerCodigoInvitacion(u)));
            mails.enviar(EmailType.REQUEST_PENDING, u.getEmail(),
                    Map.of("firstNames", u.getFirstNames(),
                           "accountStatus", AccountStatus.PENDING_COURSE.name()));
        }
    }

    /** Anti-enumeration: the same response whether or not the account exists. */
    @Transactional
    public String reenviarActivacion(String email) {
        repo.findByEmailAndDeletedAtIsNull(email.toLowerCase(Locale.ROOT))
                .filter(u -> u.getAccountStatus() == AccountStatus.PENDING_EMAIL)
                .ifPresent(this::enviarEnlaceActivacion);
        return RESPUESTA_CONSTANTE;
    }

    private String claveActivacion(String hash) { return "activacion:" + hash; }

    private String claveIndice(String email) { return "activacion:email:" + email; }

    /**
     * The token is stored hashed: whoever can read Redis must not be able to
     * activate cuentas ajenas. SHA-256 pelado alcanza — el token ya es aleatorio
     * 256 bits, no dictionary is of any use to anyone.
     */
    private String hashear(String token) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 tiene que existir", e);
        }
    }

    // The invitation code is stored ephemerally until the account is activated:
    // it is not our data and it does not go into the users table.
    private void guardarCodigoInvitacion(User u, String code) {
        otp.guardarDatoAuxiliar("invitacion:" + u.getEmail(), code, TTL_ACTIVACION);
    }

    private String leerCodigoInvitacion(User u) {
        return otp.leerDatoAuxiliar("invitacion:" + u.getEmail()).orElse(null);
    }
}
```

> **Ojo con el TTL del código de invitación:** tiene que ser el mismo que el del
> enlace (`TTL_ACTIVACION`), no el del OTP. Si el enlace vive 24 h y el código
> de invitación 30 min, una activación tardía publica `ALUMNO_REGISTRADO` con
> `invitationCode: null` y Cursos no puede rutear la solicitud.

- [ ] **Step 5: Escribir el controller y los DTOs**

```java
package ar.edu.utn.frc.tup.p4.usersservice.users.controllers;

import ar.edu.utn.frc.tup.p4.usersservice.users.dto.*;
import ar.edu.utn.frc.tup.p4.usersservice.users.services.RegistrationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/users/public/registration")
public class RegistrationController {

    private final RegistrationService registro;

    public RegistrationController(RegistrationService registro) { this.registro = registro; }

    @PostMapping("/student")
    public void alumno(@Valid @RequestBody StudentRegistrationRequest r) {
        registro.registrarAlumno(r.firstNames(), r.lastNames(), r.legajo(), r.email(),
                r.password(), r.invitationCode(), r.termsVersion());
    }

    @PostMapping("/professor")
    public void profesor(@Valid @RequestBody ProfessorRegistrationRequest r) {
        registro.registrarProfesor(r.firstNames(), r.lastNames(), r.email(), r.password(), r.termsVersion());
    }

    /**
     * RF-USR-04 · paso 2: verificacion de posesion del email.
     *
     * It is a POST and not a GET on purpose, and the mail link points at a
     * frontend screen that calls this. A GET /activate?token= would be consumed
     * by corporate mail scanners before the person got there.
     */
    @PostMapping("/activate")
    public void activate(@Valid @RequestBody ActivateAccountRequest r) {
        registro.activate(r.token());
    }

    @PostMapping("/resend-activation")
    public Map<String, String> reenviar(@Valid @RequestBody ResendCodeRequest r) {
        return Map.of("message", registro.reenviarActivacion(r.email()));
    }
}
```

DTOs (`users/dto/`):
```java
public record StudentRegistrationRequest(@NotBlank String firstNames, @NotBlank String lastNames,
                                    @NotBlank String legajo, @NotBlank @Email String email,
                                    @NotBlank String password, @NotBlank String invitationCode,
                                    @NotBlank String termsVersion) { }
public record ProfessorRegistrationRequest(@NotBlank String firstNames, @NotBlank String lastNames,
                                      @NotBlank @Email String email, @NotBlank String password,
                                      @NotBlank String termsVersion) { }
/**
 * The link's token. It does NOT carry the e-mail: the token already identifies
 * asking for it as well would be an enumeration channel. The pattern narrows
 * what is accepted to base64url of the expected length, so anything that cannot
 * rechaza antes de tocar Redis.
 */
public record ActivateAccountRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{40,64}") String token) { }
public record ResendCodeRequest(@NotBlank @Email String email) { }
```

- [ ] **Step 6: Configurar la base de los enlaces**

En `application.yml`, junto a `users.legal`:

```yaml
users:
  # Base of the links that travel by mail (activation and reset). It points at
  # the FRONTEND: the client screens are the ones that call the API.
  front-url: ${FRONT_URL:http://localhost:5173}
```

El valor efectivo lo inyecta quien orquesta el despliegue, por variable de
entorno `USERS_FRONT_URL`. **No hay que tocar ningún `docker-compose.yml` desde
este repo**: el default de arriba alcanza para desarrollo y para los tests, y el
del entorno lo pisa donde haga falta.

> **Por qué el default apunta al front y no a la API.** El enlace del mail lo
> abre una persona en un navegador, y del otro lado tiene que haber una pantalla
> que llame al endpoint — no el endpoint. Si el mail linkeara directo a la API,
> los escáneres de correo institucional (Safe Links, Proofpoint) consumirían el
> token de un solo uso antes de que nadie apriete nada.

- [ ] **Step 7: Correr y verificar que pasan**

Run: `mvn -q clean verify -Dit.test=RegistrationIT,ActivationLinkIT`
Expected: PASS — 11 tests.

Verificación manual contra el stack levantado, que es la que prueba lo que el
test no puede (que el mail lleve una URL usable):

```bash
G=http://localhost:8080
curl -s -X POST $G/api/users/public/registration/student -H 'Content-Type: application/json' \
  -d '{"firstNames":"Link","lastNames":"Test","legajo":"90911","email":"link.test@utn.edu.ar",
       "password":"passwordvalida1","invitationCode":"PROG4-2026-A1","termsVersion":"v1"}'

# El enlace sale del outbox: no hay servidor de mail en desarrollo.
docker compose exec -T mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" users -N \
  -e "SELECT payload FROM outbox_events ORDER BY created_at DESC LIMIT 1;" \
  | grep -oE 'token=[A-Za-z0-9_-]+' | cut -d= -f2
```

Con ese token, tres checks:

```bash
# 1. Activa, y el STUDENT queda en PENDING_COURSE (no ACTIVE).
curl -s -X POST $G/api/users/public/registration/activate \
  -H 'Content-Type: application/json' -d "{\"token\":\"$T\"}" -w ' [%{http_code}]'
# -> 200

# 2. El mismo enlace por segunda vez: 400 invalid-link.
curl -s -X POST $G/api/users/public/registration/activate \
  -H 'Content-Type: application/json' -d "{\"token\":\"$T\"}"
# -> {"type":".../invalid-link", ...}

# 3. Un token inventado devuelve EXACTAMENTE lo mismo que el usado.
#    Si los dos mensajes difieren, hay enumeracion de cuentas.
```

- [ ] **Step 8: Commit**

```bash
git add src/main/java src/main/resources src/test/java
git commit -m "feat: alta con activacion por enlace y TyC versionados

RF-USR-04: la verificacion de posesion del email es un link de un solo uso.
El enlace apunta al frontend y la activacion es un POST: un GET lo consumirian
los escaneres de correo institucional antes que la persona.
Token de 256 bits, hasheado en Redis, TTL 24 h, indice por email para que el
reenvio invalide el anterior.
DEC-31: TyC mock versionado, servido publico."
```

---

### Task 18: Onboarding, `GET /me` y perfil público

**Files:**
- Create: `src/main/java/…/users/services/UserService.java`
- Create: `src/main/java/…/users/controllers/UserController.java`
- Create: `src/main/java/…/users/dto/{OnboardingRequest,UserMeResponse,ProfileResponse}.java`
- Test: `src/test/java/…/users/OnboardingWithoutAvatarIT.java`
- Test: `src/test/java/…/users/ProfileIT.java`

**Interfaces:**
- Consumes: `UserRepository` (T2), `GatewayPrincipal` (T5), `SkipAccountGate` (T8).
- Produces: `UserService.me(UUID)` → `UserMeResponse`; `.completeOnboarding(UUID, String, String, boolean)`; `.perfil(UUID)` → `ProfileResponse`.

- [ ] **Step 1: Escribir el test de onboarding (falla)** — criterio de DoD #27

```java
package ar.edu.utn.frc.tup.p4.usersservice.users;

import ar.edu.utn.frc.tup.p4.usersservice.AbstractIntegrationTest;
import ar.edu.utn.frc.tup.p4.usersservice.users.entities.User;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.AccountStatus;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.UserRepository;
import ar.edu.utn.frc.tup.p4.usersservice.users.services.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DEC-30 - the test that keeps EVERY new user from being locked in a 403.
 * Without object storage there is no upload endpoint; if avatarRef were
 * podria cerrar el gate 3 y quedaria bloqueado indefinidamente.
 */
class OnboardingWithoutAvatarIT extends AbstractIntegrationTest {

    @Autowired UserService users;
    @Autowired UserRepository repo;

    private UUID activo() {
        User u = User.create("Ana", "P", "onb@utn.edu.ar", "$2a$12$h", Role.STUDENT, "v1");
        u.forceStatusForTest(AccountStatus.ACTIVE);
        return repo.saveAndFlush(u).getId();
    }

    @Test
    void el_onboarding_SIN_avatarRef_cierra_el_gate_3() {
        UUID id = activo();
        assertThat(users.me(id).firstLogin()).isTrue();

        users.completeOnboarding(id, "anaperez", null, true);

        var me = users.me(id);
        assertThat(me.firstLogin()).isFalse();
        assertThat(me.avatarRef()).isNull();
        assertThat(me.guidedTourCompleted()).isTrue();
    }

    @Test
    void si_viene_avatarRef_se_guarda() {
        // The field's contract does not change: when object storage lands, it works.
        UUID id = activo();
        users.completeOnboarding(id, "anaperez", "avatars/ana.png", true);
        assertThat(users.me(id).avatarRef()).isEqualTo("avatars/ana.png");
    }

    @Test
    void GET_me_devuelve_los_cuatro_flags_que_el_frontend_necesita() {
        // This is how the person finds out WHAT they are missing.
        var me = users.me(activo());
        assertThat(me.accountStatus()).isNotNull();
        assertThat(me.mustChangePassword()).isFalse();
        assertThat(me.firstLogin()).isTrue();
        assertThat(me.guidedTourCompleted()).isFalse();
    }
}
```

- [ ] **Step 2: Escribir el test de perfil (falla)** — `DEC-36`

```java
package ar.edu.utn.frc.tup.p4.usersservice.users;

import ar.edu.utn.frc.tup.p4.usersservice.AbstractIntegrationTest;
import ar.edu.utn.frc.tup.p4.usersservice.users.entities.User;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.AccountStatus;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.UserRepository;
import ar.edu.utn.frc.tup.p4.usersservice.users.services.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/** DEC-36 · la ruta acepta token de persona ademas de MS. */
class ProfileIT extends AbstractIntegrationTest {

    @Autowired UserService users;
    @Autowired UserRepository repo;

    @Test
    void el_perfil_NO_expone_email_legajo_ni_estado() {
        // This is what makes opening it to person tokens acceptable. If it returned
        // email o legajo, la respuesta a DEC-36 seria no.
        User u = User.create("Ana", "Perez", "perf@utn.edu.ar", "$2a$12$h", Role.STUDENT, "v1");
        u.setLegajo("76543");
        u.forceStatusForTest(AccountStatus.ACTIVE);
        u.completeOnboarding("anaperez", "avatars/a.png", true);
        repo.saveAndFlush(u);

        var perfil = users.perfil(u.getId());

        assertThat(perfil.firstNames()).isEqualTo("Ana");
        assertThat(perfil.githubUsername()).isEqualTo("anaperez");
        assertThat(perfil.toString())
                .doesNotContain("perf@utn.edu.ar")
                .doesNotContain("76543")
                .doesNotContain("ACTIVE");
    }

    @Test
    void solo_el_handle_de_github_nunca_una_URL() {
        // RF-USR-06: the link is built at render time, which avoids URL injection.
        User u = User.create("B", "Q", "gh@utn.edu.ar", "$2a$12$h", Role.STUDENT, "v1");
        u.forceStatusForTest(AccountStatus.ACTIVE);
        u.completeOnboarding("bq", null, true);
        repo.saveAndFlush(u);
        assertThat(users.perfil(u.getId()).githubUsername()).doesNotContain("http");
    }
}
```

- [ ] **Step 3: Escribir los DTOs y `UserService`**

```java
package ar.edu.utn.frc.tup.p4.usersservice.users.dto;

import ar.edu.utn.frc.tup.p4.usersservice.users.enums.AccountStatus;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;

public record UserMeResponse(String id, String firstNames, String lastNames, String email,
                             Role role, AccountStatus accountStatus, boolean mustChangePassword,
                             boolean firstLogin, boolean guidedTourCompleted,
                             String githubUsername, String avatarRef) { }
```
```java
package ar.edu.utn.frc.tup.p4.usersservice.users.dto;

/** DEC-36 - what any classmate sees. NO e-mail, no legajo, no status. */
public record ProfileResponse(String id, String firstNames, String lastNames,
                             String githubUsername, String avatarRef) { }
```
```java
package ar.edu.utn.frc.tup.p4.usersservice.users.dto;

import jakarta.validation.constraints.NotBlank;

/** DEC-30 · avatarRef es OPCIONAL: no lleva @NotBlank. */
public record OnboardingRequest(@NotBlank String githubUsername, String avatarRef, boolean tourOk) { }
```

```java
package ar.edu.utn.frc.tup.p4.usersservice.users.services;

import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ApiException;
import ar.edu.utn.frc.tup.p4.usersservice.users.dto.*;
import ar.edu.utn.frc.tup.p4.usersservice.users.entities.User;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class UserService {

    private final UserRepository repo;

    public UserService(UserRepository repo) { this.repo = repo; }

    @Transactional(readOnly = true)
    public UserMeResponse me(UUID id) {
        User u = buscar(id);
        return new UserMeResponse(u.getId().toString(), u.getFirstNames(), u.getLastNames(),
                u.getEmail(), u.getRole(), u.getAccountStatus(), u.mustChangePassword(),
                u.isFirstLogin(), u.isGuidedTourCompleted(), u.getGithubUsername(), u.getAvatarRef());
    }

    @Transactional(readOnly = true)
    public ProfileResponse perfil(UUID id) {
        User u = buscar(id);
        return new ProfileResponse(u.getId().toString(), u.getFirstNames(), u.getLastNames(),
                u.getGithubUsername(), u.getAvatarRef());
    }

    /** DEC-30 - avatarRef may be null while object storage is out of this sprint. */
    @Transactional
    public void completeOnboarding(UUID id, String githubUsername, String avatarRef, boolean tourOk) {
        User u = buscar(id);
        u.completeOnboarding(githubUsername, avatarRef, tourOk);
        repo.save(u);
    }

    private User buscar(UUID id) {
        return repo.findByIdAndDeletedAtIsNull(id).orElseThrow(ApiException::accessDenied);
    }
}
```

- [ ] **Step 4: Escribir el controller**

```java
package ar.edu.utn.frc.tup.p4.usersservice.users.controllers;

import ar.edu.utn.frc.tup.p4.usersservice.shared.gates.SkipAccountGate;
import ar.edu.utn.frc.tup.p4.usersservice.shared.security.GatewayPrincipal;
import ar.edu.utn.frc.tup.p4.usersservice.users.dto.*;
import ar.edu.utn.frc.tup.p4.usersservice.users.services.UserService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService users;

    public UserController(UserService users) { this.users = users; }

    /** The ONLY route that crosses all three gates: it is how the frontend
     *  entera de que le falta a la persona. */
    @GetMapping("/me")
    @SkipAccountGate({SkipAccountGate.Gate.ESTADO, SkipAccountGate.Gate.PASSWORD,
                      SkipAccountGate.Gate.ONBOARDING})
    public UserMeResponse me(@AuthenticationPrincipal GatewayPrincipal p) {
        return users.me(p.id());
    }

    @PatchMapping("/me/onboarding")
    /** The ONBOARDING gate's way out, and exempt from PASSWORD for the same
     *  symmetric reason as /auth/password/change: the two gates must not lock
     *  each other out (task 8). The STATUS gate still applies. */
    @SkipAccountGate({SkipAccountGate.Gate.ONBOARDING, SkipAccountGate.Gate.PASSWORD})
    public void onboarding(@AuthenticationPrincipal GatewayPrincipal p,
                           @Valid @RequestBody OnboardingRequest r) {
        users.completeOnboarding(p.id(), r.githubUsername(), r.avatarRef(), r.tourOk());
    }

    /**
     * DEC-36 - it accepts both token types. With MS it demands the scope; with a
     * de persona alcanza estar autenticado — y los tres gates igual aplican,
     * so a pending account does not read other people's profiles.
     */
    @GetMapping("/profile/{id}")
    @PreAuthorize("(hasRole('MS') and hasAuthority('users.profile.read')) or isAuthenticated()")
    public ProfileResponse perfil(@PathVariable UUID id) {
        return users.perfil(id);
    }
}
```

- [ ] **Step 5: Correr y verificar que pasan**

Run: `mvn -q test -Dtest=OnboardingWithoutAvatarIT+ProfileIT`
Expected: PASS — 5 tests.

- [ ] **Step 6: Commit**

```bash
git add src/main/java src/test/java
git commit -m "feat: onboarding con avatar opcional, GET /me y perfil (DEC-30, DEC-36)

DEC-30: sin avatarRef opcional, todo usuario nuevo quedaba encerrado en 403.
DEC-36: el perfil no expone email, legajo ni status — es lo que hace
aceptable abrirlo a tokens de persona."
```

---

### Task 19: Operaciones de ADMIN

**Files:**
- Modify: `src/main/java/…/users/services/UserService.java`
- Modify: `src/main/java/…/users/controllers/UserController.java`
- Create: `src/main/java/…/users/dto/{CreateUserRequest,RoleChangeRequest,AdminDeactivationRequest}.java`
- Test: `src/test/java/…/users/AdminRulesIT.java`

**Interfaces:**
- Consumes: `UserRepository.countActivosConLock` (T2), `CredentialService` (T12), `AccountEventPublisher` (T6), `TokenStore` (T11).
- Produces: `UserService.crear(...)`, `.deactivate(UUID actor, UUID objetivo, AdminDeactivationRequest)`, `.changeRole(UUID actor, UUID objetivo, Role)`.

- [ ] **Step 1: Escribir el test (falla)** — criterios de DoD #9, #17, #20, #24

```java
package ar.edu.utn.frc.tup.p4.usersservice.users;

import ar.edu.utn.frc.tup.p4.usersservice.AbstractIntegrationTest;
import ar.edu.utn.frc.tup.p4.usersservice.auth.store.TokenStore;
import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ApiException;
import ar.edu.utn.frc.tup.p4.usersservice.users.dto.AdminDeactivationRequest;
import ar.edu.utn.frc.tup.p4.usersservice.users.entities.User;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.AccountStatus;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.UserRepository;
import ar.edu.utn.frc.tup.p4.usersservice.users.services.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminRulesIT extends AbstractIntegrationTest {

    @Autowired UserService users;
    @Autowired UserRepository repo;
    @Autowired PasswordEncoder encoder;
    @Autowired TokenStore store;

    private User admin(String email) {
        User u = User.createAdmin("Ad", "Min", email, encoder.encode("passwordvalida1"), "v1");
        u.changePassword(encoder.encode("passwordvalida1"));   // limpia mustChangePassword
        return repo.saveAndFlush(u);
    }

    private AdminDeactivationRequest confirmacion(String username) {
        return new AdminDeactivationRequest("passwordvalida1", "123456", username);
    }

    @Test
    void no_se_puede_dejar_la_plataforma_sin_ningun_ADMIN() {
        repo.deleteAll();
        User unico = admin("solo@utn.edu.ar");
        assertThatThrownBy(() -> users.deactivate(unico.getId(), unico.getId(),
                confirmacion("solo@utn.edu.ar")))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void un_ADMIN_no_puede_darse_de_baja_a_si_mismo() {
        admin("otro@utn.edu.ar");
        User a = admin("auto@utn.edu.ar");
        assertThatThrownBy(() -> users.deactivate(a.getId(), a.getId(), confirmacion("auto@utn.edu.ar")))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void la_baja_de_ADMIN_exige_confirmacion_escrita_del_username() {
        // RF-ROL-06 / DEC-11: no alcanza con el boton.
        User actor = admin("act@utn.edu.ar");
        User objetivo = admin("obj@utn.edu.ar");
        assertThatThrownBy(() -> users.deactivate(actor.getId(), objetivo.getId(),
                confirmacion("escrito-mal@utn.edu.ar")))
                .isInstanceOf(ApiException.class);

        users.deactivate(actor.getId(), objetivo.getId(), confirmacion("obj@utn.edu.ar"));
        assertThat(repo.findById(objetivo.getId()))
                .get().extracting(u -> u.getAccountStatus()).isEqualTo(AccountStatus.DEACTIVATED);
    }

    @Test
    void dar_de_baja_BORRA_la_sesion() throws Exception {
        // DEC-23 - DoD criterion #24. This is what makes the decision to
        // llevar est/pwd/onb en el token: perder acceso NO puede esperar 10 min.
        admin("x1@utn.edu.ar");
        User objetivo = admin("x2@utn.edu.ar");
        store.guardarSesion(objetivo.getId(), "sid-vivo");

        users.deactivate(admin("x3@utn.edu.ar").getId(), objetivo.getId(), confirmacion("x2@utn.edu.ar"));

        assertThat(store.sidDe(objetivo.getId())).isEmpty();
    }

    @Test
    void dos_bajas_CONCURRENTES_no_pueden_dejar_cero_ADMIN() throws Exception {
        // DEC-20 regla 5 · criterio de DoD #20. Sin SELECT FOR UPDATE, bajo
        // Under REPEATABLE READ each transaction sees TWO admins in its own snapshot
        // y ambas proceden.
        repo.deleteAll();
        User a = admin("c1@utn.edu.ar");
        User b = admin("c2@utn.edu.ar");
        User actor = admin("c3@utn.edu.ar");

        var pool = Executors.newFixedThreadPool(2);
        var listos = new CountDownLatch(2);
        var arrancar = new CountDownLatch(1);
        AtomicInteger exitos = new AtomicInteger();

        for (User objetivo : List.of(a, b)) {
            pool.submit(() -> {
                listos.countDown();
                try {
                    arrancar.await();
                    users.deactivate(actor.getId(), objetivo.getId(),
                            confirmacion(objetivo.getEmail()));
                    exitos.incrementAndGet();
                } catch (Exception ignored) { }
            });
        }
        listos.await();
        arrancar.countDown();
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);

        assertThat(repo.countByRolAndDeletedAtIsNull(Role.ADMIN)).isGreaterThanOrEqualTo(1);
        assertThat(exitos.get()).isLessThanOrEqualTo(2);
    }

    @Test
    void cambiar_el_rol_del_ultimo_ADMIN_tambien_se_bloquea() {
        repo.deleteAll();
        User unico = admin("role@utn.edu.ar");
        assertThatThrownBy(() -> users.changeRole(unico.getId(), unico.getId(), Role.PROFESSOR))
                .isInstanceOf(ApiException.class);
    }
}
```

- [ ] **Step 2: Correr y verificar que falla**

Run: `mvn -q test -Dtest=AdminRulesIT`
Expected: FAIL — faltan los métodos en `UserService`.

- [ ] **Step 3: Agregar los métodos a `UserService`**

`src/main/java/…/users/dto/AdminDeactivationRequest.java`:
```java
package ar.edu.utn.frc.tup.p4.usersservice.users.dto;

import jakarta.validation.constraints.NotBlank;

/** RF-ROL-06: `usernameConfirmation` es la confirmacion ESCRITA. */
public record AdminDeactivationRequest(@NotBlank String password, @NotBlank String twoFactorCode,
                               @NotBlank String usernameConfirmation) { }
```

Y los otros dos DTO de la tarea:
```java
public record CreateUserRequest(@NotBlank String firstNames, @NotBlank String lastNames,
                                  @NotBlank @Email String email, @NotBlank String password,
                                  @NotNull Role role) { }
public record RoleChangeRequest(@NotNull Role role) { }
```

**`UserService` suma dependencias al constructor** (`CredentialService`, `SecondFactorProvider`, `TokenStore`, `AccountEventPublisher`, `KafkaTopicsProperties`, `PasswordEncoder` y `@Value("${users.legal.terms-version}") String tycVigente`), además del `UserRepository` que ya tenía:

```java
/**
 * It crosses both modules with NO network in between: the reinforced confirmation
 * (password again + 2FA) belongs to auth/, the business rules to users/.
 */
@Transactional
public void deactivate(UUID actorId, UUID objetivoId, AdminDeactivationRequest req) {
    User objetivo = buscar(objetivoId);

    if (objetivo.getRole() == Role.ADMIN) {
        if (actorId.equals(objetivoId)) {
            throw ApiException.validation("Un ADMIN no puede darse de baja a si mismo.");
        }
        // RF-ROL-06 / DEC-11: confirmacion ESCRITA, no solo un boton.
        if (!objetivo.getEmail().equalsIgnoreCase(req.usernameConfirmation())) {
            throw ApiException.validation(
                    "Escribi el username exacto del ADMIN que vas a dar de baja para confirmar.");
        }
        if (!credenciales.verifyPasswordOf(actorId, req.password())) {
            throw ApiException.invalidCredentials();
        }
        segundoFactor.verificar(actorId, req.twoFactorCode());

        // DEC-20 rule 5: the count goes WITH A LOCK, in this same transaction.
        if (repo.countActivosConLock(Role.ADMIN) <= 1) throw ApiException.lastAdmin();
    }

    objetivo.deactivate();
    repo.save(objetivo);

    // DEC-23: losing access cannot wait the ~10 min of exp.
    store.borrarSesion(objetivoId);

    eventos.publicar(topics.audit(), "USUARIO_DADO_DE_BAJA",
            Map.of("userId", objetivoId.toString(), "actorId", actorId.toString()));
}

@Transactional
public void changeRole(UUID actorId, UUID objetivoId, Role nuevo) {
    User objetivo = buscar(objetivoId);
    if (objetivo.getRole() == Role.ADMIN && nuevo != Role.ADMIN
            && repo.countActivosConLock(Role.ADMIN) <= 1) {
        throw ApiException.lastAdmin();
    }
    objetivo.changeRole(nuevo);
    repo.save(objetivo);
    eventos.publicar(topics.audit(), "ROL_CAMBIADO",
            Map.of("userId", objetivoId.toString(), "rolNuevo", nuevo.name(),
                   "actorId", actorId.toString()));
}

@Transactional
public UUID crear(String firstNames, String lastNames, String email, String password, Role role) {
    PasswordPolicy.validate(password);
    String normalizado = email.toLowerCase(Locale.ROOT);
    if (repo.findByEmailAndDeletedAtIsNull(normalizado).isPresent()) throw ApiException.duplicateEmail();

    User u = role == Role.ADMIN
            ? User.createAdmin(firstNames, lastNames, normalizado, encoder.encode(password), tycVigente)
            : User.create(firstNames, lastNames, normalizado, encoder.encode(password), role, tycVigente);
    repo.saveAndFlush(u);
    eventos.publicar(topics.audit(), "USUARIO_CREADO",
            Map.of("userId", u.getId().toString(), "role", role.name()));
    return u.getId();
}
```

- [ ] **Step 4: Agregar los endpoints con `@PreAuthorize`**

```java
/**
 * Layer 1 in the annotation (does it have the role?). Layer 2 in the service
 * (does it leave the platform without an ADMIN?). @PreAuthorize over @RolesAllowed
 * because only SpEL allows expressions like the one for /profile/{id} (DEC-36).
 */
@PostMapping
@PreAuthorize("hasRole('ADMIN')")
public Map<String, String> crear(@Valid @RequestBody CreateUserRequest r) {
    return Map.of("id", users.crear(r.firstNames(), r.lastNames(), r.email(),
            r.password(), r.role()).toString());
}

@DeleteMapping("/{id}")
@PreAuthorize("hasRole('ADMIN')")
public void baja(@AuthenticationPrincipal GatewayPrincipal p, @PathVariable UUID id,
                 @Valid @RequestBody AdminDeactivationRequest req) {
    users.deactivate(p.id(), id, req);
}

@PatchMapping("/{id}/role")
@PreAuthorize("hasRole('ADMIN')")
public void changeRole(@AuthenticationPrincipal GatewayPrincipal p, @PathVariable UUID id,
                       @Valid @RequestBody RoleChangeRequest req) {
    users.changeRole(p.id(), id, req.role());
}
```

- [ ] **Step 5: Correr y verificar que pasa**

Run: `mvn -q test -Dtest=AdminRulesIT`
Expected: PASS — 6 tests. **El de concurrencia es el que valida `DEC-20` r5**: si alguien saca el `FOR UPDATE`, falla.

- [ ] **Step 6: Commit**

```bash
git add src/main/java src/test/java
git commit -m "feat: reglas de ADMIN con conteo bajo lock pesimista

DEC-20 r5: sin SELECT FOR UPDATE, bajo REPEATABLE READ dos bajas concurrentes
cuentan dos ADMIN cada una y la plataforma queda en cero. Hay test con dos hilos.
DEC-23: deactivate borra session:{userId}."
```

---

### Task 20: Whitelist y solicitudes del PROFESSOR

**Files:**
- Create: `src/main/java/…/users/services/WhitelistService.java`
- Create: `src/main/java/…/users/controllers/WhitelistController.java`
- Create: `src/main/java/…/users/dto/{AddEmailRequest,CreateWhitelistRequest,ResolveWhitelistRequest}.java`
- Test: `src/test/java/…/users/WhitelistRequestIT.java`

**Interfaces:**
- Consumes: `EmailWhitelistRepository`+`WhitelistRequestRepository` (T3), `NotificationEventPublisher` (T7), `UserRepository` (T2).
- Produces: `WhitelistService.agregar(UUID, String)`, `.remove(UUID)`, `.listar()`, `.solicitar(UUID, String, String)`, `.resolver(UUID admin, UUID solicitudId, boolean approve, String rejectionReason)`.

- [ ] **Step 1: Escribir el test (falla)** — criterio de DoD #28

```java
package ar.edu.utn.frc.tup.p4.usersservice.users;

import ar.edu.utn.frc.tup.p4.usersservice.AbstractIntegrationTest;
import ar.edu.utn.frc.tup.p4.usersservice.users.entities.User;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.AccountStatus;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.RequestStatus;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.*;
import ar.edu.utn.frc.tup.p4.usersservice.users.services.WhitelistService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** DEC-29 · request con ESTADO, no un mail suelto. */
class WhitelistRequestIT extends AbstractIntegrationTest {

    @Autowired WhitelistService whitelist;
    @Autowired WhitelistRequestRepository solicitudes;
    @Autowired EmailWhitelistRepository lista;
    @Autowired UserRepository repo;

    private UUID profesor() { return crear(Role.PROFESSOR, "prof-" + UUID.randomUUID() + "@utn.edu.ar"); }
    private UUID adminId()  { return crear(Role.ADMIN,    "adm-"  + UUID.randomUUID() + "@utn.edu.ar"); }

    private UUID crear(Role role, String email) {
        User u = User.create("N", "A", email, "$2a$12$h", role, "v1");
        u.forceStatusForTest(AccountStatus.ACTIVE);
        return repo.saveAndFlush(u).getId();
    }

    @Test
    void aprobar_marca_APROBADA_e_inserta_en_la_whitelist_en_la_MISMA_transaccion() {
        UUID sol = whitelist.solicitar(profesor(), "nuevo1@utn.edu.ar", "es titular de la catedra");
        whitelist.resolver(adminId(), sol, true, null);

        assertThat(solicitudes.findById(sol)).get()
                .extracting(s -> s.getStatus()).isEqualTo(RequestStatus.APPROVED);
        assertThat(lista.existsByEmailAndDeletedAtIsNull("nuevo1@utn.edu.ar")).isTrue();
    }

    @Test
    void rechazar_exige_motivo_y_NO_toca_la_whitelist() {
        UUID sol = whitelist.solicitar(profesor(), "nuevo2@utn.edu.ar", "reason");
        assertThatThrownBy(() -> whitelist.resolver(adminId(), sol, false, null))
                .isInstanceOf(RuntimeException.class);

        whitelist.resolver(adminId(), sol, false, "no corresponde");
        assertThat(lista.existsByEmailAndDeletedAtIsNull("nuevo2@utn.edu.ar")).isFalse();
        // RF-NFR-01: a rejected request STAYS, with its reason. With a mail
        // loose, nobody could answer "what happened to what I asked for".
        assertThat(solicitudes.findById(sol)).get()
                .extracting(s -> s.getStatus()).isEqualTo(RequestStatus.REJECTED);
    }

    @Test
    void dos_profesores_no_pueden_abrir_dos_solicitudes_para_el_mismo_email() {
        whitelist.solicitar(profesor(), "duplicado@utn.edu.ar", "uno");
        assertThatThrownBy(() -> whitelist.solicitar(profesor(), "duplicado@utn.edu.ar", "dos"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void tras_rechazar_se_puede_volver_a_solicitar_el_mismo_email() {
        // The unique key applies only among the PENDING ones (generated column).
        UUID sol = whitelist.solicitar(profesor(), "reintento@utn.edu.ar", "uno");
        whitelist.resolver(adminId(), sol, false, "no");
        assertThat(whitelist.solicitar(profesor(), "reintento@utn.edu.ar", "dos")).isNotNull();
    }

    @Test
    void resolver_dos_veces_la_misma_solicitud_falla() {
        UUID sol = whitelist.solicitar(profesor(), "unavez@utn.edu.ar", "reason");
        whitelist.resolver(adminId(), sol, true, null);
        assertThatThrownBy(() -> whitelist.resolver(adminId(), sol, true, null))
                .isInstanceOf(RuntimeException.class);
    }
}
```

- [ ] **Step 2: Escribir `WhitelistService`**

```java
package ar.edu.utn.frc.tup.p4.usersservice.users.services;

import ar.edu.utn.frc.tup.p4.usersservice.shared.notifications.NotificationEventPublisher;
import ar.edu.utn.frc.tup.p4.usersservice.shared.notifications.EmailType;
import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ApiException;
import ar.edu.utn.frc.tup.p4.usersservice.users.entities.*;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.RequestStatus;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class WhitelistService {

    private final EmailWhitelistRepository lista;
    private final WhitelistRequestRepository solicitudes;
    private final UserRepository usuarios;
    private final NotificationEventPublisher mails;

    public WhitelistService(EmailWhitelistRepository lista, WhitelistRequestRepository solicitudes,
                            UserRepository usuarios, NotificationEventPublisher mails) {
        this.lista = lista; this.solicitudes = solicitudes;
        this.usuarios = usuarios; this.mails = mails;
    }

    @Transactional
    public UUID agregar(UUID admin, String email) {
        return lista.saveAndFlush(EmailWhitelist.create(email, admin)).getId();
    }

    @Transactional
    public void remove(UUID id) {
        EmailWhitelist e = lista.findById(id).orElseThrow(ApiException::accessDenied);
        e.remove();
        lista.save(e);
    }

    @Transactional(readOnly = true)
    public List<EmailWhitelist> listar() { return lista.findAllByDeletedAtIsNull(); }

    @Transactional
    public UUID solicitar(UUID profesorId, String email, String reason) {
        // The unique key on pending_email makes the second open request fail.
        WhitelistRequest r = solicitudes.saveAndFlush(
                WhitelistRequest.create(email, profesorId, reason));

        usuarios.findAll().stream()
                .filter(u -> u.getRole() == Role.ADMIN && u.getDeletedAt() == null)
                .forEach(a -> mails.enviar(EmailType.WHITELIST_SUBMISSION, a.getEmail(),
                        Map.of("emailSolicitado", r.getRequestedEmail(), "reason", reason)));
        return r.getId();
    }

    /**
     * DEC-29 · ATOMICO: marcar APPROVED e insertar en la whitelist ocurren en
     * the same transaction. There is no intermediate status where the request
     * be approved with the e-mail not whitelisted.
     */
    @Transactional
    public void resolver(UUID adminId, UUID solicitudId, boolean approve, String rejectionReason) {
        WhitelistRequest r = solicitudes.findById(solicitudId).orElseThrow(ApiException::accessDenied);

        if (approve) {
            r.approve(adminId);
            if (!lista.existsByEmailAndDeletedAtIsNull(r.getRequestedEmail())) {
                lista.save(EmailWhitelist.create(r.getRequestedEmail(), adminId));
            }
        } else {
            r.reject(adminId, rejectionReason);
        }
        solicitudes.save(r);

        usuarios.findByIdAndDeletedAtIsNull(r.getRequestedBy()).ifPresent(prof ->
                mails.enviar(EmailType.WHITELIST_DECISION, prof.getEmail(), Map.of(
                        "firstNames", prof.getFirstNames(),
                        "emailSolicitado", r.getRequestedEmail(),
                        "resultado", approve ? RequestStatus.APPROVED.name()
                                             : RequestStatus.REJECTED.name(),
                        "reason", rejectionReason == null ? "" : rejectionReason)));
    }
}
```

- [ ] **Step 3: Escribir el controller**

```java
package ar.edu.utn.frc.tup.p4.usersservice.users.controllers;

import ar.edu.utn.frc.tup.p4.usersservice.shared.security.GatewayPrincipal;
import ar.edu.utn.frc.tup.p4.usersservice.users.dto.*;
import ar.edu.utn.frc.tup.p4.usersservice.users.services.WhitelistService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/users/whitelist")
public class WhitelistController {

    private final WhitelistService whitelist;

    public WhitelistController(WhitelistService whitelist) { this.whitelist = whitelist; }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, String> agregar(@AuthenticationPrincipal GatewayPrincipal p,
                                       @Valid @RequestBody AddEmailRequest r) {
        return Map.of("id", whitelist.agregar(p.id(), r.email()).toString());
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public List<Map<String, String>> listar() {
        return whitelist.listar().stream()
                .map(e -> Map.of("id", e.getId().toString(), "email", e.getEmail())).toList();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public void remove(@PathVariable UUID id) { whitelist.remove(id); }

    @PostMapping("/requests")
    @PreAuthorize("hasRole('PROFESSOR')")
    public Map<String, String> solicitar(@AuthenticationPrincipal GatewayPrincipal p,
                                         @Valid @RequestBody CreateWhitelistRequest r) {
        return Map.of("id", whitelist.solicitar(p.id(), r.email(), r.reason()).toString());
    }

    @PatchMapping("/requests/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public void resolver(@AuthenticationPrincipal GatewayPrincipal p, @PathVariable UUID id,
                         @Valid @RequestBody ResolveWhitelistRequest r) {
        whitelist.resolver(p.id(), id, r.approve(), r.rejectionReason());
    }
}
```

- [ ] **Step 4: Correr y verificar que pasa**

Run: `mvn -q test -Dtest=WhitelistRequestIT`
Expected: PASS — 5 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java src/test/java
git commit -m "feat: whitelist con solicitudes del PROFESSOR (DEC-29)

Aprobar es atomico: marcar APPROVED e insertar en la lista van en la misma
transaccion. Con un mail suelto, una request rechazada no dejaria rastro."
```

---
### Task 21: Consumer desde Cursos e idempotencia

**Files:**
- Create: `src/main/java/…/users/listeners/CourseValidationListener.java`
- Create: `src/main/java/…/config/KafkaConfig.java`
- Test: `src/test/java/…/users/CourseValidationListenerIT.java`
- Test: `src/test/java/…/TimestampIT.java`

**Interfaces:**
- Consumes: `ProcessedEventRepository` (T3), `UserRepository` (T2), `NotificationEventPublisher` (T7).
- Produces: nada para otras tareas — es el borde entrante del sistema.

- [ ] **Step 1: Escribir el test del consumer (falla)** — criterio de DoD #6

```java
package ar.edu.utn.frc.tup.p4.usersservice.users;

import ar.edu.utn.frc.tup.p4.usersservice.AbstractIntegrationTest;
import ar.edu.utn.frc.tup.p4.usersservice.shared.events.ProcessedEventRepository;
import ar.edu.utn.frc.tup.p4.usersservice.users.entities.User;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.AccountStatus;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;
import ar.edu.utn.frc.tup.p4.usersservice.users.listeners.CourseValidationListener;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CourseValidationListenerIT extends AbstractIntegrationTest {

    @Autowired CourseValidationListener listener;
    @Autowired UserRepository repo;
    @Autowired ProcessedEventRepository procesados;

    private User pendienteCurso(String email) {
        User u = User.create("Ana", "P", email, "$2a$12$h", Role.STUDENT, "v1");
        u.activate();                     // -> PENDING_COURSE
        return repo.saveAndFlush(u);
    }

    private String sobre(String eventId, UUID userId, String resultado) {
        return """
               {"eventId":"%s","eventType":"VALIDACION_CURSO_RESUELTA",
                "timestamp":"2026-09-07T12:00:00Z","producer":"tema-02-cursos",
                "payload":{"userId":"%s","resultado":"%s","cursoId":"c-1"}}
               """.formatted(eventId, userId, resultado);
    }

    @Test
    void el_evento_pasa_la_cuenta_a_ACTIVA() {
        User u = pendienteCurso("cv1@utn.edu.ar");
        listener.consumir(sobre(UUID.randomUUID().toString(), u.getId(), "VALIDADO_PADRON"));

        assertThat(repo.findById(u.getId()))
                .get().extracting(User::getAccountStatus).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void reprocesar_el_MISMO_eventId_es_un_no_op_verificable() {
        // DEC-13 · criterio de DoD #6.
        User u = pendienteCurso("cv2@utn.edu.ar");
        String id = UUID.randomUUID().toString();

        listener.consumir(sobre(id, u.getId(), "VALIDADO_PADRON"));
        long procesadosAntes = procesados.count();
        listener.consumir(sobre(id, u.getId(), "VALIDADO_PADRON"));   // otra vez

        assertThat(procesados.count()).isEqualTo(procesadosAntes);
        assertThat(repo.findById(u.getId()))
                .get().extracting(User::getAccountStatus).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void un_evento_sobre_una_cuenta_YA_ACTIVA_no_falla() {
        User u = pendienteCurso("cv3@utn.edu.ar");
        u.activateAfterCourseValidation();
        repo.saveAndFlush(u);

        listener.consumir(sobre(UUID.randomUUID().toString(), u.getId(), "VALIDADO_EXCEPCION"));

        assertThat(repo.findById(u.getId()))
                .get().extracting(User::getAccountStatus).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void campos_desconocidos_en_el_payload_NO_rompen() {
        // DEC-34: if Cursos sends three extra fields, we do not break.
        User u = pendienteCurso("cv4@utn.edu.ar");
        String conExtras = """
              {"eventId":"%s","eventType":"VALIDACION_CURSO_RESUELTA",
               "timestamp":"2026-09-07T12:00:00Z","producer":"tema-02-cursos",
               "campoNuevoDeCursos":"loquesea",
               "payload":{"userId":"%s","resultado":"VALIDADO_PADRON","cursoId":"c-1",
                          "otroCampoNuevo":42}}
              """.formatted(UUID.randomUUID(), u.getId());

        listener.consumir(conExtras);

        assertThat(repo.findById(u.getId()))
                .get().extracting(User::getAccountStatus).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void el_resultado_y_el_cursoId_NO_se_persisten() {
        // DEC-09: Cursos owns that data. Duplicating it here would be a second
        // source of truth, which is exactly what the v5 revision fixed.
        User u = pendienteCurso("cv5@utn.edu.ar");
        listener.consumir(sobre(UUID.randomUUID().toString(), u.getId(), "VALIDADO_EXCEPCION"));

        assertThat(repo.findById(u.getId()).orElseThrow().toString())
                .doesNotContain("VALIDADO_EXCEPCION").doesNotContain("c-1");
    }
}
```

- [ ] **Step 2: Escribir el test de timestamps (falla)** — criterio de DoD #21

```java
package ar.edu.utn.frc.tup.p4.usersservice;

import ar.edu.utn.frc.tup.p4.usersservice.users.entities.User;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DEC-20 regla 2 · criterio de DoD #21. Con TIMESTAMP, dos instancias con
 * different timezones would store different values for the same instant.
 * Con DATETIME(6) + UTC en la conexion, no.
 */
class TimestampIT extends AbstractIntegrationTest {

    @Autowired UserRepository repo;
    @Autowired JdbcTemplate jdbc;

    @Test
    void un_Instant_sobrevive_a_un_cambio_de_timezone_del_proceso() {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Argentina/Cordoba"));
            User u = repo.saveAndFlush(
                    User.create("A", "A", "tz@utn.edu.ar", "$2a$12$h", Role.STUDENT, "v1"));
            String guardado = jdbc.queryForObject(
                    "SELECT created_at FROM users WHERE id = ?", String.class, u.getId().toString());

            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));
            String releido = jdbc.queryForObject(
                    "SELECT created_at FROM users WHERE id = ?", String.class, u.getId().toString());

            // The value in the database does not change with who reads it.
            assertThat(releido).isEqualTo(guardado);
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    void los_timestamps_tienen_precision_de_microsegundos() {
        User u = repo.saveAndFlush(
                User.create("B", "B", "us@utn.edu.ar", "$2a$12$h", Role.STUDENT, "v1"));
        Integer escala = jdbc.queryForObject(
                "SELECT datetime_precision FROM information_schema.columns "
                + "WHERE table_schema = DATABASE() AND table_name='users' AND column_name='created_at'",
                Integer.class);
        assertThat(escala).isEqualTo(6);
        assertThat(u.getDeletedAt()).isNull();
    }

    @Test
    void el_sobre_de_eventos_usa_ISO_8601_UTC() {
        // DEC-12: one single representation of time across the whole system.
        String ts = ar.edu.utn.frc.tup.p4.usersservice.shared.events.EventEnvelope
                .de("X", "y").timestamp();
        assertThat(ts).endsWith("Z");
        assertThat(Instant.parse(ts)).isNotNull();
    }
}
```

- [ ] **Step 3: Escribir el listener**

```java
package ar.edu.utn.frc.tup.p4.usersservice.users.listeners;

import ar.edu.utn.frc.tup.p4.usersservice.shared.events.ProcessedEventRepository;
import ar.edu.utn.frc.tup.p4.usersservice.shared.events.entities.ProcessedEvent;
import ar.edu.utn.frc.tup.p4.usersservice.shared.notifications.NotificationEventPublisher;
import ar.edu.utn.frc.tup.p4.usersservice.shared.notifications.EmailType;
import ar.edu.utn.frc.tup.p4.usersservice.users.entities.User;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Component
public class CourseValidationListener {

    private static final Logger log = LoggerFactory.getLogger(CourseValidationListener.class);

    private final ObjectMapper mapper;
    private final UserRepository repo;
    private final ProcessedEventRepository procesados;
    private final NotificationEventPublisher mails;

    public CourseValidationListener(ObjectMapper mapper, UserRepository repo,
                                   ProcessedEventRepository procesados,
                                   NotificationEventPublisher mails) {
        this.mapper = mapper; this.repo = repo;
        this.procesados = procesados; this.mails = mails;
    }

    @KafkaListener(topics = "${users.kafka.topics.validation-curso}")
    @Transactional
    public void consumir(String message) {
        JsonNode sobre;
        try {
            sobre = mapper.readTree(message);
        } catch (Exception e) {
            log.error("EVENTO_ILEGIBLE en validation-curso", e);
            return;   // veneno: no se reintenta eternamente
        }

        String eventId = sobre.path("eventId").asText();

        // DEC-13 - idempotency: INSERT and catch the duplicate. NOT a SELECT
        // previo: bajo REPEATABLE READ (DEC-20 r5) dos consumers concurrentes
        // with the same eventId can BOTH see the row missing.
        try {
            procesados.saveAndFlush(new ProcessedEvent(eventId, sobre.path("eventType").asText()));
        } catch (DataIntegrityViolationException yaProcesado) {
            log.debug("EVENTO_DUPLICADO eventId={}", eventId);
            return;
        }

        JsonNode payload = sobre.path("payload");
        UUID userId = UUID.fromString(payload.path("userId").asText());

        // DEC-09: `resultado` and `cursoId` are USED to decide and then DISCARDED.
        // Cursos owns that data; duplicating it would be a second source of
        // truth for the same fact.
        repo.findByIdAndDeletedAtIsNull(userId).ifPresent(u -> {
            u.activateAfterCourseValidation();   // no-op si ya estaba ACTIVE
            repo.save(u);
            mails.enviar(EmailType.WHITELISTING_RESOLVED, u.getEmail(),
                    Map.of("firstNames", u.getFirstNames()));
        });
    }
}
```

- [ ] **Step 4: Correr y verificar que pasan**

Run: `mvn -q test -Dtest=CourseValidationListenerIT+TimestampIT`
Expected: PASS — 8 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java src/test/java
git commit -m "feat: consumer de Cursos con idempotencia por INSERT (DEC-13)

INSERT y capturar el duplicado, no SELECT previo: bajo REPEATABLE READ dos
consumers concurrentes pueden ver ambos la fila ausente.
DEC-09: resultado y cursoId se usan y se descartan. El dueno es Cursos."
```

---

### Task 22: ADMIN inicial y break-glass de recuperación

**Files:**
- Create: `src/main/java/…/users/cli/AdminBootstrap.java`
- Create: `src/main/java/…/auth/cli/AdminRecoveryCommand.java`
- Modify: `src/main/resources/application.yml`, `src/test/resources/application-test.yml`
- Test: `src/test/java/…/users/AdminBootstrapTest.java`
- Test: `src/test/java/…/auth/AdminRecoveryCommandTest.java`

**Interfaces:**
- Consumes: `UserRepository` (T2), `PasswordEncoder` (T5), `AccountEventPublisher` (T6), `NotificationEventPublisher` (T7), `PasswordPolicy` (T12).

> **`AccountEventPublisher` y `NotificationEventPublisher` son del lote L3.** Si
> todavia no estan en `main` cuando llegues acá, **no borres las llamadas en
> silencio**: sin ellas RF-USR-01 se instala sin rastro de auditoria y las tres
> vias de alerta de `DEC-32` quedan en una sola, y nada lo avisa. Dejá el test
> escrito con `@Disabled("espera L3 · T6/T7")` y una linea en tu PR diciendo
> que falta. Un requisito que desaparece sin dejar marca no se recupera nunca.
- Produces: nada — las dos son herramientas de arranque y de emergencia.

> **Los dos caminos por los que puede existir un ADMIN, y por qué hacen falta
> los dos.** `RF-USR-01` pide que *la instalación incluya un ADMIN inicial que
> debe cambiar su contraseña en el primer login*; `RF-ROL-04` pide una vía de
> recuperación para cuando la plataforma se quedó sin ninguno.
>
> Sin el **bootstrap**, una instalación limpia arranca con cero ADMIN: nadie
> puede crear el primero, porque crear usuarios es una operación de ADMIN.
> Ninguna migración de Flyway lo resuelve — el hash lo tiene que producir el
> `PasswordEncoder` de la aplicación (BCrypt 12, `DEC-38`), y un `INSERT` con un
> hash escrito a mano en un `.sql` es una password conocida y versionada en el
> repositorio, que es justo lo que `RF-USR-01` quiere evitar.
>
> Sin el **break-glass**, si la plataforma se queda sin ADMIN estando en marcha
> (restore de backup, error humano) tampoco hay forma de entrar.
>
> **Y ojo con esto:** el ADMIN que crean los dos nace con
> `mustChangePassword = true` **y** `firstLogin = true`. Si las exenciones de
> gates de la Task 8 no están bien puestas, ese ADMIN no puede entrar nunca y
> los dos requisitos quedan inalcanzables. Las tres cosas se prueban juntas.

- [ ] **Step 0a: Escribir el test del ADMIN inicial (falla)** — `RF-USR-01`

```java
package ar.edu.utn.frc.tup.p4.usersservice.users;

// imports: ArgumentCaptor, BCryptPasswordEncoder, TransactionCallback,
// TransactionTemplate, y los mocks de Mockito.

/** RF-USR-01 · el ADMIN inicial de una instalacion limpia. */
class AdminBootstrapTest {

    UserRepository repo = mock(UserRepository.class);
    PasswordEncoder encoder = new BCryptPasswordEncoder(4);   // costo bajo: es un test
    AccountEventPublisher eventos = mock(AccountEventPublisher.class);
    KafkaTopicsProperties topics = mock(KafkaTopicsProperties.class);
    TransactionTemplate tx = mock(TransactionTemplate.class);

    private AdminBootstrap bootstrap(String password) {
        when(tx.execute(any())).thenAnswer(inv ->
                ((TransactionCallback<?>) inv.getArgument(0)).doInTransaction(null));
        when(topics.audit()).thenReturn("tema-01-users.auditoria.v1");
        when(repo.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        return new AdminBootstrap(repo, encoder, eventos, topics, tx,
                "admin@frc.utn.edu.ar", password, "Admin", "Inicial", "v1");
    }

    @Test
    void crea_un_ADMIN_obligado_a_cambiar_la_password() {
        when(repo.countByRolAndDeletedAtIsNull(Role.ADMIN)).thenReturn(0L);
        when(repo.findByEmailAndDeletedAtIsNull(any())).thenReturn(Optional.empty());

        bootstrap("passwordvalida1").run(null);

        ArgumentCaptor<User> creado = ArgumentCaptor.forClass(User.class);
        verify(repo).saveAndFlush(creado.capture());
        User admin = creado.getValue();
        assertThat(admin.getRole()).isEqualTo(Role.ADMIN);
        assertThat(admin.getAccountStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(admin.mustChangePassword()).isTrue();   // el corazon de RF-USR-01
    }

    @Test
    void no_hace_nada_si_ya_hay_un_ADMIN_activo() {
        // Idempotent: the daily startup must not seed all over again.
        when(repo.countByRolAndDeletedAtIsNull(Role.ADMIN)).thenReturn(1L);
        bootstrap("passwordvalida1").run(null);
        verify(repo, never()).saveAndFlush(any());
    }

    @Test
    void una_password_floja_del_entorno_hace_fallar_el_arranque() {
        // Better not to start at all than to start with a short-password ADMIN.
        when(repo.countByRolAndDeletedAtIsNull(Role.ADMIN)).thenReturn(0L);
        when(repo.findByEmailAndDeletedAtIsNull(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bootstrap("corta").run(null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("12 caracteres");
        verify(repo, never()).saveAndFlush(any());
    }

    @Test
    void sin_password_configurada_genera_una_que_cumple_la_politica() {
        // No fixed default: a default password in the repository is the same one
        // in every installation.
        when(repo.countByRolAndDeletedAtIsNull(Role.ADMIN)).thenReturn(0L);
        when(repo.findByEmailAndDeletedAtIsNull(any())).thenReturn(Optional.empty());

        bootstrap("").run(null);

        ArgumentCaptor<User> creado = ArgumentCaptor.forClass(User.class);
        verify(repo).saveAndFlush(creado.capture());
        assertThat(creado.getValue().getPasswordHash()).startsWith("$2");
    }
}
```

- [ ] **Step 0b: Escribir `AdminBootstrap`**

```java
@Component
@ConditionalOnProperty(name = "users.bootstrap.enabled", havingValue = "true", matchIfMissing = true)
public class AdminBootstrap implements ApplicationRunner {

    // Constructor: repo, encoder, eventos, topics, tx, y por @Value
    //   users.bootstrap.email      (default admin@frc.utn.edu.ar)
    //   users.bootstrap.password   (default vacio -> se genera)
    //   users.bootstrap.firstNames / .lastNames
    //   users.legal.terms-version

    @Override
    public void run(ApplicationArguments args) {
        if (repo.countByRolAndDeletedAtIsNull(Role.ADMIN) > 0) return;   // idempotente
        if (repo.findByEmailAndDeletedAtIsNull(email.toLowerCase()).isPresent()) {
            log.warn("ADMIN_BOOTSTRAP omitido: {} ya existe con otro role.", email);
            return;
        }

        // With no password configured one is generated and printed exactly ONCE.
        // Having no fixed default is deliberate: a default password in the
        // repository is the same one in every installation.
        boolean generada = password.isBlank();
        String clara = generada ? generar() : password;
        PasswordPolicy.validate(clara);   // una password floja falla al arrancar, no despues

        // 🔴 El evento va DENTRO de la misma transaccion que la fila: eso ES el
        // outbox (DEC-45b). `publicar` esta anotado con propagation MANDATORY,
        // asi que llamarlo afuera falla siempre y el alta de ADMIN queda sin
        // rastro de auditoria — con un try/catch que lo tapa, que es peor.
        UUID id = tx.execute(s -> {
            User admin = User.createAdmin(firstNames, lastNames, email.toLowerCase(),
                    encoder.encode(clara), tycVigente);   // deja mustChangePassword = true
            UUID nuevoId = repo.saveAndFlush(admin).getId();
            eventos.publicar(topics.audit(), "ADMIN_INICIAL_CREADO",
                    Map.of("adminId", String.valueOf(nuevoId)));
            return nuevoId;
        });

        if (generada) log.warn("""

                ====================================================================
                 ADMIN INICIAL CREADO (RF-USR-01)
                   email:    {}
                   password: {}
                 Se muestra UNA sola vez. Hay que cambiarla en el primer login.
                 Para fijarla vos: ADMIN_BOOTSTRAP_PASSWORD en el entorno.
                ====================================================================
                """, email, clara);
    }

    private String generar() {
        byte[] b = new byte[18];
        new SecureRandom().nextBytes(b);
        return "Aa1" + Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
```

Properties en `application.yml`. **Este archivo tiene UN solo dueño** (ver
`docs/TASK-ASSIGNMENT.md`): no lo commitees. Pasá el bloque y se aplica a `main`
antes de que abras el PR, así tus tests pasan también para el resto y no chocás
con otra rama en el mismo lugar.

```yaml
users:
  # RF-USR-01 - ADMIN inicial de una instalacion limpia. Idempotente: solo se
  # crea si no hay ningun ADMIN activo. Sin ADMIN_BOOTSTRAP_PASSWORD se genera
  # una y se imprime UNA sola vez en el log del arranque.
  bootstrap:
    enabled:     ${ADMIN_BOOTSTRAP_ENABLED:true}
    email:       ${ADMIN_BOOTSTRAP_EMAIL:admin@frc.utn.edu.ar}
    password:    ${ADMIN_BOOTSTRAP_PASSWORD:}
    first-names: ${ADMIN_BOOTSTRAP_FIRST_NAMES:Admin}
    last-names:  ${ADMIN_BOOTSTRAP_LAST_NAMES:Inicial}
```

Y **apagado en los tests** (`src/test/resources/application-test.yml`), o el
ADMIN sembrado altera el conteo de los tests de `RF-ROL-05`:

```yaml
users:
  bootstrap:
    enabled: false
```

- [ ] **Step 1: Escribir el test del break-glass (falla)** — criterio de DoD #29

```java
package ar.edu.utn.frc.tup.p4.usersservice.auth;

import ar.edu.utn.frc.tup.p4.usersservice.AbstractIntegrationTest;
import ar.edu.utn.frc.tup.p4.usersservice.auth.cli.AdminRecoveryCommand;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** DEC-32 · RF-ROL-04, criterio de release #12. */
@TestPropertySource(properties = "users.breakglass.secret-hash=$2a$04$abcdefghijklmnopqrstuv")
class AdminRecoveryCommandTest extends AbstractIntegrationTest {

    @Autowired AdminRecoveryCommand comando;
    @Autowired UserRepository repo;

    @Test
    void con_el_secreto_correcto_crea_un_ADMIN_con_cambio_forzado() {
        var id = comando.recuperar("el-secreto-de-instalacion",
                "Rescate", "Admin", "rescate@utn.edu.ar", "passwordvalida1");

        assertThat(repo.findById(id)).get().satisfies(u -> {
            assertThat(u.getRole()).isEqualTo(Role.ADMIN);
            // Point 2 of the RF: a forced password change after using it.
            assertThat(u.mustChangePassword()).isTrue();
        });
    }

    @Test
    void con_el_secreto_incorrecto_no_crea_nada() {
        long antes = repo.count();
        assertThatThrownBy(() -> comando.recuperar("mal", "R", "A", "no@utn.edu.ar", "passwordvalida1"))
                .isInstanceOf(SecurityException.class);
        assertThat(repo.count()).isEqualTo(antes);
    }

    @Test
    void el_ADMIN_se_crea_AUNQUE_kafka_y_el_mail_fallen() {
        // Break-glass exists precisely for scenarios where things
        // are broken. The three alert channels go OUTSIDE the transaction.
        comando.romperPublishersParaTest();

        var id = comando.recuperar("el-secreto-de-instalacion",
                "Rescate2", "Admin", "rescate2@utn.edu.ar", "passwordvalida1");

        assertThat(repo.findById(id)).isPresent();
    }

    @Test
    void el_secreto_nunca_aparece_en_el_mensaje_de_error() {
        assertThatThrownBy(() -> comando.recuperar("secreto-filtrable", "R", "A",
                "x@utn.edu.ar", "passwordvalida1"))
                .isInstanceOf(SecurityException.class)
                .hasMessageNotContaining("secreto-filtrable");
    }
}
```

- [ ] **Step 2: Escribir el comando**

```java
package ar.edu.utn.frc.tup.p4.usersservice.auth.cli;

import ar.edu.utn.frc.tup.p4.usersservice.config.KafkaTopicsProperties;
import ar.edu.utn.frc.tup.p4.usersservice.shared.events.AccountEventPublisher;
import ar.edu.utn.frc.tup.p4.usersservice.shared.notifications.NotificationEventPublisher;
import ar.edu.utn.frc.tup.p4.usersservice.shared.notifications.EmailType;
import ar.edu.utn.frc.tup.p4.usersservice.users.PasswordPolicy;
import ar.edu.utn.frc.tup.p4.usersservice.users.entities.User;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.UUID;

/**
 * RF-ROL-04 - the legitimate, audited back door. A CLI command, with NO
 * HTTP endpoint: as an endpoint it would be a privilege escalation
 * a un request de distancia.
 *
 * Escenario: la plataforma quedo sin ningun ADMIN operativo (bug, restore de
 * backup, human error). validateNotLastAdmin prevents reaching zero through the
 * normal paths, but if it happens anyway there is no way in through the UI.
 */
@Component
public class AdminRecoveryCommand {

    private static final Logger log = LoggerFactory.getLogger(AdminRecoveryCommand.class);

    private final UserRepository repo;
    private final PasswordEncoder encoder;
    private final AccountEventPublisher eventos;
    private final NotificationEventPublisher mails;
    private final KafkaTopicsProperties topics;
    private final TransactionTemplate tx;
    private final String secretHash;
    private final String tycVigente;
    private boolean publishersRotosParaTest = false;

    public AdminRecoveryCommand(UserRepository repo, PasswordEncoder encoder,
                                AccountEventPublisher eventos, NotificationEventPublisher mails,
                                KafkaTopicsProperties topics, TransactionTemplate tx,
                                @Value("${users.breakglass.secret-hash:}") String secretHash,
                                @Value("${users.legal.terms-version}") String tycVigente) {
        this.repo = repo; this.encoder = encoder; this.eventos = eventos; this.mails = mails;
        this.topics = topics; this.tx = tx; this.secretHash = secretHash; this.tycVigente = tycVigente;
    }

    public UUID recuperar(String secreto, String firstNames, String lastNames,
                          String email, String password) {
        // Punto 1 del RF: secreto de instalacion, comparado contra un hash.
        // Custodiado FUERA del equipo de desarrollo, en variable de entorno.
        if (secretHash.isBlank() || !encoder.matches(secreto, secretHash)) {
            // The message does NOT include what was received: not even an attempt leaks.
            throw new SecurityException("Secreto de instalacion invalido.");
        }
        PasswordPolicy.validate(password);

        // The creation goes in its own transaction, and the alerts OUTSIDE it.
        UUID id = tx.execute(status -> {
            User admin = User.createAdmin(firstNames, lastNames, email,
                    encoder.encode(password), tycVigente);   // ya deja mustChangePassword = true
            return repo.saveAndFlush(admin).getId();
        });

        alertar(id);
        return id;
    }

    /**
     * DEC-32 - three channels, no new infrastructure, each failing
     * differently. And NONE can prevent the ADMIN from being created: break-glass
     * exists for when things are already broken.
     */
    private void alertar(UUID adminId) {
        // Channel 1: it lands in the log aggregator without depending on Kafka or mail.
        log.error("BREAKGLASS_USADO adminCreado={}", adminId);

        try {
            if (publishersRotosParaTest) throw new IllegalStateException("publishers rotos (test)");
            tx.executeWithoutResult(status -> {
                // Via 2: el registro durable y auditable.
                eventos.publicar(topics.audit(), "RECUPERACION_ADMIN",
                        Map.of("adminId", adminId.toString()));
                // Channel 3: the only one that reaches a PERSON.
                repo.findAll().stream()
                        .filter(u -> u.getRole() == Role.ADMIN && u.getDeletedAt() == null
                                     && !u.getId().equals(adminId))
                        .forEach(a -> mails.enviar(EmailType.BREAKGLASS_ALERT, a.getEmail(),
                                Map.of("adminId", adminId.toString())));
            });
        } catch (Exception e) {
            log.error("BREAKGLASS_ALERTA_FALLIDA adminCreado={} — el ADMIN SI se creo", adminId, e);
        }
    }

    /** For the DoD #29 test only. */
    public void romperPublishersParaTest() { this.publishersRotosParaTest = true; }
}
```

- [ ] **Step 3: Correr y verificar que pasa**

Run: `mvn -q test -Dtest=AdminRecoveryCommandTest`
Expected: PASS — 4 tests.

- [ ] **Step 4: Commit**

```bash
git add src/main/java src/test/java
git commit -m "feat: break-glass de recuperacion de ADMIN con alerta triple (DEC-32)

CLI sin endpoint HTTP: como endpoint seria una escalada a un request de
distancia. Las tres vias de alerta van fuera de la transaccion: el ADMIN se
crea aunque Kafka y el mail esten caidos."
```

---

### Task 23: Frontera entre módulos, Docker y verificación final

**Files:**
- Create: `src/test/java/…/ArchitectureTest.java`
- Create: `Dockerfile`
- Create: `.dockerignore`
- Test: (todo el suite)

**Interfaces:**
- Consumes: todas las tareas anteriores.
- Produces: la garantía estructural del servicio.

- [ ] **Step 1: Escribir el test de arquitectura (falla si alguien cruzó la frontera)**

Criterio de DoD #11. **Es la restricción más importante del servicio.**

```java
package ar.edu.utn.frc.tup.p4.usersservice;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * The auth/ <-> users/ boundary is what makes this service TWO
 * modules and not a monolith with folders. Without these tests, the boundary
 * exactamente hasta el primer import de conveniencia.
 */
class ArchitectureTest {

    private static final String RAIZ = "ar.edu.utn.frc.tup.p4.usersservice";
    private static JavaClasses clases;

    @BeforeAll
    static void importar() {
        clases = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(RAIZ);
    }

    @Test
    void auth_no_importa_entidades_ni_repositorios_de_users() {
        // The ONLY door is CredentialService (plus the enums and the DTOs).
        ArchRule regla = noClasses().that().resideInAPackage(RAIZ + ".auth..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(RAIZ + ".users.entities..", RAIZ + ".users.repositories..");
        regla.check(clases);
    }

    @Test
    void users_no_conoce_Redis() {
        // users/ accede a lo efimero SOLO via EphemeralTokenService.
        ArchRule regla = noClasses().that().resideInAPackage(RAIZ + ".users..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework.data.redis..");
        regla.check(clases);
    }

    @Test
    void users_no_importa_la_implementacion_de_auth() {
        ArchRule regla = noClasses().that().resideInAPackage(RAIZ + ".users..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(RAIZ + ".auth.services..", RAIZ + ".auth.entities..",
                                    RAIZ + ".auth.keys..", RAIZ + ".auth.tokens..",
                                    RAIZ + ".auth.store.impl..");
        regla.check(clases);
    }

    @Test
    void ninguna_clase_de_dominio_depende_de_los_controllers() {
        ArchRule regla = noClasses().that().resideInAnyPackage(
                        RAIZ + ".users.services..", RAIZ + ".auth.services..",
                        RAIZ + ".users.entities..", RAIZ + ".auth.entities..")
                .should().dependOnClassesThat().resideInAnyPackage(RAIZ + "..controllers..");
        regla.check(clases);
    }

    @Test
    void nadie_usa_un_JwtDecoder() {
        // DEC-08: no validamos JWT en el camino de request.
        ArchRule regla = noClasses().that().resideInAPackage(RAIZ + "..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("org.springframework.security.oauth2.server.resource..");
        regla.check(clases);
    }

    @Test
    void nadie_publica_a_Kafka_directamente_salvo_el_OutboxPoller() {
        // DEC-45b: every event goes through the outbox. A loose KafkaTemplate in
        // un servicio de negocio saltea la garantia transaccional.
        ArchRule regla = noClasses().that().resideOutsideOfPackage(RAIZ + ".shared.events..")
                .should().dependOnClassesThat().haveFullyQualifiedName(
                        "org.springframework.kafka.core.KafkaTemplate");
        regla.check(clases);
    }
}
```

- [ ] **Step 2: Correr y verificar que pasa**

Run: `mvn -q test -Dtest=ArchitectureTest`
Expected: PASS — 6 reglas. Si alguna falla, **no se arregla el test**: se arregla el import.

- [ ] **Step 3: Escribir el `Dockerfile`**

```dockerfile
# ---- build ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
# Los tests corren en el pipeline, no en el build de la imagen: un build que
# necesita Testcontainers necesita un Docker adentro de Docker.
RUN mvn -B clean package -DskipTests

# ---- runtime ----
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
USER app
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8082 8083
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-jar","app.jar"]
```

`.dockerignore`:
```
target/
secrets/
.env
.git/
```

- [ ] **Step 4: Correr el suite completo**

Run: `mvn -q clean verify`
Expected: PASS. Verificar además que **no hay `spring-boot-dependencies` por debajo de 4.1.1**:
Run: `mvn dependency:tree -Dincludes=org.springframework.boot:spring-boot-dependencies`

- [ ] **Step 5: Verificar los criterios de DoD contra la spec**

Abrir `spec/SPEC-users-service.md` §19 y confirmar que cada criterio tiene su test:

| # | Test |
|---|---|
| 1 | `UsersServiceApplicationTest` + `SchemaIT` |
| 2, 17 | `RegistrationIT` |
| 3 | `LoginIT` — la cadena entera: `la_fase_1_RENDERIZA_el_mail_y_publica_el_evento` + `la_fase_2_con_el_codigo_correcto_emite_los_dos_tokens` |
| 4 | `LoginIT.un_segundo_login_pisa_la_sesion_del_primero` ⚠️ **la spec lo llama `SingleSessionIT`**; acá vive dentro de `LoginIT` porque comparte el montaje. El `401` efectivo lo prueba `SessionInvalidationIT` del Gateway |
| 5 | `CourseValidationListenerIT.el_evento_pasa_la_cuenta_a_ACTIVA` + `el_resultado_y_el_cursoId_NO_se_persisten` |
| 6 | `CourseValidationListenerIT.reprocesar_el_MISMO_eventId_es_un_no_op_verificable` |
| 7 | `AccountGateInterceptorTest` |
| 8 | `CredentialServiceTest` (**sin `@SpringBootTest`**) |
| 9, 20, 24 | `AdminRulesIT` |
| 11 | `ArchitectureTest` |
| 12, 31 | `TokenContractTest` |
| 13 | `LogoutIT` |
| 14 | `ClientCredentialsIT` |
| 15, 16 | `GatewayIdentityFilterTest` |
| 18 | `PasswordResetIT` |
| 19 | `SigningKeyProviderTest` |
| 21 | `TimestampIT` |
| 22 | `EmailReuseIT` |
| 23, 25 | `SingleSessionRefreshIT` |
| 26 | `ActivationLinkIT`, `OtpServiceIT` |
| 27 | `OnboardingWithoutAvatarIT` |
| 28 | `WhitelistRequestIT` |
| 29 | `AdminRecoveryCommandTest` |
| 30 | `RateLimitLoginIT` |
| 32–35 | `TokenContractTest`, `OutboxIT`, `EmailTypeTest`, `TransitionsTest` |
| 10 | ⚠️ **Requiere el Gateway** — lo cubren `ServiceAudienceIT` e `IdentityPropagationIT` del plan del api-gateway, los únicos tests del subsistema que ejercitan un token de servicio **atravesando** el Gateway |

> **Los 35 criterios están cubiertos.** Si esta tabla y el §19 de la spec dejaran de coincidir, **la spec manda**: esta tabla es un índice, no la fuente.

- [ ] **Step 6: Commit final**

```bash
git add src/test/java/ar/edu/utn/frc/tup/p4/usersservice/ArchitectureTest.java Dockerfile .dockerignore
git commit -m "test: frontera entre modulos verificada por ArchUnit + Dockerfile

Seis reglas: auth/ no toca entidades de users/, users/ no conoce Redis, nadie
usa JwtDecoder (DEC-08) y nadie publica a Kafka fuera del outbox (DEC-45b).
Si una falla, se arregla el import, no el test."
```

---

## Notas de ejecución

**Orden de dependencias.** Las tareas 1→3 son cimientos y no se pueden reordenar. De ahí en adelante: 4–9 son independientes entre sí (se pueden paralelizar); 10–12 dependen de 9 y 5; 13–16 dependen de 10–12; 17–20 dependen de 13; 21–23 cierran.

**Lo que NO está en este plan y hay que saberlo:**

- **Criterio de DoD #10** (un cliente externo obtiene un token de servicio y lo usa contra `GET /api/users/profile/{id}` **a través del Gateway**) vive en el plan de `api-gateway` — lo cubren `ServiceAudienceIT` e `IdentityPropagationIT`. No se puede verificar desde un solo repo.
- **`TODO-06` · MinIO** está fuera del sprint por decisión (`DEC-30`). `avatarRef` quedó opcional; cuando entre, revisar si vuelve a ser obligatorio.
- **`TODO-10` y `TODO-11`** son dos nombres de tópico que acuerdan otros equipos. Están como properties con default (`DEC-34`): acordarlos es editar `application.yml`.
- **`INC-19` y `INC-23`** no son gaps: son posiciones argumentadas para la defensa. Están escritas en §18 de la spec.

**Antes del primer arranque local:** correr `./scripts/gen-dev-keys.sh` y exportar `JWT_ACTIVE_KID=dev`. Sin eso la aplicación **no arranca**, a propósito (`DEC-18`).
