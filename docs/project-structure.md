# Project Structure

This document describes the current repository layout of `banking-transaction-system` as of Day 3.

```text
banking-transaction-system/
├── .gitignore
├── README.md
├── backend/
│   ├── .gitattributes
│   ├── .gitignore
│   ├── .mvn/
│   │   └── wrapper/
│   │       └── maven-wrapper.properties
│   ├── mvnw
│   ├── mvnw.cmd
│   ├── pom.xml
│   └── src/
│       ├── main/
│       │   ├── java/com/newgen/banking/
│       │   │   ├── BankingSystemApplication.java
│       │   │   ├── controller/
│       │   │   │   ├── AccountController.java
│       │   │   │   ├── CustomerController.java
│       │   │   │   ├── HealthController.java
│       │   │   │   └── TransactionController.java
│       │   │   ├── dto/
│       │   │   │   ├── AccountRequest.java
│       │   │   │   ├── AccountResponse.java
│       │   │   │   ├── ApiErrorResponse.java
│       │   │   │   ├── CustomerRequest.java
│       │   │   │   ├── CustomerResponse.java
│       │   │   │   ├── DepositRequest.java
│       │   │   │   ├── PagedResponse.java
│       │   │   │   ├── TransactionResponse.java
│       │   │   │   ├── TransferRequest.java
│       │   │   │   └── WithdrawalRequest.java
│       │   │   ├── entity/
│       │   │   │   ├── Account.java
│       │   │   │   ├── AccountStatus.java
│       │   │   │   ├── AccountType.java
│       │   │   │   ├── BankTransaction.java
│       │   │   │   ├── Customer.java
│       │   │   │   ├── FailedTransaction.java
│       │   │   │   ├── TransactionAudit.java
│       │   │   │   ├── TransactionStatus.java
│       │   │   │   └── TransactionType.java
│       │   │   ├── exception/
│       │   │   │   ├── AccountNotFoundException.java
│       │   │   │   ├── CustomerNotFoundException.java
│       │   │   │   ├── DuplicateResourceException.java
│       │   │   │   ├── DuplicateTransactionException.java
│       │   │   │   ├── GlobalExceptionHandler.java
│       │   │   │   ├── InactiveAccountException.java
│       │   │   │   ├── InsufficientBalanceException.java
│       │   │   │   ├── ResourceNotFoundException.java
│       │   │   │   └── TransactionNotFoundException.java
│       │   │   ├── repository/
│       │   │   │   ├── AccountRepository.java
│       │   │   │   ├── BankTransactionRepository.java
│       │   │   │   ├── CustomerRepository.java
│       │   │   │   ├── FailedTransactionRepository.java
│       │   │   │   └── TransactionAuditRepository.java
│       │   │   └── service/
│       │       ├── AccountService.java
│       │       ├── CustomerService.java
│       │       └── TransactionService.java
│       │   └── resources/
│       │       └── application.properties
│       └── test/
│           ├── java/com/newgen/banking/
│           │   ├── BankingSystemApplicationTests.java
│           │   ├── controller/
│           │   │   ├── AccountControllerIntegrationTest.java
│           │   │   ├── AccountControllerTest.java
│           │   │   ├── CustomerControllerIntegrationTest.java
│           │   │   ├── CustomerControllerTest.java
│           │   │   ├── TransactionControllerIntegrationTest.java
│           │   │   └── TransactionControllerTest.java
│           │   ├── repository/
│           │   │   └── AccountRepositoryIntegrationTest.java
│           │   └── service/
│           │       ├── AccountServiceTest.java
│           │       ├── CustomerServiceTest.java
│           │       └── TransactionServiceTest.java
│           └── resources/
│               └── application-test.properties
├── database/
│   └── ddl/
│       ├── 001_create_customers.sql
│       ├── 002_create_accounts.sql
│       └── 003_create_transactions.sql
└── docs/
    ├── architecture.md
    ├── database-design.md
    ├── project-structure.md
    └── requirements.md
```

## Directory Responsibilities

- `backend/` — Spring Boot 4.2.0 + Maven backend service (`com.newgen.banking`).
  - `controller/` — REST endpoints (`HealthController`, `CustomerController`, `AccountController`, `TransactionController`).
  - `dto/` — Request/response/pagination/error records separating API contracts from JPA entities.
  - `entity/` — JPA entities and enums mapped to PostgreSQL tables (`Customer`, `Account`, `BankTransaction`, `TransactionAudit`, `FailedTransaction`).
  - `exception/` — Domain exceptions and centralized `@RestControllerAdvice` (`GlobalExceptionHandler`).
  - `repository/` — Spring Data JPA repositories.
  - `service/` — Transactional business logic (`CustomerService`, `AccountService`, `TransactionService`).
- `database/ddl/` — Versioned PostgreSQL DDL scripts (`001_create_customers.sql`, `002_create_accounts.sql`, `003_create_transactions.sql`).
- `docs/` — Project requirements, architecture, database design, and structure documentation.

## Planned Directories (Not Yet Created)

The following modules are planned in [README.md](../README.md) and [architecture.md](architecture.md) for later stages:
- `frontend/` — React.js + Tailwind CSS UI
- `performance/` — Concurrency and throughput benchmarking artifacts
