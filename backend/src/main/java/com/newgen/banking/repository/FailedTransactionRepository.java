package com.newgen.banking.repository;

import com.newgen.banking.entity.FailedTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface FailedTransactionRepository extends JpaRepository<FailedTransaction, Long> {

    Optional<FailedTransaction> findByTransactionId(String transactionId);
}
