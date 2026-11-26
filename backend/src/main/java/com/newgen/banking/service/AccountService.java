package com.newgen.banking.service;

import com.newgen.banking.dto.AccountRequest;
import com.newgen.banking.dto.AccountResponse;
import com.newgen.banking.dto.PagedResponse;
import com.newgen.banking.entity.Account;
import com.newgen.banking.entity.AccountStatus;
import com.newgen.banking.entity.Customer;
import com.newgen.banking.exception.AccountNotFoundException;
import com.newgen.banking.exception.CustomerNotFoundException;
import com.newgen.banking.exception.DuplicateResourceException;
import com.newgen.banking.repository.AccountRepository;
import com.newgen.banking.repository.CustomerRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Service layer for bank account creation and retrieval business rules.
 */
@Service
public class AccountService {

    private static final String ACCOUNT_NUMBER_PREFIX = "NG";
    private static final int MAX_SEQUENCE_ATTEMPTS = 5;
    private static final int MAX_PAGE_SIZE = 100;
    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of(
            "id", "accountNumber", "accountType", "balance", "status", "createdAt", "updatedAt"
    );

    private final AccountRepository accountRepository;
    private final CustomerRepository customerRepository;

    public AccountService(AccountRepository accountRepository, CustomerRepository customerRepository) {
        this.accountRepository = accountRepository;
        this.customerRepository = customerRepository;
    }

    @Transactional
    public AccountResponse createAccount(AccountRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Account request must not be null");
        }
        validatePositiveId(request.customerId(), "Customer ID");
        if (request.accountType() == null) {
            throw new IllegalArgumentException("Account type is required");
        }

        Customer customer = customerRepository.findById(request.customerId())
                .orElseThrow(() -> new CustomerNotFoundException(
                        "Customer not found with ID: " + request.customerId()
                ));

        BigDecimal initialBalance = normalizeInitialBalance(request.initialBalance());
        String accountNumber = generateUniqueAccountNumber();

        Account account = new Account();
        account.setAccountNumber(accountNumber);
        account.setCustomer(customer);
        account.setAccountType(request.accountType());
        account.setBalance(initialBalance);
        account.setStatus(AccountStatus.ACTIVE);

        Account savedAccount = accountRepository.save(account);
        return AccountResponse.from(savedAccount);
    }

    @Transactional(readOnly = true)
    public AccountResponse getAccountById(Long id) {
        validatePositiveId(id, "Account ID");
        Account account = accountRepository.findWithCustomerById(id)
                .orElseThrow(() -> new AccountNotFoundException("Account not found with ID: " + id));
        return AccountResponse.from(account);
    }

    @Transactional(readOnly = true)
    public AccountResponse getAccountByAccountNumber(String accountNumber) {
        if (accountNumber == null || accountNumber.isBlank()) {
            throw new IllegalArgumentException("Account number is required");
        }
        String normalizedNumber = accountNumber.trim().toUpperCase(Locale.ROOT);
        Account account = accountRepository.findByAccountNumber(normalizedNumber)
                .orElseThrow(() -> new AccountNotFoundException(
                        "Account not found with account number: " + normalizedNumber
                ));
        return AccountResponse.from(account);
    }

    @Transactional(readOnly = true)
    public PagedResponse<AccountResponse> getAllAccounts(int page, int size, String sortBy, String sortDir) {
        if (page < 0) {
            throw new IllegalArgumentException("Page index must be 0 or greater");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("Page size must be between 1 and " + MAX_PAGE_SIZE);
        }
        if (!ALLOWED_SORT_FIELDS.contains(sortBy)) {
            throw new IllegalArgumentException("Invalid sort field: " + sortBy);
        }

        Sort.Direction direction = "desc".equalsIgnoreCase(sortDir)
                ? Sort.Direction.DESC
                : Sort.Direction.ASC;
        Pageable pageable = PageRequest.of(page, size, Sort.by(direction, sortBy));
        Page<AccountResponse> accountPage = accountRepository.findAll(pageable)
                .map(AccountResponse::from);
        return PagedResponse.from(accountPage);
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> getAccountsByCustomerId(Long customerId) {
        validatePositiveId(customerId, "Customer ID");
        if (!customerRepository.existsById(customerId)) {
            throw new CustomerNotFoundException("Customer not found with ID: " + customerId);
        }
        return accountRepository.findByCustomerIdOrderByIdAsc(customerId)
                .stream()
                .map(AccountResponse::from)
                .toList();
    }

    private BigDecimal normalizeInitialBalance(BigDecimal rawBalance) {
        if (rawBalance == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        if (rawBalance.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Initial balance cannot be negative");
        }
        if (rawBalance.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("Initial balance cannot have more than 2 decimal places");
        }
        return rawBalance.setScale(2, RoundingMode.HALF_UP);
    }

    private String generateUniqueAccountNumber() {
        for (int attempt = 0; attempt < MAX_SEQUENCE_ATTEMPTS; attempt++) {
            Long sequence = accountRepository.getNextAccountNumberSequence();
            if (sequence == null || sequence <= 0) {
                throw new IllegalStateException("Failed to generate account number sequence");
            }
            String candidate = ACCOUNT_NUMBER_PREFIX + sequence;
            if (!accountRepository.existsByAccountNumber(candidate)) {
                return candidate;
            }
        }
        throw new DuplicateResourceException("Unable to allocate a unique account number");
    }

    private void validatePositiveId(Long id, String label) {
        if (id == null || id <= 0) {
            throw new IllegalArgumentException(label + " must be a positive number");
        }
    }
}
