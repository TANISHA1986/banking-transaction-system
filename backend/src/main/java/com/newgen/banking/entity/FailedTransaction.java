package com.newgen.banking.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * Entity recording categorized reasons for failed or rejected banking transactions.
 */
@Entity
@Table(name = "failed_transactions")
public class FailedTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bank_transaction_id", nullable = false, unique = true)
    private BankTransaction bankTransaction;

    @Column(name = "transaction_id", nullable = false, length = 64)
    private String transactionId;

    @Column(name = "error_category", nullable = false, length = 100)
    private String errorCategory;

    @Column(name = "failure_reason", nullable = false, length = 500)
    private String failureReason;

    @Column(name = "failed_at", nullable = false, updatable = false)
    private LocalDateTime failedAt;

    public FailedTransaction() {
    }

    @PrePersist
    protected void onCreate() {
        if (failedAt == null) {
            failedAt = LocalDateTime.now();
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public BankTransaction getBankTransaction() {
        return bankTransaction;
    }

    public void setBankTransaction(BankTransaction bankTransaction) {
        this.bankTransaction = bankTransaction;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(String transactionId) {
        this.transactionId = transactionId;
    }

    public String getErrorCategory() {
        return errorCategory;
    }

    public void setErrorCategory(String errorCategory) {
        this.errorCategory = errorCategory;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public LocalDateTime getFailedAt() {
        return failedAt;
    }

    public void setFailedAt(LocalDateTime failedAt) {
        this.failedAt = failedAt;
    }
}
