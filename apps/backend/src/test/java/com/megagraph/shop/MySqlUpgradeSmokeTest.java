package com.megagraph.shop;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

class MySqlUpgradeSmokeTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "MYSQL_UPGRADE_URL", matches = ".+")
    void existingV5FactsSurviveIdentityMigration() throws Exception {
        String url = System.getenv("MYSQL_UPGRADE_URL");
        String username = required("MYSQL_UPGRADE_USERNAME");
        String password = required("MYSQL_UPGRADE_PASSWORD");
        try (Connection connection = DriverManager.getConnection(url, username, password)) {
            assertThat(singleLong(connection, "SELECT COUNT(*) FROM information_schema.tables "
                    + "WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE'")).isZero();
        }
        Flyway v5 = Flyway.configure().dataSource(url, username, password)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("5"))
                .cleanDisabled(true).load();
        assertThat(v5.migrate().success).isTrue();

        try (Connection connection = DriverManager.getConnection(url, username, password)) {
            execute(connection, "INSERT INTO app_user (username, password_hash, display_name, status) "
                    + "VALUES ('Existing@Example.Test', 'test-hash', 'Existing', 'ACTIVE')");
            execute(connection, "INSERT INTO merchant (name, status) VALUES ('升级测试商家', 'ACTIVE')");
            execute(connection, """
                    INSERT INTO sales_order (order_no, user_id, merchant_id, idempotency_key, status,
                        currency, total_amount, recipient_name, recipient_phone, shipping_address, expires_at)
                    VALUES ('2E-UPGRADE-ORDER', 1, 1, 'upgrade-key', 'PENDING_PAYMENT',
                        'CNY', 12.00, '测试', '00000000000', '测试地址', CURRENT_TIMESTAMP(3))
                    """);
            execute(connection, """
                    INSERT INTO outbox_event (event_id, aggregate_type, aggregate_id, aggregate_version,
                        event_type, payload_json, status)
                    VALUES ('e2e22222-2222-4222-8222-222222222222', 'ORDER', 1, 1,
                        'ORDER_CREATED', '{}', 'PENDING')
                    """);
            execute(connection, """
                    INSERT INTO payment_channel_event (provider, provider_event_id, event_type, resource_type,
                        provider_resource_id, amount, currency, payload_digest, payload_text,
                        occurred_at, received_at, processing_status, conflict_count, version)
                    VALUES ('MOCK', 'upgrade-event', 'PAYMENT_PROCESSING', 'PAYMENT',
                        'upgrade-resource', 12.00, 'CNY', REPEAT('0', 64), '{}',
                        CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3), 'RECEIVED', 0, 0)
                    """);
        }

        Flyway latest = Flyway.configure().dataSource(url, username, password)
                .locations("classpath:db/migration").cleanDisabled(true).load();
        assertThat(latest.migrate().success).isTrue();
        assertThat(latest.validateWithResult().validationSuccessful).isTrue();
        try (Connection connection = DriverManager.getConnection(url, username, password)) {
            assertThat(singleLong(connection, "SELECT COUNT(*) FROM app_user WHERE username_normalized = "
                    + "'existing@example.test' AND status = 'ACTIVE'")).isEqualTo(1);
            assertThat(singleLong(connection, "SELECT COUNT(*) FROM sales_order WHERE order_no = "
                    + "'2E-UPGRADE-ORDER' AND status = 'PENDING_PAYMENT'")).isEqualTo(1);
            assertThat(singleLong(connection, "SELECT COUNT(*) FROM outbox_event WHERE event_type = "
                    + "'ORDER_CREATED' AND status = 'PENDING'")).isEqualTo(1);
            assertThat(singleLong(connection, "SELECT COUNT(*) FROM payment_channel_event WHERE provider_event_id = "
                    + "'upgrade-event' AND processing_status = 'RECEIVED'")).isEqualTo(1);
        }
    }

    private static void execute(Connection connection, String sql) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }

    private static long singleLong(Connection connection, String sql) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet result = statement.executeQuery()) {
            result.next();
            return result.getLong(1);
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " required");
        return value;
    }
}
