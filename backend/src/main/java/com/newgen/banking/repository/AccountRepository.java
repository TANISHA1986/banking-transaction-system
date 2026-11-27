package com.newgen.banking.repository;

import com.newgen.banking.entity.Account;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AccountRepository extends JpaRepository<Account, Long> {

    @EntityGraph(attributePaths = "customer")
    Optional<Account> findWithCustomerById(Long id);

    @EntityGraph(attributePaths = "customer")
    Optional<Account> findByAccountNumber(String accountNumber);

    boolean existsByAccountNumber(String accountNumber);

    @EntityGraph(attributePaths = "customer")
    List<Account> findByCustomerIdOrderByIdAsc(Long customerId);

    @Override
    @EntityGraph(attributePaths = "customer")
    Page<Account> findAll(Pageable pageable);

    @Query(value = "SELECT nextval('account_number_seq')", nativeQuery = true)
    Long getNextAccountNumberSequence();

    /**
     * Acquires a pessimistic write lock (SELECT ... FOR UPDATE) on a single account row.
     * Used for deposit and withdrawal to prevent concurrent lost updates.
     * The calling method MUST be inside an active @Transactional context.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.id = :id")
    Optional<Account> findByIdForUpdate(@Param("id") Long id);

    /**
     * Acquires pessimistic write locks on two or more account rows in a single query,
     * ordered by ID ascending. Always passing IDs in ascending order prevents deadlocks
     * when two transfers run in opposite directions simultaneously.
     * Used for transfer operations.
     * The calling method MUST be inside an active @Transactional context.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.id IN :ids ORDER BY a.id ASC")
    List<Account> findByIdsForUpdate(@Param("ids") List<Long> ids);
}
