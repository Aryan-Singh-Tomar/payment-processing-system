package com.payment.paymentsystem.e2e;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@Testcontainers
public abstract class AbstractIntegrationTest {

    @LocalServerPort
    protected int port;

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(
                    DockerImageName.parse("postgres:16")
                            .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("payments")
                    .withUsername("payments")
                    .withPassword("payments");

    @Container
    static final GenericContainer<?> redis =
            new GenericContainer<>(
                    DockerImageName.parse("redis:7-alpine")
                            .asCompatibleSubstituteFor("redis"))
                    .withExposedPorts(6379);


    @Container
    static final ConfluentKafkaContainer kafka =
            new ConfluentKafkaContainer(
                    DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    @DynamicPropertySource
    static void wireProperties(DynamicPropertyRegistry registry){
        // Postgres — Spring DataSource
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);

        // Flyway uses the same DataSource by default — no extra wiring needed

        // Redis — host and dynamic port
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port",
                () -> redis.getMappedPort(6379).toString());


        // Kafka — bootstrap servers
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    protected String baseUrl() {
        return "http://localhost:" + port;
    }

}
