package com.financeapp.core.port;

import com.financeapp.core.loan.Loan;

import java.util.List;
import java.util.Optional;

public interface LoanRepository {

    List<Loan> findAll();

    Optional<Loan> findById(long id);

    Loan save(Loan loan);

    void delete(long id);
}
