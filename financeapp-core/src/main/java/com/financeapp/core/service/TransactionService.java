package com.financeapp.core.service;

import com.financeapp.core.account.Account;
import com.financeapp.core.money.Money;
import com.financeapp.core.port.AccountRepository;
import com.financeapp.core.port.TransactionQuery;
import com.financeapp.core.port.TransactionRepository;
import com.financeapp.core.transaction.Transaction;
import com.financeapp.core.transaction.TransactionStatus;
import com.financeapp.core.transaction.TransactionType;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Saisie, modification et suppression des operations, virements internes compris. */
public final class TransactionService {

    private final AccountRepository accounts;
    private final TransactionRepository transactions;

    public TransactionService(AccountRepository accounts, TransactionRepository transactions) {
        this.accounts = accounts;
        this.transactions = transactions;
    }

    public List<Transaction> search(TransactionQuery query) {
        return transactions.search(query);
    }

    public Transaction get(long id) {
        return transactions.findById(id).orElseThrow(() -> new BusinessException("Opération introuvable"));
    }

    public Transaction create(TransactionDraft draft) {
        return transactions.insert(toTransaction(null, draft, null, null));
    }

    public Transaction update(long id, TransactionDraft draft) {
        Transaction existing = get(id);
        if (existing.isTransfer()) {
            throw new BusinessException("Un virement se modifie comme un virement");
        }
        return transactions.update(toTransaction(id, draft, existing.recurringId(), existing.occurrenceDate()));
    }

    /** Cree les deux jambes d'un virement, de facon atomique. Impact sur le patrimoine : nul. */
    public List<Transaction> createTransfer(TransferDraft draft) {
        return transactions.insertAll(transferLegs(draft, UUID.randomUUID().toString(), null, null, null, null));
    }

    public List<Transaction> updateTransfer(String transferGroup, TransferDraft draft) {
        List<Transaction> legs = legsOf(transferGroup);
        Transaction out = legs.get(0);
        Transaction in = legs.get(1);
        List<Transaction> updated = transferLegs(draft, transferGroup, out.id(), in.id(), out.recurringId(), out.occurrenceDate());
        transactions.updateAll(updated);
        return updated;
    }

    /** Jambes d'un virement, la jambe debitrice en premier. */
    public List<Transaction> legsOf(String transferGroup) {
        List<Transaction> legs = new ArrayList<>(transactions.findByTransferGroup(transferGroup));
        if (legs.size() != 2) {
            throw new BusinessException("Virement incomplet (" + legs.size() + " ligne(s))");
        }
        legs.sort(Comparator.comparing(t -> t.amount().signum()));
        return legs;
    }

    /** Supprime une operation ; pour un virement, supprime les deux jambes. */
    public void delete(long id) {
        Transaction t = get(id);
        if (t.isTransfer()) {
            transactions.deleteTransferGroup(t.transferGroup());
        } else {
            transactions.delete(id);
        }
    }

    public void setStatus(long id, TransactionStatus status) {
        Transaction t = get(id);
        if (t.isTransfer()) {
            transactions.updateAll(legsOf(t.transferGroup()).stream().map(l -> l.withStatus(status)).toList());
        } else {
            transactions.update(t.withStatus(status));
        }
    }

    private Transaction toTransaction(Long id, TransactionDraft d, Long recurringId, java.time.LocalDate occurrence) {
        if (d.type() == TransactionType.TRANSFER) {
            throw new BusinessException("Utilisez la saisie de virement");
        }
        Account account = activeAccount(d.accountId());
        Money amount = positiveAmount(d.amount(), account);
        Money signed = d.type() == TransactionType.EXPENSE ? amount.negate() : amount;
        return new Transaction(id, account.id(), d.date(), d.label(), signed, d.type(), d.status(),
                d.categoryId(), blankToNull(d.note()), null, null, recurringId, occurrence);
    }

    List<Transaction> transferLegs(TransferDraft d, String group, Long outId, Long inId,
                                   Long recurringId, java.time.LocalDate occurrence) {
        if (d.fromAccountId() == d.toAccountId()) {
            throw new BusinessException("Choisissez deux comptes différents");
        }
        Account from = activeAccount(d.fromAccountId());
        Account to = activeAccount(d.toAccountId());
        if (!from.currency().equals(to.currency())) {
            throw new BusinessException("Les virements entre devises différentes ne sont pas encore pris en charge");
        }
        Money amount = positiveAmount(d.amount(), from);
        String note = blankToNull(d.note());
        Transaction out = new Transaction(outId, from.id(), d.date(), d.label(), amount.negate(), TransactionType.TRANSFER,
                d.status(), null, note, group, to.id(), recurringId, occurrence);
        Transaction in = new Transaction(inId, to.id(), d.date(), d.label(), amount, TransactionType.TRANSFER,
                d.status(), null, note, group, from.id(), recurringId, occurrence);
        return List.of(out, in);
    }

    private Account activeAccount(long id) {
        Account account = accounts.findById(id).orElseThrow(() -> new BusinessException("Compte introuvable"));
        if (account.archived()) {
            throw new BusinessException("Le compte « " + account.name() + " » est archivé");
        }
        return account;
    }

    private static Money positiveAmount(BigDecimal value, Account account) {
        if (value == null || value.signum() <= 0) {
            throw new BusinessException("Le montant doit être strictement positif");
        }
        Money amount = Money.of(value, account.currency());
        if (amount.isZero()) {
            throw new BusinessException("Le montant est trop petit pour la devise du compte");
        }
        return amount;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
