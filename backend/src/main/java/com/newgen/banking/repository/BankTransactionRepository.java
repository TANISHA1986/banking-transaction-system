package com.newgen.banking.repository;

import com.newgen.banking.entity.BankTransaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BankTransactionRepository extends JpaRepository<BankTransaction, Long> {

    @EntityGraph(attributePaths = {"sourceAccount", "destinationAccount"})
    Optional<BankTransaction> findByTransactionId(String transactionId);

    boolean existsByTransactionId(String transactionId);

    @EntityGraph(attributePaths = {"sourceAccount", "destinationAccount"})
    @Query("SELECT t FROM BankTransaction t WHERE t.sourceAccount.id = :accountId OR t.destinationAccount.id = :accountId")
    Page<BankTransaction> findByAccountId(@Param("accountId") Long accountId, Pageable pageable);
}
