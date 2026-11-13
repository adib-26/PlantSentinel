package com.basintelligence.common;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.springframework.stereotype.Component;

/** Detects the database engine once, so features can use PostgreSQL-specific SQL where it helps. */
@Component
public class DatabaseInfo {

    private final String product;

    public DatabaseInfo(DataSource dataSource) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            this.product = c.getMetaData().getDatabaseProductName();
        }
    }

    public boolean isPostgres() {
        return product.toLowerCase().contains("postgres");
    }

    public String product() {
        return product;
    }
}
