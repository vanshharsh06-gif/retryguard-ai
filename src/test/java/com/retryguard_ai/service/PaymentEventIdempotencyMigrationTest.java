package com.retryguard_ai.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@EnabledIfSystemProperty(
        named = "retryguard.apply-payment-event-idempotency-migration",
        matches = "true")
class PaymentEventIdempotencyMigrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void addsUniqueIndexOnlyWhenExistingEventIdsAreUnambiguous() {
        Integer duplicateKeyCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM (
                    SELECT provider, provider_event_id
                    FROM payment_events
                    WHERE provider_event_id IS NOT NULL
                    GROUP BY provider, provider_event_id
                    HAVING COUNT(*) > 1
                ) duplicate_keys
                """, Integer.class);

        assertEquals(0, duplicateKeyCount,
                "Resolve existing duplicate provider event IDs before applying the index.");

        ResourceDatabasePopulator migration = new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V1__payment_event_idempotency.sql"));
        DatabasePopulatorUtils.execute(migration, jdbcTemplate.getDataSource());

        Boolean indexExists = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM pg_indexes
                    WHERE schemaname = current_schema()
                      AND indexname = 'uq_payment_events_provider_event_id'
                )
                """, Boolean.class);
        assertTrue(indexExists);
    }
}
