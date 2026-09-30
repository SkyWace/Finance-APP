package com.financeapp.infra.db;

import com.financeapp.core.account.Account;
import com.financeapp.core.account.AccountType;
import com.financeapp.core.categorization.CategorizationRule;
import com.financeapp.core.category.Category;
import com.financeapp.core.imports.ImportBatch;
import com.financeapp.core.imports.ImportCandidate;
import com.financeapp.core.imports.ImportedRow;
import com.financeapp.core.money.Money;
import com.financeapp.core.service.ImportService;
import com.financeapp.core.service.TransactionDraft;
import com.financeapp.core.transaction.Transaction;
import com.financeapp.core.transaction.TransactionStatus;
import com.financeapp.core.transaction.TransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ImportRepositoryTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 6);
    private static final String OFX = """
            <OFX><BANKTRANLIST>
            <STMTTRN><DTPOSTED>20261004<TRNAMT>-72.41<FITID>F1<NAME>CB CARREFOUR MARKET
            <STMTTRN><DTPOSTED>20261005<TRNAMT>-81.20<FITID>F2<NAME>CB TOTAL ACCESS
            <STMTTRN><DTPOSTED>20261005<TRNAMT>-45.00<FITID>F3<NAME>GARAGE MARTIN
            </BANKTRANLIST></OFX>
            """;

    @TempDir
    Path dir;
    private SqliteTestDb db;
    private Account checking;
    private Category fuel;

    @BeforeEach
    void setUp() {
        db = new SqliteTestDb(dir, TODAY);
        checking = db.accounts.save(Account.create("Compte courant", AccountType.CHECKING, Money.eur("1000"), TODAY));
        fuel = db.categories.findAll().stream().filter(c -> "TRANSPORT.FUEL".equals(c.systemCode())).findFirst().orElseThrow();
        db.categorization.save(new CategorizationRule(null, "total", fuel.id(), TransactionType.EXPENSE, true));
    }

    private ImportBatch importOfx() {
        List<ImportedRow> rows = db.imports.parseOfx(OFX.getBytes(StandardCharsets.UTF_8));
        List<ImportCandidate> plan = db.imports.plan(checking.id(), rows);
        return db.imports.commit(checking.id(), "releve.ofx", ImportService.Format.OFX, plan.stream()
                .map(c -> new ImportService.Decision(c, c.includedByDefault(), c.suggestion() == null ? null : c.suggestion().categoryId()))
                .toList());
    }

    @Test
    void ofxImportIsPersistedWithBankIdsAndGoesToTheInbox() {
        Transaction garage = db.transactions.create(new TransactionDraft(checking.id(), LocalDate.of(2026, 10, 8),
                "Garagiste", new BigDecimal("45"), TransactionType.EXPENSE, TransactionStatus.PLANNED, null, null));

        ImportBatch batch = importOfx();

        assertEquals(2, batch.created());
        assertEquals(1, batch.reconciled());
        assertEquals(2, db.inbox.count());
        assertEquals(fuel.id(), db.inbox.items().stream().filter(i -> i.transaction().label().contains("TOTAL"))
                .findFirst().orElseThrow().transaction().categoryId());
        assertEquals(java.util.Set.of("F1", "F2"), db.importRepo.externalIds(checking.id()));
        Transaction realized = db.transactionRepo.findById(garage.id()).orElseThrow();
        assertEquals(TransactionStatus.COMPLETED, realized.status());
        assertEquals(LocalDate.of(2026, 10, 5), realized.date());
        assertEquals(Money.eur("801.39"), db.accounts.balanceOf(checking.id()));

        // Reimport : identifiants bancaires connus → doublons, rien de cree
        assertEquals(0, importOfx().created());
    }

    @Test
    void undoRestoresEverythingAtomically() {
        Transaction garage = db.transactions.create(new TransactionDraft(checking.id(), LocalDate.of(2026, 10, 8),
                "Garagiste", new BigDecimal("45"), TransactionType.EXPENSE, TransactionStatus.PLANNED, null, null));
        ImportBatch batch = importOfx();

        db.imports.undo(batch.id());

        assertEquals(Money.eur("1000"), db.accounts.balanceOf(checking.id()));
        assertEquals(0, db.inbox.count());
        Transaction restored = db.transactionRepo.findById(garage.id()).orElseThrow();
        assertEquals(TransactionStatus.PLANNED, restored.status());
        assertEquals(LocalDate.of(2026, 10, 8), restored.date());
        assertEquals("Garagiste", restored.label());
        assertTrue(db.importRepo.findAll().getFirst().undone());
        assertTrue(db.importRepo.externalIds(checking.id()).isEmpty());
        assertEquals(2, importOfx().created(), "apres annulation, le fichier peut etre reimporte");
    }

    @Test
    void inboxValidationPersists() {
        importOfx();
        var carrefour = db.inbox.items().stream().filter(i -> i.transaction().label().contains("CARREFOUR")).findFirst().orElseThrow();
        Category groceries = db.categories.findAll().stream().filter(c -> "FOOD.GROCERIES".equals(c.systemCode())).findFirst().orElseThrow();
        assertEquals(3, db.inbox.count());
        db.inbox.validate(carrefour.transaction().id(), groceries.id());
        assertEquals(2, db.inbox.count());
        assertEquals(groceries.id(), db.transactionRepo.findById(carrefour.transaction().id()).orElseThrow().categoryId());
    }

    @Test
    void rulesPersist() {
        CategorizationRule r = db.categorizationRuleRepo.findAll().getFirst();
        assertEquals("total", r.pattern());
        assertEquals(TransactionType.EXPENSE, r.appliesTo());
        db.categorization.save(new CategorizationRule(r.id(), "TOTAL ACCESS", r.categoryId(), null, false));
        CategorizationRule updated = db.categorizationRuleRepo.findById(r.id()).orElseThrow();
        assertNull(updated.appliesTo());
        assertFalse(updated.active());
    }
}
