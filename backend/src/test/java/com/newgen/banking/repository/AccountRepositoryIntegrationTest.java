package com.newgen.banking.repository;

import com.newgen.banking.entity.Account;
import com.newgen.banking.entity.AccountStatus;
import com.newgen.banking.entity.AccountType;
import com.newgen.banking.entity.Customer;
import jakarta.persistence.EntityManager;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Phase 1 PostgreSQL repository & schema constraint tests against banking_test_db.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AccountRepositoryIntegrationTest {

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void saveAndFindAccount_ShouldPersistAndRetrieveByNumberAndCustomer() {
        Customer customer = saveCustomer("Ananya Rao", "ananya.rao@example.com");

        Long seq1 = accountRepository.getNextAccountNumberSequence();
        Long seq2 = accountRepository.getNextAccountNumberSequence();
        assertNotNull(seq1);
        assertNotNull(seq2);
        assertNotEquals(seq1, seq2);

        String accountNumber = "NG" + seq1;

        Account account = new Account();
        account.setAccountNumber(accountNumber);
        account.setCustomer(customer);
        account.setAccountType(AccountType.SAVINGS);
        account.setBalance(new BigDecimal("1500.50"));
        account.setStatus(AccountStatus.ACTIVE);

        Account saved = accountRepository.saveAndFlush(account);
        entityManager.clear();

        assertNotNull(saved.getId());
        assertNotNull(saved.getCreatedAt());
        assertNotNull(saved.getUpdatedAt());

        Account byNumber = accountRepository.findByAccountNumber(accountNumber).orElseThrow();
        assertEquals(saved.getId(), byNumber.getId());
        assertEquals(new BigDecimal("1500.50"), byNumber.getBalance());
        assertEquals(AccountType.SAVINGS, byNumber.getAccountType());
        assertEquals(AccountStatus.ACTIVE, byNumber.getStatus());
        assertEquals(customer.getId(), byNumber.getCustomer().getId());

        List<Account> byCustomer = accountRepository.findByCustomerIdOrderByIdAsc(customer.getId());
        assertEquals(1, byCustomer.size());
        assertEquals(accountNumber, byCustomer.get(0).getAccountNumber());
    }

    @Test
    void saveAccount_ShouldEnforceUniqueAccountNumberConstraint() {
        Customer customer = saveCustomer("Vikram Mehta", "vikram.mehta@example.com");

        Account first = new Account();
        first.setAccountNumber("NG9000000001");
        first.setCustomer(customer);
        first.setAccountType(AccountType.SAVINGS);
        first.setBalance(new BigDecimal("100.00"));
        first.setStatus(AccountStatus.ACTIVE);
        accountRepository.saveAndFlush(first);

        Account duplicate = new Account();
        duplicate.setAccountNumber("NG9000000001");
        duplicate.setCustomer(customer);
        duplicate.setAccountType(AccountType.CURRENT);
        duplicate.setBalance(new BigDecimal("200.00"));
        duplicate.setStatus(AccountStatus.ACTIVE);

        assertThrows(DataIntegrityViolationException.class, () -> accountRepository.saveAndFlush(duplicate));
    }

    @Test
    void saveAccount_ShouldEnforceNonNegativeBalanceCheckConstraint() {
        Customer customer = saveCustomer("Neha Kapoor", "neha.kapoor@example.com");

        Account invalidBalance = new Account();
        invalidBalance.setAccountNumber("NG9000000002");
        invalidBalance.setCustomer(customer);
        invalidBalance.setAccountType(AccountType.SAVINGS);
        invalidBalance.setBalance(new BigDecimal("-10.00"));
        invalidBalance.setStatus(AccountStatus.ACTIVE);

        assertThrows(DataIntegrityViolationException.class, () -> accountRepository.saveAndFlush(invalidBalance));
    }

    @Test
    void insertAccount_ShouldRejectMissingCustomerForeignKey() {
        ConstraintViolationException ex = assertThrows(ConstraintViolationException.class, () -> {
            entityManager.createNativeQuery(
                    "INSERT INTO accounts (account_number, customer_id, account_type, balance, status) " +
                    "VALUES ('NG9000000003', 999999999, 'SAVINGS', 100.00, 'ACTIVE')"
            ).executeUpdate();
            entityManager.flush();
        });
        assertEquals("23503", ex.getSQLState());
    }

    private Customer saveCustomer(String fullName, String email) {
        Customer customer = new Customer();
        customer.setFullName(fullName);
        customer.setEmail(email);
        customer.setPhone("9876543210");
        return customerRepository.saveAndFlush(customer);
    }
}
