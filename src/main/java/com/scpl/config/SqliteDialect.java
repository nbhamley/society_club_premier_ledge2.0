package com.scpl.config;

import org.hibernate.community.dialect.SQLiteDialect;

import java.sql.Types;

/**
 * SQLite stores everything as INTEGER, REAL or TEXT and reports only those types through JDBC metadata,
 * so Hibernate's schema validation would reject every BIGINT, BOOLEAN, DATE and TIMESTAMP column.
 * Types in the same storage family are declared equivalent here, which keeps the entities free of
 * columnDefinition clutter and portable to PostgreSQL and MySQL.
 */
public class SqliteDialect extends SQLiteDialect {

    private static final int OTHER = 0;
    private static final int INTEGER_FAMILY = 1;
    private static final int REAL_FAMILY = 2;
    private static final int TEXT_FAMILY = 3;

    @Override
    public boolean equivalentTypes(int typeCode1, int typeCode2) {
        if (super.equivalentTypes(typeCode1, typeCode2)) {
            return true;
        }
        int f = family(typeCode1);
        return f != OTHER && f == family(typeCode2);
    }

    private static int family(int jdbcType) {
        return switch (jdbcType) {
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT, Types.BOOLEAN, Types.BIT -> INTEGER_FAMILY;
            case Types.REAL, Types.FLOAT, Types.DOUBLE, Types.NUMERIC, Types.DECIMAL -> REAL_FAMILY;
            // Dates and times are stored as ISO-8601 text (V1 convention), so they belong with TEXT.
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR,
                 Types.CLOB, Types.NCLOB, Types.DATE, Types.TIME, Types.TIMESTAMP,
                 Types.TIME_WITH_TIMEZONE, Types.TIMESTAMP_WITH_TIMEZONE -> TEXT_FAMILY;
            default -> OTHER;
        };
    }
}