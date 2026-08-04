package dev.maestro.testing;

import java.nio.file.Files;
import java.nio.file.Path;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Real PostgreSQL and Kafka for integration tests.
 *
 * <p>Nothing in the data or messaging layer is mocked. A mocked repository proves the
 * code calls the method it was told to call; it cannot prove that
 * {@code ON CONFLICT DO NOTHING} suppresses a duplicate under a genuine race, that a
 * database constraint rejects an unbalanced write, or that an event survives a
 * round trip through a broker — which is where this platform's correctness lives.
 *
 * <p>Containers are started once per JVM and shared, so a full suite pays the startup
 * cost once. They are not stopped: Testcontainers' Ryuk sidecar reaps them when the JVM
 * exits, and stopping them between classes would multiply the suite's runtime by its
 * class count.
 *
 * <p>The PostgreSQL container runs the same role-and-schema bootstrap the Compose stack
 * runs, from the same file, so a test cannot pass against a permission model the real
 * deployment does not have.
 */
public final class MaestroInfrastructure {

    private static final String INIT_SCRIPT = "deploy/compose/postgres-init/01-roles.sql";

    private static final PostgreSQLContainer POSTGRES;
    private static final KafkaContainer KAFKA;

    static {
        POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:18"))
                .withDatabaseName("maestro")
                .withUsername("maestro")
                .withPassword("maestro")
                .withCopyFileToContainer(
                        MountableFile.forHostPath(locateInitScript()),
                        "/docker-entrypoint-initdb.d/01-roles.sql");
        KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:4.2.1"));

        POSTGRES.start();
        KAFKA.start();
    }

    private MaestroInfrastructure() {
    }

    public static PostgreSQLContainer postgres() {
        return POSTGRES;
    }

    public static KafkaContainer kafka() {
        return KAFKA;
    }

    public static String jdbcUrl() {
        return POSTGRES.getJdbcUrl();
    }

    public static String kafkaBootstrapServers() {
        return KAFKA.getBootstrapServers();
    }

    /**
     * Finds the bootstrap script by walking up from the working directory.
     *
     * <p>Deliberately reads the Compose file rather than keeping a copy in test
     * resources: two copies of a permissions model drift, and the copy the tests use
     * would be the one that stays correct.
     */
    private static Path locateInitScript() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve(INIT_SCRIPT);
            if (Files.exists(candidate)) {
                return candidate;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException(
                "Could not locate " + INIT_SCRIPT + " above " + Path.of("").toAbsolutePath());
    }
}
