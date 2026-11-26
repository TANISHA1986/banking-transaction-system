package com.newgen.banking.service;

import com.newgen.banking.dto.DepositRequest;
import com.newgen.banking.dto.PagedResponse;
import com.newgen.banking.dto.TransactionResponse;
import com.newgen.banking.dto.TransferRequest;
import com.newgen.banking.dto.WithdrawalRequest;
import com.newgen.banking.entity.Account;
import com.newgen.banking.entity.AccountStatus;
import com.newgen.banking.entity.AccountType;
import com.newgen.banking.entity.BankTransaction;
import com.newgen.banking.entity.Customer;
import com.newgen.banking.entity.FailedTransaction;
import com.newgen.banking.entity.TransactionAudit;
import com.newgen.banking.entity.TransactionStatus;
import com.newgen.banking.entity.TransactionType;
import com.newgen.banking.exception.AccountNotFoundException;
import com.newgen.banking.exception.DuplicateTransactionException;
import com.newgen.banking.exception.InactiveAccountException;
import com.newgen.banking.exception.InsufficientBalanceException;
import com.newgen.banking.exception.TransactionNotFoundException;
import com.newgen.banking.repository.AccountRepository;
import com.newgen.banking.repository.BankTransactionRepository;
import com.newgen.banking.repository.FailedTransactionRepository;
import com.newgen.banking.repository.TransactionAuditRepository;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private BankTransactionRepository bankTransactionRepository;

    @Mock
    private TransactionAuditRepository transactionAuditRepository;

    @Mock
    private FailedTransactionRepository failedTransactionRepository;

    @InjectMocks
    private TransactionService transactionService;

    @Test
    void deposit_ShouldCreditAccountAndRecordSuccess_WhenAccountIsActive() {
        Account account = buildAccount(1L, "NG1000000001", new BigDecimal("1000.00"), AccountStatus.ACTIVE);
        DepositRequest request = new DepositRequest("TXN-DEP-1", 1L, new BigDecimal("500.5"), "Salary credit");

        when(bankTransactionRepository.existsByTransactionId("TXN-DEP-1")).thenReturn(false);
        when(accountRepository.findWithCustomerById(1L)).thenReturn(Optional.of(account));
        stubTransactionSave();

        TransactionResponse response = transactionService.deposit(request);

        assertEquals(new BigDecimal("1500.50"), account.getBalance());
        assertEquals("TXN-DEP-1", response.transactionId());
        assertEquals(TransactionType.DEPOSIT, response.transactionType());
        assertEquals(TransactionStatus.SUCCESS, response.status());
        assertEquals(new BigDecimal("500.50"), response.amount());
        assertNull(response.sourceAccountId());
        assertEquals(1L, response.destinationAccountId());

        verify(accountRepository).save(account);
        verify(transactionAuditRepository).save(any(TransactionAudit.class));
        verify(failedTransactionRepository, never()).save(any());
    }

    @Test
    void withdraw_ShouldDebitAccountAndRecordSuccess_WhenBalanceIsSufficient() {
        Account account = buildAccount(1L, "NG1000000001", new BigDecimal("1000.00"), AccountStatus.ACTIVE);
        WithdrawalRequest request = new WithdrawalRequest("TXN-WDR-1", 1L, new BigDecimal("250.25"), "ATM cash");

        when(bankTransactionRepository.existsByTransactionId("TXN-WDR-1")).thenReturn(false);
        when(accountRepository.findWithCustomerById(1L)).thenReturn(Optional.of(account));
        stubTransactionSave();

        TransactionResponse response = transactionService.withdraw(request);

        assertEquals(new BigDecimal("749.75"), account.getBalance());
        assertEquals(TransactionType.WITHDRAWAL, response.transactionType());
        assertEquals(TransactionStatus.SUCCESS, response.status());
        assertEquals(1L, response.sourceAccountId());
        assertNull(response.destinationAccountId());

        verify(accountRepository).save(account);
        verify(transactionAuditRepository).save(any(TransactionAudit.class));
    }

    @Test
    void withdraw_ShouldThrowInsufficientBalanceAndRecordFailure_WhenBalanceTooLow() {
        Account account = buildAccount(1L, "NG1000000001", new BigDecimal("200.00"), AccountStatus.ACTIVE);
        WithdrawalRequest request = new WithdrawalRequest("TXN-WDR-FAIL", 1L, new BigDecimal("500.00"), "Rent");

        when(bankTransactionRepository.existsByTransactionId("TXN-WDR-FAIL")).thenReturn(false);
        when(accountRepository.findWithCustomerById(1L)).thenReturn(Optional.of(account));
        stubTransactionSave();

        assertThrows(InsufficientBalanceException.class, () -> transactionService.withdraw(request));

        assertEquals(new BigDecimal("200.00"), account.getBalance());
        verify(accountRepository, never()).save(any());

        ArgumentCaptor<BankTransaction> txCaptor = ArgumentCaptor.forClass(BankTransaction.class);
        verify(bankTransactionRepository).save(txCaptor.capture());
        assertEquals(TransactionStatus.FAILED, txCaptor.getValue().getStatus());

        ArgumentCaptor<FailedTransaction> failedCaptor = ArgumentCaptor.forClass(FailedTransaction.class);
        verify(failedTransactionRepository).save(failedCaptor.capture());
        assertEquals("INSUFFICIENT_BALANCE", failedCaptor.getValue().getErrorCategory());
        verify(transactionAuditRepository).save(any(TransactionAudit.class));
    }

    @Test
    void operations_ShouldRejectInvalidAmount_WithoutModifyingAccounts() {
        assertThrows(IllegalArgumentException.class,
                () -> transactionService.deposit(new DepositRequest("TXN-1", 1L, BigDecimal.ZERO, null)));
        assertThrows(IllegalArgumentException.class,
                () -> transactionService.withdraw(new WithdrawalRequest("TXN-2", 1L, new BigDecimal("-10.00"), null)));
        assertThrows(IllegalArgumentException.class,
                () -> transactionService.transfer(new TransferRequest("TXN-3", 1L, 2L, new BigDecimal("10.999"), null)));

        verify(accountRepository, never()).save(any());
        verify(bankTransactionRepository, never()).save(any());
    }

    @Test
    void deposit_ShouldThrowInactiveAccountAndRecordRejected_WhenAccountIsNotActive() {
        Account frozen = buildAccount(1L, "NG1000000001", new BigDecimal("500.00"), AccountStatus.FROZEN);
        DepositRequest request = new DepositRequest("TXN-FROZEN", 1L, new BigDecimal("100.00"), null);

        when(bankTransactionRepository.existsByTransactionId("TXN-FROZEN")).thenReturn(false);
        when(accountRepository.findWithCustomerById(1L)).thenReturn(Optional.of(frozen));
        stubTransactionSave();

        assertThrows(InactiveAccountException.class, () -> transactionService.deposit(request));

        assertEquals(new BigDecimal("500.00"), frozen.getBalance());
        verify(accountRepository, never()).save(any());

        ArgumentCaptor<BankTransaction> txCaptor = ArgumentCaptor.forClass(BankTransaction.class);
        verify(bankTransactionRepository).save(txCaptor.capture());
        assertEquals(TransactionStatus.REJECTED, txCaptor.getValue().getStatus());

        ArgumentCaptor<FailedTransaction> failedCaptor = ArgumentCaptor.forClass(FailedTransaction.class);
        verify(failedTransactionRepository).save(failedCaptor.capture());
        assertEquals("INACTIVE_ACCOUNT", failedCaptor.getValue().getErrorCategory());
    }

    @Test
    void transfer_ShouldUpdateBothAccountsAndRecordSuccess_WhenValid() {
        Account source = buildAccount(1L, "NG1000000001", new BigDecimal("1000.00"), AccountStatus.ACTIVE);
        Account destination = buildAccount(2L, "NG1000000002", new BigDecimal("300.00"), AccountStatus.ACTIVE);
        TransferRequest request = new TransferRequest("TXN-TRF-1", 1L, 2L, new BigDecimal("400.00"), "Savings move");

        when(bankTransactionRepository.existsByTransactionId("TXN-TRF-1")).thenReturn(false);
        when(accountRepository.findWithCustomerById(1L)).thenReturn(Optional.of(source));
        when(accountRepository.findWithCustomerById(2L)).thenReturn(Optional.of(destination));
        stubTransactionSave();

        TransactionResponse response = transactionService.transfer(request);

        assertEquals(new BigDecimal("600.00"), source.getBalance());
        assertEquals(new BigDecimal("700.00"), destination.getBalance());
        assertEquals(TransactionType.TRANSFER, response.transactionType());
        assertEquals(TransactionStatus.SUCCESS, response.status());
        assertEquals(1L, response.sourceAccountId());
        assertEquals(2L, response.destinationAccountId());

        verify(accountRepository, times(2)).save(any(Account.class));
        verify(transactionAuditRepository).save(any(TransactionAudit.class));
    }

    @Test
    void transfer_ShouldThrowAccountNotFoundAndRecordRejected_WhenDestinationMissing() {
        Account source = buildAccount(1L, "NG1000000001", new BigDecimal("1000.00"), AccountStatus.ACTIVE);
        TransferRequest request = new TransferRequest("TXN-TRF-MISSING", 1L, 99L, new BigDecimal("100.00"), null);

        when(bankTransactionRepository.existsByTransactionId("TXN-TRF-MISSING")).thenReturn(false);
        when(accountRepository.findWithCustomerById(1L)).thenReturn(Optional.of(source));
        when(accountRepository.findWithCustomerById(99L)).thenReturn(Optional.empty());
        stubTransactionSave();

        assertThrows(AccountNotFoundException.class, () -> transactionService.transfer(request));

        assertEquals(new BigDecimal("1000.00"), source.getBalance());
        verify(accountRepository, never()).save(any());
        verify(failedTransactionRepository).save(any(FailedTransaction.class));
    }

    @Test
    void transfer_ShouldRejectSameSourceAndDestinationAccount() {
        TransferRequest request = new TransferRequest("TXN-SAME", 1L, 1L, new BigDecimal("50.00"), null);

        assertThrows(IllegalArgumentException.class, () -> transactionService.transfer(request));
        verify(accountRepository, never()).findWithCustomerById(any());
        verify(bankTransactionRepository, never()).save(any());
    }

    @Test
    void deposit_ShouldThrowDuplicateTransactionException_WhenTransactionIdAlreadyExists() {
        DepositRequest request = new DepositRequest("TXN-DUP", 1L, new BigDecimal("100.00"), null);
        when(bankTransactionRepository.existsByTransactionId("TXN-DUP")).thenReturn(true);

        assertThrows(DuplicateTransactionException.class, () -> transactionService.deposit(request));

        verify(accountRepository, never()).findWithCustomerById(any());
        verify(accountRepository, never()).save(any());
        verify(bankTransactionRepository, never()).save(any());
    }

    @Test
    void lookupMethods_ShouldReturnTransactionAndAccountHistory_OrThrowNotFound() {
        Account source = buildAccount(1L, "NG1000000001", new BigDecimal("500.00"), AccountStatus.ACTIVE);
        BankTransaction tx = new BankTransaction();
        tx.setId(10L);
        tx.setTransactionId("TXN-LOOKUP");
        tx.setTransactionType(TransactionType.WITHDRAWAL);
        tx.setAmount(new BigDecimal("50.00"));
        tx.setSourceAccount(source);
        tx.setStatus(TransactionStatus.SUCCESS);
        tx.setCreatedAt(LocalDateTime.of(2026, 11, 26, 10, 30));

        when(bankTransactionRepository.findByTransactionId("TXN-LOOKUP")).thenReturn(Optional.of(tx));
        when(bankTransactionRepository.findByTransactionId("TXN-NONE")).thenReturn(Optional.empty());
        when(accountRepository.existsById(1L)).thenReturn(true);
        when(accountRepository.existsById(99L)).thenReturn(false);
        when(bankTransactionRepository.findByAccountId(eq(1L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(tx)));

        TransactionResponse single = transactionService.getTransactionByTransactionId("TXN-LOOKUP");
        assertEquals("TXN-LOOKUP", single.transactionId());

        assertThrows(TransactionNotFoundException.class,
                () -> transactionService.getTransactionByTransactionId("TXN-NONE"));

        PagedResponse<TransactionResponse> history =
                transactionService.getTransactionsByAccountId(1L, 0, 20, "createdAt", "desc");
        assertEquals(1, history.content().size());

        assertThrows(AccountNotFoundException.class,
                () -> transactionService.getTransactionsByAccountId(99L, 0, 20, "createdAt", "desc"));
    }

    private void stubTransactionSave() {
        when(bankTransactionRepository.save(any(BankTransaction.class))).thenAnswer(invocation -> {
            BankTransaction tx = invocation.getArgument(0);
            if (tx.getId() == null) {
                tx.setId(100L);
            }
            if (tx.getCreatedAt() == null) {
                tx.setCreatedAt(LocalDateTime.of(2026, 11, 26, 10, 30));
            }
            return tx;
        });
    }

    private static Account buildAccount(Long id, String accountNumber, BigDecimal balance, AccountStatus status) {
        Customer customer = new Customer();
        customer.setId(1L);
        customer.setFullName("Aarav Sharma");

        Account account = new Account();
        account.setId(id);
        account.setAccountNumber(accountNumber);
        account.setCustomer(customer);
        account.setAccountType(AccountType.SAVINGS);
        account.setBalance(balance);
        account.setStatus(status);
        return account;
    }
}
