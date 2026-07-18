package com.mellishy.customtag.data.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code storage.mysql.table-prefix} in config.yml is concatenated directly into SQL statement
 * text (table names can't be bound as JDBC parameters) - {@link MySQLStorageBackend#sanitizeIdentifier}
 * is what stands between a careless/corrupted config value and the executed SQL. Only
 * {@code [A-Za-z0-9_]} may ever pass through unchanged.
 */
class MySQLStorageBackendSanitizeTest {

    @Test
    void alphanumericAndUnderscore_passThroughUnchanged() {
        assertEquals("ct_prod_", MySQLStorageBackend.sanitizeIdentifier("ct_prod_"));
        assertEquals("Server1_", MySQLStorageBackend.sanitizeIdentifier("Server1_"));
    }

    @Test
    void backtick_isStripped() {
        // a raw backtick could otherwise let a config value break out of the identifier position
        // in every SQL statement this backend builds
        assertEquals("ctplayers", MySQLStorageBackend.sanitizeIdentifier("ct`players"));
    }

    @Test
    void whitespaceAndSqlSyntax_isStripped() {
        assertEquals("ctDROPTABLEplayers", MySQLStorageBackend.sanitizeIdentifier("ct`; DROP TABLE players; --"));
    }

    @Test
    void nullOrBlank_becomesEmptyPrefix() {
        assertEquals("", MySQLStorageBackend.sanitizeIdentifier(null));
        assertEquals("", MySQLStorageBackend.sanitizeIdentifier("   "));
    }

    @Test
    void leadingAndTrailingWhitespace_isTrimmedFirst() {
        assertEquals("ct_", MySQLStorageBackend.sanitizeIdentifier("  ct_  "));
    }
}
