package com.financeapp.infra.db;

import com.financeapp.core.account.Account;
import com.financeapp.core.account.AccountType;
import com.financeapp.core.port.AccountRepository;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.util.Currency;
import java.util.List;
import java.util.Optional;

public final class JdbcAccountRepository implements AccountRepository {

    private static final RowMapper<Account> MAPPER = (rs, i) -> {
        Currency currency = Currency.getInstance(rs.getString("currency"));
        return new Account(
                rs.getLong("id"),
                rs.getString("name"),
                AccountType.valueOf(rs.getString("type")),
                DbCodec.money(rs, "initial_balance_minor", currency),
                DbCodec.date(rs, "opening_date"),
                rs.getString("icon"),
                rs.getString("color"),
                DbCodec.bool(rs, "include_in_available"),
                DbCodec.bool(rs, "archived"),
                rs.getInt("sort_order"));
    };

    private final JdbcClient jdbc;

    public JdbcAccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Account> findAll() {
        return jdbc.sql("SELECT * FROM accounts ORDER BY archived, sort_order, name COLLATE NOCASE")
                .query(MAPPER).list();
    }

    @Override
    public Optional<Account> findById(long id) {
        return jdbc.sql("SELECT * FROM accounts WHERE id = :id").param("id", id).query(MAPPER).optional();
    }

    @Override
    public Account save(Account a) {
        String now = DbCodec.now();
        var statement = jdbc.sql(a.id() == null
                        ? """
                          INSERT INTO accounts (name, type, currency, initial_balance_minor, opening_date, icon, color,
                                                include_in_available, archived, sort_order, created_at, updated_at)
                          VALUES (:name, :type, :currency, :initial, :opening, :icon, :color,
                                  :included, :archived, :sortOrder, :now, :now)
                          """
                        : """
                          UPDATE accounts SET name = :name, type = :type, currency = :currency,
                                 initial_balance_minor = :initial, opening_date = :opening, icon = :icon, color = :color,
                                 include_in_available = :included, archived = :archived, sort_order = :sortOrder,
                                 updated_at = :now
                          WHERE id = :id
                          """)
                .param("id", a.id())
                .param("name", a.name())
                .param("type", a.type().name())
                .param("currency", a.currency().getCurrencyCode())
                .param("initial", a.initialBalance().toMinorUnits())
                .param("opening", DbCodec.date(a.openingDate()))
                .param("icon", a.icon())
                .param("color", a.color())
                .param("included", DbCodec.bool(a.includeInAvailable()))
                .param("archived", DbCodec.bool(a.archived()))
                .param("sortOrder", a.sortOrder())
                .param("now", now);
        if (a.id() != null) {
            statement.update();
            return a;
        }
        KeyHolder keys = new GeneratedKeyHolder();
        statement.update(keys);
        return a.withId(JdbcKeys.id(keys));
    }

    @Override
    public void delete(long id) {
        jdbc.sql("DELETE FROM accounts WHERE id = :id").param("id", id).update();
    }
}
