package com.financeapp.core.service;

import com.financeapp.core.account.Account;
import com.financeapp.core.imports.CsvMapping;
import com.financeapp.core.imports.CsvMappingGuesser;
import com.financeapp.core.imports.CsvReader;
import com.financeapp.core.imports.CsvRowConverter;
import com.financeapp.core.imports.CsvTable;
import com.financeapp.core.imports.ImportBatch;
import com.financeapp.core.imports.ImportCandidate;
import com.financeapp.core.imports.ImportPlanner;
import com.financeapp.core.imports.ImportedRow;
import com.financeapp.core.imports.ImportedTransaction;
import com.financeapp.core.imports.OfxParser;
import com.financeapp.core.imports.QifParser;
import com.financeapp.core.imports.Reconciliation;
import com.financeapp.core.imports.TextDecoding;
import com.financeapp.core.money.Money;
import com.financeapp.core.planning.PlannedItem;
import com.financeapp.core.port.ImportRepository;
import com.financeapp.core.port.TransactionRepository;
import com.financeapp.core.transaction.Transaction;
import com.financeapp.core.transaction.TransactionStatus;
import com.financeapp.core.transaction.TransactionType;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Import de releves bancaires (CSV, OFX, QIF) : lecture, analyse (doublons,
 * rapprochements, categories proposees), puis enregistrement des seules lignes
 * confirmees par l'utilisateur. Chaque import est annulable.
 */
public final class ImportService {

    public enum Format { CSV, OFX, QIF }

    /** Choix de l'utilisateur pour une ligne de l'apercu. */
    public record Decision(ImportCandidate candidate, boolean include, Long categoryId) {
    }

    private final ImportRepository imports;
    private final TransactionRepository transactions;
    private final AccountService accounts;
    private final PlanningService planning;
    private final CategorizationService categorization;
    private final ImportPlanner planner = new ImportPlanner();

    public ImportService(ImportRepository imports, TransactionRepository transactions, AccountService accounts,
                         PlanningService planning, CategorizationService categorization) {
        this.imports = imports;
        this.transactions = transactions;
        this.accounts = accounts;
        this.planning = planning;
        this.categorization = categorization;
    }

    public static Format detectFormat(String fileName, byte[] content) {
        String name = fileName.toLowerCase(Locale.ROOT);
        String head = new String(content, 0, Math.min(content.length, 512), java.nio.charset.StandardCharsets.ISO_8859_1)
                .strip().toUpperCase(Locale.ROOT);
        if (name.endsWith(".ofx") || name.endsWith(".qfx") || head.contains("<OFX") || head.startsWith("OFXHEADER")) {
            return Format.OFX;
        }
        if (name.endsWith(".qif") || head.startsWith("!TYPE") || head.startsWith("!ACCOUNT")) {
            return Format.QIF;
        }
        return Format.CSV;
    }

    public CsvTable readCsv(byte[] content) {
        return new CsvReader().read(content);
    }

    public CsvMapping guessMapping(CsvTable table) {
        if (table.rows().isEmpty()) {
            throw new BusinessException("Le fichier est vide");
        }
        return new CsvMappingGuesser().guess(table);
    }

    public List<ImportedRow> convertCsv(CsvTable table, CsvMapping mapping) {
        return new CsvRowConverter().convert(table, mapping);
    }

    public List<ImportedRow> parseOfx(byte[] content) {
        return new OfxParser().parse(TextDecoding.decode(content));
    }

    public List<ImportedRow> parseQif(byte[] content) {
        return new QifParser().parse(TextDecoding.decode(content));
    }

    /** Analyse les lignes par rapport aux donnees existantes du compte. Rien n'est enregistre. */
    public List<ImportCandidate> plan(long accountId, List<ImportedRow> rows) {
        Account account = activeAccount(accountId);
        List<LocalDate> dates = rows.stream().filter(ImportedRow::isValid).map(ImportedRow::date).toList();
        if (dates.isEmpty()) {
            return planner.plan(rows, accountId, account.currency(), List.of(), java.util.Set.of(), List.of(),
                    categorization.engine());
        }
        LocalDate min = dates.stream().min(Comparator.naturalOrder()).orElseThrow();
        LocalDate max = dates.stream().max(Comparator.naturalOrder()).orElseThrow();
        List<Transaction> existing = transactions.findCounted(min.minusDays(7), max.plusDays(7));
        List<PlannedItem> pending = planning.upcoming(max.plusDays(7));
        return planner.plan(rows, accountId, account.currency(), existing, imports.externalIds(accountId), pending,
                categorization.engine());
    }

    /** Enregistre les lignes cochees, atomiquement. Les operations creees arrivent dans l'Inbox "a valider". */
    public ImportBatch commit(long accountId, String fileName, Format format, List<Decision> decisions) {
        Account account = activeAccount(accountId);
        List<ImportedTransaction> created = new ArrayList<>();
        List<Reconciliation> reconciliations = new ArrayList<>();
        int realizedOccurrences = 0;
        for (Decision d : decisions) {
            ImportCandidate c = d.candidate();
            if (!d.include() || c.kind() == ImportCandidate.Kind.INVALID) {
                continue;
            }
            ImportedRow row = c.row();
            if (c.kind() == ImportCandidate.Kind.MATCHES_PLANNED) {
                Transaction planned = transactions.findById(c.matchedPlanned().transactionId())
                        .orElseThrow(() -> new BusinessException("Opération prévue introuvable : " + c.matchedPlanned().label()));
                reconciliations.add(new Reconciliation(planned.id(), row.date(), planned.label(),
                        planned.status(), planned.date(), planned.label()));
                continue;
            }
            Money amount = Money.of(row.amount(), account.currency());
            TransactionType type = amount.isNegative() ? TransactionType.EXPENSE : TransactionType.INCOME;
            Long recurringId = null;
            LocalDate occurrence = null;
            if (c.kind() == ImportCandidate.Kind.MATCHES_RECURRING) {
                PlannedItem p = c.matchedPlanned();
                recurringId = p.recurringId();
                occurrence = p.date();
                realizedOccurrences++;
            }
            Transaction t = new Transaction(null, accountId, row.date(), row.label(), amount, type,
                    TransactionStatus.COMPLETED, d.categoryId(), null, null, null, recurringId, occurrence);
            created.add(new ImportedTransaction(t, row.externalId()));
        }
        int skipped = decisions.size() - created.size() - reconciliations.size();
        // Compteurs affiches : "creees" = operations nouvelles ; "rapprochees" = operations prevues et
        // echeances recurrentes realisees (une echeance realisee est enregistree comme une operation liee
        // a son occurrence, mais n'est pas une operation nouvelle pour l'utilisateur).
        ImportBatch batch = new ImportBatch(null, accountId, fileName, format.name(), Instant.now(),
                created.size() - realizedOccurrences, reconciliations.size() + realizedOccurrences, skipped, false);
        return imports.commit(batch, created, reconciliations);
    }

    public List<ImportBatch> batches() {
        return imports.findAll();
    }

    public void undo(long batchId) {
        imports.undo(batchId);
    }

    private Account activeAccount(long id) {
        Account account = accounts.get(id);
        if (account.archived()) {
            throw new BusinessException("Le compte « " + account.name() + " » est archivé");
        }
        return account;
    }
}
