package com.lm.codeprep.persistence;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** schema.sql creates fresh tables; this also upgrades existing MySQL databases in place. */
@Component("documentIngestionSchemaMigration")
@DependsOnDatabaseInitialization
public class DocumentIngestionSchemaMigration {
    private final DataSource dataSource;

    public DocumentIngestionSchemaMigration(DataSource dataSource) { this.dataSource = dataSource; }

    @PostConstruct
    public void migrate() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            addColumnIfMissing(connection, "ingest_owner", "VARCHAR(36)");
            addColumnIfMissing(connection, "ingest_lease_until", "BIGINT");
        }
    }

    private void addColumnIfMissing(Connection connection, String name, String definition) throws SQLException {
        if (hasColumn(connection, name)) return;
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE cp_document ADD COLUMN " + name + " " + definition);
        } catch (SQLException exception) {
            // Concurrent application startups may both observe the old schema.
            if (!hasColumn(connection, name)) throw exception;
        }
    }

    private boolean hasColumn(Connection connection, String name) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        for (String table : new String[] { "cp_document", "CP_DOCUMENT" }) {
            for (String column : new String[] { name, name.toUpperCase(java.util.Locale.ROOT) }) {
                try (ResultSet found = metadata.getColumns(connection.getCatalog(), null, table, column)) {
                    if (found.next()) return true;
                }
            }
        }
        return false;
    }
}
