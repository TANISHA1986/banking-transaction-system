# Banking Transaction Processing System

## About the Project

This project is a Java, Spring Boot, and PostgreSQL banking transaction processing system designed to support customer and account management, deposits, withdrawals, fund transfers, concurrent transaction processing, and audit/failure tracking.

## Current Implementation Status (Day 2 Foundation)

Currently implemented:
- Spring Boot Maven backend skeleton (`banking-system`)
- PostgreSQL database DDL for `customers` (`database/ddl/001_create_customers.sql`) and `accounts` + `account_number_seq` (`database/ddl/002_create_accounts.sql`)
- Health check endpoint (`GET /api/v1/health`)
- Customer registration and retrieval APIs (`POST /api/customers`, `GET /api/customers`, `GET /api/customers/paged`, `GET /api/customers/{id}`)
- Account management APIs (`POST /api/accounts`, `GET /api/accounts`, `GET /api/accounts/{id}`, `GET /api/accounts/number/{accountNumber}`, `GET /api/accounts/customer/{customerId}`)
- Request validation and centralized JSON error handling (`GlobalExceptionHandler`)
- Unit, WebMVC slice, and PostgreSQL integration tests for Customer and Account modules

Planned for upcoming stages (not yet implemented):
- Deposits, withdrawals, and fund transfers
- Concurrent transaction processing (`ExecutorService`) and duplicate transaction prevention
- Transaction audit and failed transaction tracking
- SQL reports and performance testing
- React.js + Tailwind CSS frontend

## Current Architecture

The backend follows a layered architecture:
- **Controller (`controller/`)**: Exposes REST endpoints, validates incoming DTOs (`@Valid`), and returns HTTP responses.
- **DTO (`dto/`)**: Defines request, response, pagination, and error payloads so JPA entities are not exposed directly.
- **Service (`service/`)**: Implements business rules (input normalization, duplicate email checks, pagination guardrails, not-found handling).
- **Repository (`repository/`)**: Communicates with PostgreSQL via Spring Data JPA.
- **Entity (`entity/`)**: Maps domain objects to PostgreSQL tables.
- **Exception (`exception/`)**: Translates domain and validation exceptions into consistent JSON error responses via `@RestControllerAdvice`.

## Technologies Used

- Java 17 (target compatibility)
- Spring Boot 4.2.0 (WebMVC, Data JPA, Validation, Actuator)
- Maven (via Maven Wrapper `mvnw` / `mvnw.cmd`)
- PostgreSQL
- JUnit 5 & Mockito (automated testing)
- SLF4J and Logback

## Database Setup

1. Ensure PostgreSQL is running locally on port `5432`.
2. Create the database if it does not already exist:
   ```sql
   CREATE DATABASE banking_db;
   ```
3. Run the DDL scripts against `banking_db` in order:
   ```bash
   psql -U postgres -d banking_db -f database/ddl/001_create_customers.sql
   psql -U postgres -d banking_db -f database/ddl/002_create_accounts.sql
   ```
   `spring.jpa.hibernate.ddl-auto` is set to `none`, so schema management is handled explicitly through SQL scripts in `database/ddl/`.

## Environment Variables

Database credentials are read from environment variables in `backend/src/main/resources/application.properties` and must not be hardcoded:

| Variable | Required | Default | Description |
|---|---|---|---|
| `DB_USERNAME` | No | `postgres` | PostgreSQL username (main app) |
| `DB_PASSWORD` | Yes (for `spring-boot:run`) | — | PostgreSQL password (main app) |
| `TEST_DB_USERNAME` | No | `postgres` | PostgreSQL username (isolated `test` profile) |
| `TEST_DB_PASSWORD` | Yes (for integration tests) | — | PostgreSQL password (isolated `test` profile) |

PowerShell example:
```powershell
$env:DB_USERNAME="postgres"
$env:DB_PASSWORD="your_postgres_password"
$env:TEST_DB_USERNAME="postgres"
$env:TEST_DB_PASSWORD="your_postgres_password"
```

### Isolated Test Database (`banking_test_db`)

Integration tests use `backend/src/test/resources/application-test.properties` (`@ActiveProfiles("test")`), which connects **only** to `banking_test_db` and never touches `banking_db`:

```sql
CREATE DATABASE banking_test_db;
```

## How to Run the Backend

From the `backend/` directory:

```powershell
cd backend
.\mvnw.cmd spring-boot:run
```

To compile and run unit/controller tests:
```powershell
cd backend
.\mvnw.cmd test
```

The backend runs on `http://localhost:8080`.

## Current Implemented APIs

### Health Check
- `GET /api/v1/health` — Returns application status (`200 OK`)
- `GET /actuator/health` — Spring Boot Actuator health endpoint

### Customer APIs
- `POST /api/customers` — Create a new customer (`201 Created`, `400 Bad Request` on validation error, `409 Conflict` on duplicate email)
  - Request body:
    ```json
    {
      "fullName": "Aarav Sharma",
      "email": "aarav.sharma@example.com",
      "phone": "9876543210"
    }
    ```
- `GET /api/customers` — List all customers (`200 OK`)
- `GET /api/customers/paged?page=0&size=20&sortBy=id&sortDir=asc` — Paginated and sorted customer list (`200 OK`, `400 Bad Request` for invalid paging/sort parameters)
- `GET /api/customers/{id}` — Get a customer by ID (`200 OK`, `404 Not Found` if missing, `400 Bad Request` if ID is non-positive or non-numeric)

### Account APIs
- `POST /api/accounts` — Open a new account for an existing customer (`201 Created`, `400 Bad Request` on invalid input/negative balance, `404 Not Found` if customer does not exist)
  - Request body (`initialBalance` is optional and defaults to `0.00`; `accountType` is `SAVINGS` or `CURRENT`):
    ```json
    {
      "customerId": 1,
      "accountType": "SAVINGS",
      "initialBalance": 1500.00
    }
    ```
  - Example response (`201 Created`):
    ```json
    {
      "id": 1,
      "accountNumber": "NG1000000001",
      "customerId": 1,
      "customerName": "Aarav Sharma",
      "accountType": "SAVINGS",
      "balance": 1500.00,
      "status": "ACTIVE",
      "createdAt": "2026-11-26T10:15:30.123456",
      "updatedAt": "2026-11-26T10:15:30.123456"
    }
    ```
- `GET /api/accounts?page=0&size=20&sortBy=id&sortDir=asc` — Paginated and sorted list of accounts (`200 OK`, `400 Bad Request` for invalid paging/sort parameters)
- `GET /api/accounts/{id}` — Get account by database ID (`200 OK`, `404 Not Found` if missing, `400 Bad Request` if non-positive or non-numeric)
- `GET /api/accounts/number/{accountNumber}` — Get account by unique account number (`200 OK`, `404 Not Found` if missing)
- `GET /api/accounts/customer/{customerId}` — List all accounts belonging to a customer (`200 OK`, `404 Not Found` if customer does not exist)

### Running Account Tests
- Unit & MockMvc slice tests (no DB required):
  ```powershell
  cd backend
  .\mvnw.cmd test "-Dtest=AccountServiceTest,AccountControllerTest"
  ```
- Full test suite including PostgreSQL integration tests (requires `banking_test_db` and `TEST_DB_PASSWORD`):
  ```powershell
  cd backend
  .\mvnw.cmd clean test
  ```

## Project Structure

See [docs/project-structure.md](docs/project-structure.md) for the current repository layout.
