package se.klubb.groupplanner.repo;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Small helpers for reading nullable numeric columns out of a {@link ResultSet}.
 *
 * <p>{@code ResultSet.getObject(String, Class)} is JDBC-driver-dependent for which target classes
 * it supports; the xerial sqlite-jdbc driver (3.53.2.0) supports {@code Integer.class} but throws
 * {@code SQLException("Bad value for type Double")} for {@code Double.class}. The classic
 * {@code getX()} + {@code wasNull()} idiom below works uniformly for every JDBC driver, so all row
 * mappers in this package use it instead.
 */
final class NullableColumns {

    private NullableColumns() {
    }

    static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }
}
