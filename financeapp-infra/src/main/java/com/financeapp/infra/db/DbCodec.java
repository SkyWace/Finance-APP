package com.financeapp.infra.db;

import com.financeapp.core.money.Money;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;

/** Conversions entre types du domaine et representation SQLite. */
final class DbCodec {

    private DbCodec() {
    }

    static String date(LocalDate date) {
        return date == null ? null : date.toString();
    }

    static LocalDate date(ResultSet rs, String column) throws SQLException {
        String value = rs.getString(column);
        return value == null ? null : LocalDate.parse(value);
    }

    static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    static int bool(boolean value) {
        return value ? 1 : 0;
    }

    static boolean bool(ResultSet rs, String column) throws SQLException {
        return rs.getInt(column) != 0;
    }

    static Money money(ResultSet rs, String column, Currency currency) throws SQLException {
        return Money.ofMinor(rs.getLong(column), currency);
    }

    static Money nullableMoney(ResultSet rs, String column, Currency currency) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : Money.ofMinor(value, currency);
    }

    static Long minor(Money m) {
        return m == null ? null : m.toMinorUnits();
    }

    static String decimal(java.math.BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    static java.math.BigDecimal decimal(ResultSet rs, String column) throws SQLException {
        String value = rs.getString(column);
        return value == null ? null : new java.math.BigDecimal(value);
    }

    static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    static String now() {
        return Instant.now().toString();
    }
}
