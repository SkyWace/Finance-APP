package com.financeapp.core.port;

import com.financeapp.core.account.AccountValuation;

import java.util.List;
import java.util.Map;

public interface ValuationRepository {

    /** Valorisations du compte, de la plus recente a la plus ancienne. */
    List<AccountValuation> findByAccount(long accountId);

    /** Derniere valorisation de chaque compte qui en a une. */
    Map<Long, AccountValuation> latestByAccount();

    /** Enregistre (une seule valorisation par compte et par date : la nouvelle remplace l'ancienne). */
    AccountValuation save(AccountValuation valuation);

    void delete(long id);
}
