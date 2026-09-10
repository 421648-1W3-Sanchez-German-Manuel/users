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
