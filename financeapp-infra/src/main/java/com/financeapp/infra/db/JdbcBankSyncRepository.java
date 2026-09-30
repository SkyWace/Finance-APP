package com.financeapp.infra.db;

import com.financeapp.core.banksync.BankAccountLink;
import com.financeapp.core.banksync.BankConnection;
import com.financeapp.core.banksync.BankSyncCredentials;
import com.financeapp.core.port.BankSyncRepository;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public final class JdbcBankSyncRepository implements BankSyncRepository {

    private static final RowMapper<BankConnection> CONNECTION = (rs, i) -> new BankConnection(
            rs.getLong("id"), rs.getString("session_id"), rs.getString("bank_name"), rs.getString("country"),
            Instant.parse(rs.getString("valid_until")), Instant.parse(rs.getString("created_at")));

    private static final RowMapper<BankAccountLink> LINK = (rs, i) -> {
        String lastSync = rs.getString("last_sync_at");
        return new BankAccountLink(rs.getLong("id"), rs.getLong("connection_id"), rs.getString("account_uid"),
                rs.getString("name"), rs.getString("masked_iban"), rs.getString("currency"),
                DbCodec.nullableLong(rs, "local_account_id"), DbCodec.date(rs, "synced_until"),
                lastSync == null ? null : Instant.parse(lastSync));
    };

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public JdbcBankSyncRepository(JdbcClient jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    @Override
    public Optional<BankSyncCredentials> credentials() {
        return jdbc.sql("SELECT application_id, private_key_pem, redirect_url FROM bank_sync_config WHERE id = 1")
                .query((rs, i) -> new BankSyncCredentials(rs.getString(1), rs.getString(2), rs.getString(3)))
                .optional();
    }

    @Override
    public void saveCredentials(BankSyncCredentials c) {
        jdbc.sql("""
                        INSERT INTO bank_sync_config (id, application_id, private_key_pem, redirect_url, updated_at)
                        VALUES (1, :app, :key, :redirect, :now)
                        ON CONFLICT(id) DO UPDATE SET application_id = :app, private_key_pem = :key,
                               redirect_url = :redirect, updated_at = :now
                        """)
                .param("app", c.applicationId()).param("key", c.privateKeyPem()).param("redirect", c.redirectUrl())
                .param("now", DbCodec.now()).update();
    }

    @Override
    public void clearAll() {
        tx.executeWithoutResult(s -> {
            jdbc.sql("DELETE FROM bank_connections").update();
            jdbc.sql("DELETE FROM bank_sync_config").update();
        });
    }

    @Override
    public List<BankConnection> connections() {
        return jdbc.sql("SELECT * FROM bank_connections ORDER BY bank_name COLLATE NOCASE, id").query(CONNECTION).list();
    }

    @Override
    public BankConnection saveConnection(BankConnection c, List<BankAccountLink> accounts) {
        return tx.execute(s -> {
            KeyHolder keys = new GeneratedKeyHolder();
            jdbc.sql("""
                            INSERT INTO bank_connections (session_id, bank_name, country, valid_until, created_at)
                            VALUES (:session, :bank, :country, :until, :created)
                            """)
                    .param("session", c.sessionId()).param("bank", c.bankName()).param("country", c.country())
                    .param("until", c.validUntil().toString()).param("created", c.createdAt().toString())
                    .update(keys);
            BankConnection saved = c.withId(JdbcKeys.id(keys));
            for (BankAccountLink l : accounts) {
                jdbc.sql("""
                                INSERT INTO bank_account_links (connection_id, account_uid, name, masked_iban, currency,
                                       local_account_id, synced_until, last_sync_at)
                                VALUES (:connection, :uid, :name, :iban, :currency, :local, :until, :last)
                                """)
                        .param("connection", saved.id()).param("uid", l.accountUid()).param("name", l.name())
                        .param("iban", l.maskedIban()).param("currency", l.currency())
                        .param("local", l.localAccountId()).param("until", DbCodec.date(l.syncedUntil()))
                        .param("last", l.lastSyncAt() == null ? null : l.lastSyncAt().toString())
                        .update();
            }
            return saved;
        });
    }

    @Override
    public void deleteConnection(long connectionId) {
        jdbc.sql("DELETE FROM bank_connections WHERE id = :id").param("id", connectionId).update();
    }

    @Override
    public List<BankAccountLink> links() {
        return jdbc.sql("SELECT * FROM bank_account_links ORDER BY connection_id, id").query(LINK).list();
    }

    @Override
    public Optional<BankAccountLink> link(long id) {
        return jdbc.sql("SELECT * FROM bank_account_links WHERE id = :id").param("id", id).query(LINK).optional();
    }

    @Override
    public BankAccountLink saveLink(BankAccountLink l) {
        jdbc.sql("""
                        UPDATE bank_account_links SET local_account_id = :local, synced_until = :until, last_sync_at = :last
                        WHERE id = :id
                        """)
                .param("local", l.localAccountId()).param("until", DbCodec.date(l.syncedUntil()))
                .param("last", l.lastSyncAt() == null ? null : l.lastSyncAt().toString())
                .param("id", l.id()).update();
        return l;
    }

    @Override
    public void recordFetch(long linkId, Instant at) {
        jdbc.sql("INSERT INTO bank_sync_fetches (link_id, fetched_at) VALUES (:link, :at)")
                .param("link", linkId).param("at", at.toEpochMilli()).update();
        // Seules les dernieres 24 heures servent : l'historique plus ancien est purge.
        jdbc.sql("DELETE FROM bank_sync_fetches WHERE fetched_at < :old")
                .param("old", at.minusSeconds(7 * 86400).toEpochMilli()).update();
    }

    @Override
    public int fetchesSince(long linkId, Instant since) {
        return jdbc.sql("SELECT COUNT(*) FROM bank_sync_fetches WHERE link_id = :link AND fetched_at >= :since")
                .param("link", linkId).param("since", since.toEpochMilli()).query(Integer.class).single();
    }
}
