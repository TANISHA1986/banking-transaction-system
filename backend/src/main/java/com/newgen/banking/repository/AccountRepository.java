package com.newgen.banking.repository;

import com.newgen.banking.entity.Account;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
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
}
