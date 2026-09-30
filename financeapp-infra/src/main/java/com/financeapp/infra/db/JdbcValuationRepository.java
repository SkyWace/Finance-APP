package com.financeapp.infra.db;

import com.financeapp.core.account.AccountValuation;
import com.financeapp.core.port.ValuationRepository;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class JdbcValuationRepository implements ValuationRepository {

    private static final RowMapper<AccountValuation> MAPPER = (rs, i) -> new AccountValuation(
            rs.getLong("id"), rs.getLong("account_id"), DbCodec.date(rs, "value_date"),
            DbCodec.money(rs, "value_minor", Currency.getInstance(rs.getString("currency"))));

    private final JdbcClient jdbc;

    public JdbcValuationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<AccountValuation> findByAccount(long accountId) {
        return jdbc.sql("SELECT * FROM account_valuations WHERE account_id = :a ORDER BY value_date DESC")
                .param("a", accountId).query(MAPPER).list();
    }

    @Override
    public Map<Long, AccountValuation> latestByAccount() {
        Map<Long, AccountValuation> result = new HashMap<>();
        jdbc.sql("""
                        SELECT v.* FROM account_valuations v
                        WHERE v.value_date = (SELECT MAX(w.value_date) FROM account_valuations w WHERE w.account_id = v.account_id)
                        """)
                .query(MAPPER).list().forEach(v -> result.put(v.accountId(), v));
        return result;
    }

    @Override
    public AccountValuation save(AccountValuation v) {
        jdbc.sql("""
                        INSERT INTO account_valuations (account_id, value_date, value_minor, currency, created_at)
                        VALUES (:a, :d, :v, :c, :now)
                        ON CONFLICT(account_id, value_date) DO UPDATE SET value_minor = :v, currency = :c
                        """)
                .param("a", v.accountId()).param("d", DbCodec.date(v.date())).param("v", v.value().toMinorUnits())
                .param("c", v.value().currency().getCurrencyCode()).param("now", DbCodec.now())
                .update();
        return jdbc.sql("SELECT * FROM account_valuations WHERE account_id = :a AND value_date = :d")
                .param("a", v.accountId()).param("d", DbCodec.date(v.date())).query(MAPPER).single();
    }

    @Override
    public void delete(long id) {
        jdbc.sql("DELETE FROM account_valuations WHERE id = :id").param("id", id).update();
    }
}
