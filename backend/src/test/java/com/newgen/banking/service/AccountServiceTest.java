package com.newgen.banking.service;

import com.newgen.banking.dto.AccountRequest;
import com.newgen.banking.dto.AccountResponse;
import com.newgen.banking.dto.PagedResponse;
import com.newgen.banking.entity.Account;
import com.newgen.banking.entity.AccountStatus;
import com.newgen.banking.entity.AccountType;
import com.newgen.banking.entity.Customer;
import com.newgen.banking.exception.AccountNotFoundException;
import com.newgen.banking.exception.CustomerNotFoundException;
import com.newgen.banking.repository.AccountRepository;
import com.newgen.banking.repository.CustomerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private CustomerRepository customerRepository;

    @InjectMocks
    private AccountService accountService;

    @Test
    void createAccount_ShouldCreateActiveAccount_WhenCustomerAndBalanceAreValid() {
        Customer customer = buildCustomer(1L, "Aarav Sharma");
        AccountRequest request = new AccountRequest(1L, AccountType.SAVINGS, new BigDecimal("2500.5"));

        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));
        when(accountRepository.getNextAccountNumberSequence()).thenReturn(1000000001L);
        when(accountRepository.existsByAccountNumber("NG1000000001")).thenReturn(false);
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> {
            Account saved = invocation.getArgument(0);
            saved.setId(10L);
            saved.setCreatedAt(LocalDateTime.of(2026, 11, 26, 10, 0));
            saved.setUpdatedAt(LocalDateTime.of(2026, 11, 26, 10, 0));
            return saved;
        });

        AccountResponse response = accountService.createAccount(request);

        ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(captor.capture());
        Account persisted = captor.getValue();

        assertEquals("NG1000000001", persisted.getAccountNumber());
        assertEquals(AccountType.SAVINGS, persisted.getAccountType());
        assertEquals(new BigDecimal("2500.50"), persisted.getBalance());
        assertEquals(AccountStatus.ACTIVE, persisted.getStatus());

        assertEquals(10L, response.id());
        assertEquals("NG1000000001", response.accountNumber());
        assertEquals(1L, response.customerId());
        assertEquals("Aarav Sharma", response.customerName());
        assertEquals(new BigDecimal("2500.50"), response.balance());
        assertEquals(AccountStatus.ACTIVE, response.status());
    }

    @Test
    void createAccount_ShouldDefaultBalanceToZero_WhenInitialBalanceIsNull() {
        Customer customer = buildCustomer(1L, "Aarav Sharma");
        AccountRequest request = new AccountRequest(1L, AccountType.CURRENT, null);

        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));
        when(accountRepository.getNextAccountNumberSequence()).thenReturn(1000000002L);
        when(accountRepository.existsByAccountNumber("NG1000000002")).thenReturn(false);
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AccountResponse response = accountService.createAccount(request);

        assertEquals(new BigDecimal("0.00"), response.balance());
        assertEquals(AccountStatus.ACTIVE, response.status());
    }

    @Test
    void createAccount_ShouldThrowCustomerNotFoundException_WhenCustomerDoesNotExist() {
        AccountRequest request = new AccountRequest(99L, AccountType.SAVINGS, new BigDecimal("500.00"));
        when(customerRepository.findById(99L)).thenReturn(Optional.empty());

        CustomerNotFoundException ex = assertThrows(
                CustomerNotFoundException.class,
                () -> accountService.createAccount(request)
        );

        assertEquals("Customer not found with ID: 99", ex.getMessage());
        verify(accountRepository, never()).save(any());
    }

    @Test
    void createAccount_ShouldRejectInvalidInitialBalanceOrCustomerId() {
        Customer customer = buildCustomer(1L, "Aarav Sharma");
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));

        assertThrows(IllegalArgumentException.class,
                () -> accountService.createAccount(new AccountRequest(0L, AccountType.SAVINGS, BigDecimal.TEN)));
        assertThrows(IllegalArgumentException.class,
                () -> accountService.createAccount(new AccountRequest(1L, AccountType.SAVINGS, new BigDecimal("-1.00"))));
        assertThrows(IllegalArgumentException.class,
                () -> accountService.createAccount(new AccountRequest(1L, AccountType.SAVINGS, new BigDecimal("10.999"))));

        verify(accountRepository, never()).save(any());
    }

    @Test
    void createAccount_ShouldGenerateUniqueAccountNumbersAndRetryIfCollisionOccurs() {
        Customer customer = buildCustomer(1L, "Aarav Sharma");
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));
        when(accountRepository.getNextAccountNumberSequence())
                .thenReturn(1000000001L, 1000000002L, 1000000003L);
        when(accountRepository.existsByAccountNumber("NG1000000001")).thenReturn(true);
        when(accountRepository.existsByAccountNumber("NG1000000002")).thenReturn(false);
        when(accountRepository.existsByAccountNumber("NG1000000003")).thenReturn(false);
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AccountResponse first = accountService.createAccount(new AccountRequest(1L, AccountType.SAVINGS, BigDecimal.TEN));
        AccountResponse second = accountService.createAccount(new AccountRequest(1L, AccountType.CURRENT, BigDecimal.TEN));

        assertEquals("NG1000000002", first.accountNumber());
        assertEquals("NG1000000003", second.accountNumber());
        assertNotEquals(first.accountNumber(), second.accountNumber());
    }

    @Test
    void getAccountById_ShouldReturnAccount_WhenFound() {
        Customer customer = buildCustomer(2L, "Priya Nair");
        Account account = buildAccount(5L, "NG1000000005", customer, AccountType.SAVINGS, new BigDecimal("750.00"));
        when(accountRepository.findWithCustomerById(5L)).thenReturn(Optional.of(account));

        AccountResponse response = accountService.getAccountById(5L);

        assertEquals(5L, response.id());
        assertEquals("NG1000000005", response.accountNumber());
        assertEquals(2L, response.customerId());
        assertEquals("Priya Nair", response.customerName());
    }

    @Test
    void getAccountById_ShouldThrowAccountNotFoundException_WhenMissing() {
        when(accountRepository.findWithCustomerById(77L)).thenReturn(Optional.empty());

        AccountNotFoundException ex = assertThrows(
                AccountNotFoundException.class,
                () -> accountService.getAccountById(77L)
        );

        assertEquals("Account not found with ID: 77", ex.getMessage());
    }

    @Test
    void getAccountByAccountNumber_ShouldNormalizeAndReturnAccount() {
        Customer customer = buildCustomer(2L, "Priya Nair");
        Account account = buildAccount(5L, "NG1000000005", customer, AccountType.CURRENT, new BigDecimal("1200.00"));
        when(accountRepository.findByAccountNumber("NG1000000005")).thenReturn(Optional.of(account));

        AccountResponse response = accountService.getAccountByAccountNumber("  ng1000000005 ");

        assertEquals("NG1000000005", response.accountNumber());
        assertEquals(AccountType.CURRENT, response.accountType());
    }

    @Test
    void getAccountByAccountNumber_ShouldThrowAccountNotFoundException_WhenMissing() {
        when(accountRepository.findByAccountNumber("NG9999999999")).thenReturn(Optional.empty());

        assertThrows(
                AccountNotFoundException.class,
                () -> accountService.getAccountByAccountNumber("NG9999999999")
        );
    }

    @Test
    void getAccountsByCustomerId_ShouldReturnAccounts_WhenCustomerExists() {
        Customer customer = buildCustomer(3L, "Rohan Verma");
        List<Account> accounts = List.of(
                buildAccount(1L, "NG1000000010", customer, AccountType.SAVINGS, new BigDecimal("100.00")),
                buildAccount(2L, "NG1000000011", customer, AccountType.CURRENT, new BigDecimal("200.00"))
        );
        when(customerRepository.existsById(3L)).thenReturn(true);
        when(accountRepository.findByCustomerIdOrderByIdAsc(3L)).thenReturn(accounts);

        List<AccountResponse> result = accountService.getAccountsByCustomerId(3L);

        assertEquals(2, result.size());
        assertEquals("NG1000000010", result.get(0).accountNumber());
        assertEquals("NG1000000011", result.get(1).accountNumber());
    }

    @Test
    void getAccountsByCustomerId_ShouldThrowCustomerNotFoundException_WhenCustomerDoesNotExist() {
        when(customerRepository.existsById(88L)).thenReturn(false);

        assertThrows(CustomerNotFoundException.class, () -> accountService.getAccountsByCustomerId(88L));
        verify(accountRepository, never()).findByCustomerIdOrderByIdAsc(any());
    }

    @Test
    void getAllAccounts_ShouldReturnPagedResponse_AndValidateParameters() {
        Customer customer = buildCustomer(1L, "Aarav Sharma");
        Account account = buildAccount(1L, "NG1000000001", customer, AccountType.SAVINGS, new BigDecimal("500.00"));
        when(accountRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(account)));

        PagedResponse<AccountResponse> paged = accountService.getAllAccounts(0, 10, "id", "asc");

        assertEquals(1, paged.content().size());
        assertEquals("NG1000000001", paged.content().get(0).accountNumber());
        assertTrue(paged.first());

        assertThrows(IllegalArgumentException.class, () -> accountService.getAllAccounts(-1, 10, "id", "asc"));
        assertThrows(IllegalArgumentException.class, () -> accountService.getAllAccounts(0, 0, "id", "asc"));
        assertThrows(IllegalArgumentException.class, () -> accountService.getAllAccounts(0, 101, "id", "asc"));
        assertThrows(IllegalArgumentException.class, () -> accountService.getAllAccounts(0, 10, "unknownField", "asc"));
    }

    private static Customer buildCustomer(Long id, String fullName) {
        Customer customer = new Customer();
        customer.setId(id);
        customer.setFullName(fullName);
        customer.setEmail(fullName.toLowerCase().replace(" ", ".") + "@example.com");
        return customer;
    }

    private static Account buildAccount(
            Long id,
            String accountNumber,
            Customer customer,
            AccountType type,
            BigDecimal balance) {
        Account account = new Account();
        account.setId(id);
        account.setAccountNumber(accountNumber);
        account.setCustomer(customer);
        account.setAccountType(type);
        account.setBalance(balance);
        account.setStatus(AccountStatus.ACTIVE);
        account.setCreatedAt(LocalDateTime.of(2026, 11, 26, 10, 0));
        account.setUpdatedAt(LocalDateTime.of(2026, 11, 26, 10, 0));
        return account;
    }
}
