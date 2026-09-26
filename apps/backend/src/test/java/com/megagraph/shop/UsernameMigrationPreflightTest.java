package com.megagraph.shop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;

class UsernameMigrationPreflightTest {

    @Test
    void normalizedCollisionFailsBeforeChangingPersistentSchema() throws Exception {
        String url = "jdbc:h2:mem:username-preflight;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("5"))
                .cleanDisabled(true).load().migrate();

        try (var connection = DriverManager.getConnection(url, "sa", "");
                var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO app_user (username, password_hash, display_name, status) "
                    + "VALUES ('First@Example.Test', 'hash', 'First', 'ACTIVE')");
            statement.executeUpdate("INSERT INTO app_user (username, password_hash, display_name, status) "
                    + "VALUES ('first@example.test', 'hash', 'Second', 'ACTIVE')");
        }

        Flyway latest = Flyway.configure().dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .cleanDisabled(true).load();
        assertThatThrownBy(latest::migrate).isInstanceOf(FlywayException.class);

        try (var connection = DriverManager.getConnection(url, "sa", "");
                var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_name = 'app_user' AND column_name = 'username_normalized'")) {
            result.next();
            assertThat(result.getInt(1)).isZero();
        }
    }
}
