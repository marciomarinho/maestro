package dev.maestro.payment;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Architectural rules, enforced by the build rather than by code review.
 *
 * <p>Every rule here corresponds to a claim made in the documentation. A claim that
 * only a human checks is a claim that will eventually stop being true.
 */
@AnalyzeClasses(
        packages = "dev.maestro",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    /**
     * The domain library depends on nothing (ADR-0001). It holds the money type, the
     * identifiers and the state machines, and it must stay testable in microseconds and
     * readable without knowing any framework.
     */
    @ArchTest
    static final ArchRule domainLibraryIsFrameworkFree = noClasses()
            .that().resideInAPackage("dev.maestro.domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..",
                    "tools.jackson..",
                    "com.fasterxml..",
                    "jakarta..",
                    "javax..",
                    "java.sql..",
                    "org.apache.kafka..")
            .because("lib-domain must remain a pure Java library with no framework coupling");

    /**
     * Services share an event contract and nothing else (ADR-0014). A compile-time
     * dependency between two services would turn a deployment boundary into a lie.
     */
    @ArchTest
    static final ArchRule servicesDoNotDependOnEachOther = noClasses()
            .that().resideInAPackage("dev.maestro.payment..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "dev.maestro.router..",
                    "dev.maestro.acquirersim..",
                    "dev.maestro.ledger..")
            .because("services communicate only through published events");

    /**
     * Constructor injection only. Field injection hides a class's dependencies from its
     * constructor, which makes it possible to add one without anybody noticing and
     * impossible to instantiate the class in a test without a container.
     */
    @ArchTest
    static final ArchRule noFieldInjection = noClasses()
            .should().beAnnotatedWith("org.springframework.beans.factory.annotation.Autowired")
            .orShould().dependOnClassesThat()
            .haveFullyQualifiedName("org.springframework.beans.factory.annotation.Value")
            .because("dependencies belong in the constructor, where they cannot be overlooked");

    /**
     * Money is never floating point (ADR-0003). This rule is the reason the ADR stays
     * true: {@code double} is the natural thing to reach for, and one instance of it in
     * a fee calculation is enough to make a ledger stop balancing.
     */
    @ArchTest
    static final ArchRule noFloatingPointFields = fields()
            .that().areDeclaredInClassesThat().resideInAnyPackage(
                    "dev.maestro.domain..", "dev.maestro.payment..", "dev.maestro.ledger..")
            .should().notHaveRawType(Double.class)
            .andShould().notHaveRawType(Float.class)
            .andShould().notHaveRawType(double.class)
            .andShould().notHaveRawType(float.class)
            .because("monetary values are integer minor units; floating point cannot represent them exactly");

    /**
     * Timestamps use {@code java.time}. {@code Date} and {@code Calendar} are mutable and
     * carry an implicit time zone, which in a system that reconciles against a bank's
     * end-of-day file is a defect waiting for a daylight-saving transition.
     */
    @ArchTest
    static final ArchRule noLegacyDateApi = noClasses()
            .that().resideInAPackage("dev.maestro..")
            .should().dependOnClassesThat().haveFullyQualifiedName("java.util.Date")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("java.util.Calendar")
            .because("java.time is the only date API used in this platform");
}
