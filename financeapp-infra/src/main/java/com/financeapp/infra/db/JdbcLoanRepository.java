package com.financeapp.infra.db;

import com.financeapp.core.loan.Loan;
import com.financeapp.core.port.LoanRepository;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.util.Currency;
import java.util.List;
import java.util.Optional;

public final class JdbcLoanRepository implements LoanRepository {

    private static final RowMapper<Loan> MAPPER = (rs, i) -> {
        Currency currency = Currency.getInstance(rs.getString("currency"));
        return new Loan(
                rs.getLong("id"),
                rs.getString("name"),
                DbCodec.money(rs, "principal_minor", currency),
                DbCodec.decimal(rs, "annual_rate"),
                rs.getInt("term_months"),
                DbCodec.date(rs, "first_payment_date"),
                DbCodec.nullableMoney(rs, "payment_minor", currency),
                DbCodec.money(rs, "insurance_minor", currency),
                DbCodec.nullableLong(rs, "account_id"),
                DbCodec.nullableLong(rs, "recurring_id"),
                DbCodec.nullableLong(rs, "category_id"),
                DbCodec.bool(rs, "archived"),
                rs.getString("note"));
    };

    private final JdbcClient jdbc;

    public JdbcLoanRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Loan> findAll() {
        return jdbc.sql("SELECT * FROM loans ORDER BY archived, first_payment_date, name COLLATE NOCASE")
                .query(MAPPER).list();
    }

    @Override
    public Optional<Loan> findById(long id) {
        return jdbc.sql("SELECT * FROM loans WHERE id = :id").param("id", id).query(MAPPER).optional();
    }

    @Override
    public Loan save(Loan l) {
        var statement = jdbc.sql(l.id() == null
                        ? """
                          INSERT INTO loans (name, principal_minor, currency, annual_rate, term_months, first_payment_date,
                                 payment_minor, insurance_minor, account_id, recurring_id, category_id, archived, note,
                                 created_at, updated_at)
                          VALUES (:name, :principal, :currency, :rate, :term, :first, :payment, :insurance, :account,
                                  :recurring, :category, :archived, :note, :now, :now)
                          """
                        : """
                          UPDATE loans SET name = :name, principal_minor = :principal, currency = :currency,
                                 annual_rate = :rate, term_months = :term, first_payment_date = :first,
                                 payment_minor = :payment, insurance_minor = :insurance, account_id = :account,
                                 recurring_id = :recurring, category_id = :category, archived = :archived, note = :note,
                                 updated_at = :now
                          WHERE id = :id
                          """)
                .param("id", l.id())
                .param("name", l.name())
                .param("principal", l.principal().toMinorUnits())
                .param("currency", l.principal().currency().getCurrencyCode())
                .param("rate", DbCodec.decimal(l.annualRate()))
                .param("term", l.termMonths())
                .param("first", DbCodec.date(l.firstPaymentDate()))
                .param("payment", DbCodec.minor(l.payment()))
                .param("insurance", l.monthlyInsurance().toMinorUnits())
                .param("account", l.accountId())
                .param("recurring", l.recurringId())
                .param("category", l.categoryId())
                .param("archived", DbCodec.bool(l.archived()))
                .param("note", l.note())
                .param("now", DbCodec.now());
        if (l.id() != null) {
            statement.update();
            return l;
        }
        KeyHolder keys = new GeneratedKeyHolder();
        statement.update(keys);
        return l.withId(JdbcKeys.id(keys));
    }

    @Override
    public void delete(long id) {
        jdbc.sql("DELETE FROM loans WHERE id = :id").param("id", id).update();
    }
}
