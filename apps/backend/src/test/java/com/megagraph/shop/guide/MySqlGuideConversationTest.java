package com.megagraph.shop.guide;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@EnabledIfEnvironmentVariable(named = "MYSQL_GUIDE_URL", matches = ".+")
class MySqlGuideConversationTest extends GuideConversationIntegrationTest {
    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) throws Exception {
        String url = required("MYSQL_GUIDE_URL");
        String username = required("MYSQL_GUIDE_USERNAME");
        String password = required("MYSQL_GUIDE_PASSWORD");
        try (var connection = DriverManager.getConnection(url, username, password);
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT COUNT(*) FROM information_schema.tables "
                     + "WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE'")) {
            result.next();
            assertThat(result.getLong(1)).as("Guide tests require a dedicated empty schema").isZero();
            assertThat(connection.getMetaData().getDatabaseMajorVersion()).isEqualTo(8);
            assertThat(connection.getMetaData().getDatabaseMinorVersion()).isGreaterThanOrEqualTo(4);
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> username);
        registry.add("spring.datasource.password", () -> password);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
}
