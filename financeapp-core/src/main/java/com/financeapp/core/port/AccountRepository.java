package com.financeapp.core.port;

import com.financeapp.core.account.Account;

import java.util.List;
import java.util.Optional;

public interface AccountRepository {

    /** Tous les comptes, archives compris, dans l'ordre d'affichage. */
    List<Account> findAll();

    Optional<Account> findById(long id);

    /** Insere (id {@code null}) ou met a jour ; renvoie le compte avec son identifiant. */
    Account save(Account account);

    /** Suppression definitive : reservee aux comptes sans aucune operation ni recurrence. */
    void delete(long id);
}
