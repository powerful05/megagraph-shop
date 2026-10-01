package com.megagraph.shop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

class MySqlMigrationSmokeTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "MYSQL_SMOKE_URL", matches = ".+")
    void emptyMysql84SchemaAcceptsAllMigrationsAndInboxUniqueness() throws Exception {
        String url = System.getenv("MYSQL_SMOKE_URL");
        String username = requiredEnvironment("MYSQL_SMOKE_USERNAME");
        String password = requiredEnvironment("MYSQL_SMOKE_PASSWORD");

        try (Connection connection = DriverManager.getConnection(url, username, password)) {
            assertThat(connection.getMetaData().getDatabaseMajorVersion()).isEqualTo(8);
            assertThat(connection.getMetaData().getDatabaseMinorVersion()).isGreaterThanOrEqualTo(4);
            assertThat(countUserTables(connection)).as("Smoke schema must be empty").isZero();
        }

        Flyway flyway = Flyway.configure()
                .dataSource(url, username, password)
                .locations("classpath:db/migration")
                .cleanDisabled(true)
                .load();
        assertThat(flyway.migrate().success).isTrue();
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();

        try (Connection connection = DriverManager.getConnection(url, username, password)) {
            assertNullable(connection, "refund_order", "provider_refund_id", true);
            assertNullable(connection, "product", "supply_details", true);
            assertNullable(connection, "product", "test_supply", false);
            assertNullable(connection, "sales_order", "checkout_group_id", true);
            assertNullable(connection, "sales_order", "test_order", false);
            assertNullable(connection, "sales_order_item", "product_version", true);
            assertNullable(connection, "sales_order_item", "supply_details", true);
            assertNullable(connection, "refund_order", "refunded_at", true);
            assertUniqueConstraint(connection, "payment_channel_event", "uk_payment_channel_event");
            assertUniqueConstraint(connection, "payment_order", "uk_payment_provider_id");
            assertUniqueConstraint(connection, "refund_order", "uk_refund_provider_id");
            assertUniqueConstraint(connection, "refund_attempt", "uk_refund_attempt_idempotency");
            assertUniqueConstraint(connection, "trial_invite", "uk_trial_invite_code_hash");
            assertUniqueConstraint(connection, "app_user", "uk_app_user_username_normalized");
            assertUniqueConstraint(connection, "SPRING_SESSION", "SPRING_SESSION_IX1");
            assertUniqueConstraint(connection, "checkout_group", "uk_checkout_group_no");
            assertUniqueConstraint(connection, "checkout_group", "uk_checkout_user_key");
            insertInbox(connection, "mysql-smoke-event");
            assertThatThrownBy(() -> insertInbox(connection, "mysql-smoke-event"))
                    .isInstanceOf(SQLException.class);
            insertInvite(connection);
            assertThatThrownBy(() -> insertInvite(connection)).isInstanceOf(SQLException.class);
        }
    }

    private void assertUniqueConstraint(Connection connection, String table, String constraint) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*)
                FROM information_schema.table_constraints
                WHERE table_schema = DATABASE()
                  AND table_name = ?
                  AND constraint_name = ?
                  AND constraint_type = 'UNIQUE'
                """)) {
            statement.setString(1, table);
            statement.setString(2, constraint);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                assertThat(result.getInt(1)).isEqualTo(1);
            }
        }
    }

    private long countUserTables(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = DATABASE()
                  AND table_type = 'BASE TABLE'
                """)) {
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private void assertNullable(Connection connection, String table, String column, boolean expected) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT is_nullable
                FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?
                """)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo(expected ? "YES" : "NO");
            }
        }
    }

    private void insertInbox(Connection connection, String eventId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO payment_channel_event (
                    provider, provider_event_id, event_type, resource_type, provider_resource_id,
                    amount, currency, payload_digest, payload_text, occurred_at, received_at,
                    processing_status, conflict_count, version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0)
                """)) {
            statement.setString(1, "MOCK");
            statement.setString(2, eventId);
            statement.setString(3, "PAYMENT_PROCESSING");
            statement.setString(4, "PAYMENT");
            statement.setString(5, "mysql-smoke-resource");
            statement.setBigDecimal(6, BigDecimal.ONE);
            statement.setString(7, "CNY");
            statement.setString(8, "0".repeat(64));
            statement.setString(9, "{}");
            Timestamp now = Timestamp.from(Instant.now());
            statement.setTimestamp(10, now);
            statement.setTimestamp(11, now);
            statement.setString(12, "RECEIVED");
            statement.executeUpdate();
        }
    }

    private void insertInvite(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO trial_invite (code_hash, expires_at) VALUES (?, ?)
                """)) {
            statement.setString(1, "a".repeat(64));
            statement.setTimestamp(2, Timestamp.from(Instant.now().plusSeconds(3600)));
            statement.executeUpdate();
        }
    }

    private String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required when MYSQL_SMOKE_URL is set");
        }
        return value;
    }
}
