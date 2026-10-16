package com.lm.codeprep.knowledge;

import com.lm.codeprep.persistence.DocumentIngestionSchemaMigration;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DocumentIngestionSchemaMigrationTest {
    @Test
    void upgradesAnExistingDocumentTableAndCanRunAgain() throws SQLException {
        DriverManagerDataSource source = new DriverManagerDataSource(
                "jdbc:h2:mem:claim-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE cp_document (id VARCHAR(36) PRIMARY KEY)");

        DocumentIngestionSchemaMigration migration = new DocumentIngestionSchemaMigration(source);
        migration.migrate();
        migration.migrate();

        jdbc.update("INSERT INTO cp_document (id, ingest_owner, ingest_lease_until) VALUES (?, ?, ?)",
                "doc-1", "worker-1", 123L);
        assertEquals("worker-1", jdbc.queryForObject(
                "SELECT ingest_owner FROM cp_document WHERE id = ?", String.class, "doc-1"));
    }
}
