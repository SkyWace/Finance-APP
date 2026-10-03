package com.financeapp.infra.db;

import com.financeapp.core.account.Account;
import com.financeapp.core.account.AccountType;
import com.financeapp.core.attachment.Attachment;
import com.financeapp.core.attachment.AttachmentType;
import com.financeapp.core.money.Money;
import com.financeapp.core.service.AttachmentService;
import com.financeapp.core.service.TransactionDraft;
import com.financeapp.core.transaction.Transaction;
import com.financeapp.core.transaction.TransactionStatus;
import com.financeapp.core.transaction.TransactionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AttachmentRepositoryTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 3);
    /** Texte reconnaissable : il ne doit jamais apparaitre en clair dans le fichier de la base. */
    private static final String MARKER = "FACTURE-PLOMBIER-CONFIDENTIELLE";

    @TempDir
    Path dir;

    private static byte[] pdf() {
        return ("%PDF-1.7\n" + MARKER + "\n" + "x".repeat(20_000) + "\n%%EOF").getBytes(StandardCharsets.US_ASCII);
    }

    @Test
    void attachmentsAreStoredEncryptedAndFollowTheirTransaction() throws Exception {
        SqliteTestDb db = new SqliteTestDb(dir, TODAY);
        Account checking = db.accounts.save(Account.create("Courant", AccountType.CHECKING, Money.eur("500"), TODAY.minusYears(1)));
        Transaction repair = db.transactions.create(new TransactionDraft(checking.id(), TODAY, "Plombier",
                new BigDecimal("150"), TransactionType.EXPENSE, TransactionStatus.COMPLETED, null, null, List.of(), Set.of()));

        Attachment invoice = db.attachments.attach(repair.id(), "facture.pdf", pdf());
        assertEquals(AttachmentType.PDF, invoice.type());
        assertEquals(List.of(invoice), db.attachments.list(repair.id()));
        assertArrayEquals(pdf(), db.attachments.content(invoice.id()));
        assertEquals(Map.of(repair.id(), 1), db.attachments.counts(List.of(repair.id())));
        assertEquals(new AttachmentService.Usage(1, pdf().length), db.attachments.usage());

        // Chiffre sur le disque (base et journal WAL)
        for (String name : List.of("financeapp.db", "financeapp.db-wal")) {
            Path file = db.dirs.databaseFile().resolveSibling(name);
            if (Files.exists(file)) {
                assertFalse(new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1).contains(MARKER), name);
            }
        }

        // Modifier l'operation garde ses justificatifs
        db.transactions.update(repair.id(), new TransactionDraft(checking.id(), TODAY, "Plombier (fuite)",
                new BigDecimal("160"), TransactionType.EXPENSE, TransactionStatus.COMPLETED, null, null, List.of(), Set.of()));
        assertEquals(1, db.attachments.list(repair.id()).size());

        // Supprimer l'operation supprime ses justificatifs
        db.transactions.delete(repair.id());
        assertEquals(new AttachmentService.Usage(0, 0), db.attachments.usage());
    }

    @Test
    void countsWorkForManyTransactions() {
        SqliteTestDb db = new SqliteTestDb(dir, TODAY);
        Account checking = db.accounts.save(Account.create("Courant", AccountType.CHECKING, Money.eur("500"), TODAY.minusYears(1)));
        Transaction t = db.transactions.create(new TransactionDraft(checking.id(), TODAY, "Courses",
                new BigDecimal("20"), TransactionType.EXPENSE, TransactionStatus.COMPLETED, null, null, List.of(), Set.of()));
        db.attachments.attach(t.id(), "ticket.pdf", pdf());
        db.attachments.attach(t.id(), "ticket 2.pdf", pdf());
        List<Long> ids = new java.util.ArrayList<>();
        for (long i = 1; i <= 1200; i++) {
            ids.add(i + 100_000);
        }
        ids.add(t.id());
        assertEquals(Map.of(t.id(), 2), db.attachments.counts(ids), "plus de 500 identifiants : par paquets");
    }
}
