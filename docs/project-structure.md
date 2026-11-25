# Project Structure

This document describes the current repository layout of `banking-transaction-system` as of Day 1.

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
│       │   │   │   ├── CustomerController.java
│       │   │   │   └── HealthController.java
│       │   │   ├── dto/
│       │   │   │   ├── ApiErrorResponse.java
│       │   │   │   ├── CustomerRequest.java
│       │   │   │   ├── CustomerResponse.java
│       │   │   │   └── PagedResponse.java
│       │   │   ├── entity/
│       │   │   │   └── Customer.java
│       │   │   ├── exception/
│       │   │   │   ├── DuplicateResourceException.java
│       │   │   │   ├── GlobalExceptionHandler.java
│       │   │   │   └── ResourceNotFoundException.java
│       │   │   ├── repository/
│       │   │   │   └── CustomerRepository.java
│       │   │   └── service/
│       │   │       └── CustomerService.java
│       │   └── resources/
│       │       └── application.properties
│       └── test/
│           ├── java/com/newgen/banking/
│           │   ├── BankingSystemApplicationTests.java
│           │   ├── controller/
│           │   │   ├── CustomerControllerIntegrationTest.java
│           │   │   └── CustomerControllerTest.java
│           │   └── service/
│           │       └── CustomerServiceTest.java
│           └── resources/
│               └── application-test.properties
├── database/
│   └── ddl/
│       └── 001_create_customers.sql
└── docs/
    ├── architecture.md
    ├── database-design.md
    ├── project-structure.md
    └── requirements.md
```

## Directory Responsibilities

- `backend/` — Spring Boot 4.2.0 + Maven backend service (`com.newgen.banking`).
  - `controller/` — REST endpoints (`HealthController`, `CustomerController`).
  - `dto/` — Request/response/pagination/error records separating API contracts from JPA entities.
  - `entity/` — JPA entities mapped to PostgreSQL tables (`Customer`).
  - `exception/` — Domain exceptions and centralized `@RestControllerAdvice` (`GlobalExceptionHandler`).
  - `repository/` — Spring Data JPA repositories (`CustomerRepository`).
  - `service/` — Transactional business logic (`CustomerService`).
- `database/ddl/` — Versioned PostgreSQL DDL scripts (`001_create_customers.sql`).
- `docs/` — Project requirements, architecture, database design, and structure documentation.

## Planned Directories (Not Yet Created)

The following modules are planned in [README.md](../README.md) and [architecture.md](architecture.md) for later stages:
- `frontend/` — React.js + Tailwind CSS UI
- `performance/` — Concurrency and throughput benchmarking artifacts
