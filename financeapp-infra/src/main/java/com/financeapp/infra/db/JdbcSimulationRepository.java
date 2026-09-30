package com.financeapp.infra.db;

import com.financeapp.core.port.SimulationRepository;
import com.financeapp.core.simulation.Simulation;
import com.financeapp.core.simulation.SimulationItem;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Optional;

/** Scenarios et hypotheses ; un enregistrement remplace toutes les hypotheses, atomiquement. */
public final class JdbcSimulationRepository implements SimulationRepository {

    private record Header(long id, String name, int horizon) {
    }

    private static final RowMapper<Header> HEADER = (rs, i) ->
            new Header(rs.getLong("id"), rs.getString("name"), rs.getInt("horizon_months"));

    private static final RowMapper<SimulationItem> ITEM = (rs, i) -> {
        String code = rs.getString("currency");
        Currency currency = code == null ? null : Currency.getInstance(code);
        return new SimulationItem(
                rs.getLong("id"),
                SimulationItem.Kind.valueOf(rs.getString("kind")),
                rs.getString("label"),
                currency == null ? null : DbCodec.nullableMoney(rs, "amount_minor", currency),
                DbCodec.date(rs, "item_date"),
                DbCodec.nullableInt(rs, "months"),
                DbCodec.decimal(rs, "annual_rate"),
                currency == null ? null : DbCodec.nullableMoney(rs, "payment_minor", currency),
                DbCodec.nullableLong(rs, "recurring_id"));
    };

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public JdbcSimulationRepository(JdbcClient jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    @Override
    public List<Simulation> findAll() {
        List<Simulation> result = new ArrayList<>();
        for (Header h : jdbc.sql("SELECT * FROM simulations ORDER BY updated_at DESC").query(HEADER).list()) {
            result.add(load(h));
        }
        return result;
    }

    @Override
    public Optional<Simulation> findById(long id) {
        return jdbc.sql("SELECT * FROM simulations WHERE id = :id").param("id", id).query(HEADER).optional()
                .map(this::load);
    }

    @Override
    public Simulation save(Simulation s) {
        return tx.execute(status -> {
            String now = DbCodec.now();
            long id;
            if (s.id() == null) {
                KeyHolder keys = new GeneratedKeyHolder();
                jdbc.sql("""
                                INSERT INTO simulations (name, horizon_months, created_at, updated_at)
                                VALUES (:name, :horizon, :now, :now)
                                """)
                        .param("name", s.name()).param("horizon", s.horizonMonths()).param("now", now)
                        .update(keys);
                id = JdbcKeys.id(keys);
            } else {
                id = s.id();
                jdbc.sql("UPDATE simulations SET name = :name, horizon_months = :horizon, updated_at = :now WHERE id = :id")
                        .param("name", s.name()).param("horizon", s.horizonMonths()).param("now", now)
                        .param("id", id).update();
                jdbc.sql("DELETE FROM simulation_items WHERE simulation_id = :id").param("id", id).update();
            }
            int position = 0;
            for (SimulationItem item : s.items()) {
                var currency = item.amount() != null ? item.amount().currency()
                        : item.payment() != null ? item.payment().currency() : null;
                jdbc.sql("""
                                INSERT INTO simulation_items (simulation_id, position, kind, label, amount_minor, currency,
                                       item_date, months, annual_rate, payment_minor, recurring_id)
                                VALUES (:sim, :position, :kind, :label, :amount, :currency, :date, :months, :rate,
                                        :payment, :recurring)
                                """)
                        .param("sim", id)
                        .param("position", position++)
                        .param("kind", item.kind().name())
                        .param("label", item.label())
                        .param("amount", DbCodec.minor(item.amount()))
                        .param("currency", currency == null ? null : currency.getCurrencyCode())
                        .param("date", DbCodec.date(item.date()))
                        .param("months", item.months())
                        .param("rate", DbCodec.decimal(item.annualRate()))
                        .param("payment", DbCodec.minor(item.payment()))
                        .param("recurring", item.recurringId())
                        .update();
            }
            return findById(id).orElseThrow();
        });
    }

    @Override
    public void delete(long id) {
        jdbc.sql("DELETE FROM simulations WHERE id = :id").param("id", id).update();
    }

    private Simulation load(Header h) {
        List<SimulationItem> items = jdbc.sql("SELECT * FROM simulation_items WHERE simulation_id = :id ORDER BY position")
                .param("id", h.id()).query(ITEM).list();
        return new Simulation(h.id(), h.name(), h.horizon(), items);
    }
}
