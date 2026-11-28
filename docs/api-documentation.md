# REST API Specification & Integration Reference

## 1. Overview

The **Banking Transaction Processing System** exposes RESTful APIs for customer onboarding, account lifecycle management, core financial transactions (deposit, withdrawal, transfer), asynchronous batch transaction submission, and historical audit retrieval.

All endpoints adhere to standard HTTP verbs and status codes:
- `200 OK`: Successful retrieval or query
- `201 Created`: Resource successfully created or transaction committed
- `400 Bad Request`: Validation failure (malformed payload, invalid format, missing field)
- `404 Not Found`: Target entity (customer, account, or transaction) does not exist
- `409 Conflict`: Business duplicate conflict (e.g., duplicate customer email or duplicate transaction ID)
- `422 Unprocessable Content`: Business rule failure (e.g., insufficient account balance)
- `500 Internal Server Error`: Unexpected server or database exception

---

## 2. Standard Error Response Schema

All error responses from `@RestControllerAdvice` in [GlobalExceptionHandler.java](file:///c:/Users/XPS/Documents/Newgen-Project/banking-transaction-system/backend/src/main/java/com/newgen/banking/exception/GlobalExceptionHandler.java) follow a consistent structure:

```json
{
  "timestamp": "2026-11-28T09:30:00.123456",
  "status": 422,
  "error": "Unprocessable Content",
  "message": "Insufficient balance in account NG1000000001 (available: 50.00, requested: 500.00)",
  "path": "/api/transactions/withdraw"
}
```

For validation failures (`400 Bad Request`), the `errors` map contains field-specific validation messages:

```json
{
  "timestamp": "2026-11-28T09:30:00.123456",
  "status": 400,
  "error": "Bad Request",
  "message": "Validation failed",
  "errors": {
    "amount": "Transaction amount must be greater than zero",
    "accountId": "Account ID is required"
  },
  "path": "/api/transactions/deposit"
}
```

---

## 3. Customer Management APIs

### 3.1 Create Customer
- **Endpoint:** `POST /api/customers`
- **Description:** Registers a new customer with a unique email address.
- **Request Body (`CustomerRequest`):**
  ```json
  {
    "fullName": "Aarav Sharma",
    "email": "aarav.sharma@example.com",
    "phone": "9876543210"
  }
  ```
  - `fullName`: String (Required, 2–100 chars, letters, spaces, hyphens, periods)
  - `email`: String (Required, valid email format, max 150 chars, unique)
  - `phone`: String (Optional, 10–15 digits)
- **Responses:**
  - `201 Created`:
    ```json
    {
      "id": 1,
      "fullName": "Aarav Sharma",
      "email": "aarav.sharma@example.com",
      "phone": "9876543210",
      "createdAt": "2026-11-28T09:00:00"
    }
    ```
  - `400 Bad Request`: Validation failure on name, email format, or phone.
  - `409 Conflict`: Email already registered.

### 3.2 List All Customers
- **Endpoint:** `GET /api/customers`
- **Description:** Retrieves all customers.
- **Response `200 OK`:** Array of `CustomerResponse` objects.

### 3.3 List Customers (Paginated & Sorted)
- **Endpoint:** `GET /api/customers/paged?page=0&size=20&sortBy=id&sortDir=asc`
- **Query Parameters:**
  - `page`: Integer $\ge 0$ (default: `0`)
  - `size`: Integer $1 \dots 100$ (default: `20`)
  - `sortBy`: `id`, `fullName`, `email`, `createdAt` (default: `id`)
  - `sortDir`: `asc`, `desc` (default: `asc`)
- **Response `200 OK`:** `PagedResponse<CustomerResponse>` with page metadata.

### 3.4 Get Customer by ID
- **Endpoint:** `GET /api/customers/{id}`
- **Responses:**
  - `200 OK`: `CustomerResponse` JSON.
  - `404 Not Found`: Customer does not exist.

---

## 4. Account Management APIs

### 4.1 Open Account
- **Endpoint:** `POST /api/accounts`
- **Description:** Creates an account (`SAVINGS` or `CURRENT`) for an existing customer. Generates an account number from `account_number_seq` prefixed with `NG` (e.g. `NG1000000001`).
- **Request Body (`AccountRequest`):**
  ```json
  {
    "customerId": 1,
    "accountType": "SAVINGS",
    "initialBalance": 1500.00
  }
  ```
  - `customerId`: Long (Required, positive)
  - `accountType`: Enum (`SAVINGS`, `CURRENT`) (Required)
  - `initialBalance`: BigDecimal (Optional, defaults to `0.00`, non-negative, max 2 decimal places)
- **Responses:**
  - `201 Created`:
    ```json
    {
      "id": 1,
      "accountNumber": "NG1000000001",
      "customerId": 1,
      "accountType": "SAVINGS",
      "balance": 1500.00,
      "status": "ACTIVE",
      "createdAt": "2026-11-28T09:05:00",
      "updatedAt": "2026-11-28T09:05:00"
    }
    ```
  - `400 Bad Request`: Negative initial balance or invalid account type.
  - `404 Not Found`: Customer does not exist.

### 4.2 Get Account by ID
- **Endpoint:** `GET /api/accounts/{id}`
- **Responses:**
  - `200 OK`: `AccountResponse` JSON.
  - `404 Not Found`: Account does not exist.

### 4.3 Get Accounts by Customer ID
- **Endpoint:** `GET /api/accounts/customer/{customerId}`
- **Responses:**
  - `200 OK`: Array of `AccountResponse` objects.
  - `404 Not Found`: Customer does not exist.

---

## 5. Core Banking Transaction APIs

### 5.1 Deposit Funds
- **Endpoint:** `POST /api/transactions/deposit`
- **Description:** Deposits an amount into an active account. Protected by pessimistic row lock (`SELECT ... FOR UPDATE`).
- **Request Body (`DepositRequest`):**
  ```json
  {
    "transactionId": "TXN-DEP-20261128-001",
    "accountId": 1,
    "amount": 500.00,
    "description": "Salary deposit"
  }
  ```
  - `transactionId`: String (Optional, auto-generated UUID if omitted, max 64 chars)
  - `accountId`: Long (Required, positive)
  - `amount`: BigDecimal (Required, positive, max 2 decimals)
  - `description`: String (Optional, max 255 chars)
- **Responses:**
  - `201 Created`:
    ```json
    {
      "id": 1,
      "transactionId": "TXN-DEP-20261128-001",
      "transactionType": "DEPOSIT",
      "amount": 500.00,
      "sourceAccountId": null,
      "sourceAccountNumber": null,
      "destinationAccountId": 1,
      "destinationAccountNumber": "NG1000000001",
      "status": "SUCCESS",
      "description": "Salary deposit",
      "failureReason": null,
      "createdAt": "2026-11-28T09:10:00"
    }
    ```
  - `400 Bad Request`: Account not ACTIVE or non-positive amount.
  - `404 Not Found`: Account ID does not exist.
  - `409 Conflict`: `transactionId` has already been processed.

### 5.2 Withdraw Funds
- **Endpoint:** `POST /api/transactions/withdraw`
- **Description:** Withdraws an amount from an active account. Acquires pessimistic row lock before reading balance.
- **Request Body (`WithdrawalRequest`):**
  ```json
  {
    "transactionId": "TXN-WDR-20261128-002",
    "accountId": 1,
    "amount": 200.00,
    "description": "ATM cash withdrawal"
  }
  ```
- **Responses:**
  - `201 Created`: `TransactionResponse` JSON with `status: "SUCCESS"`.
  - `404 Not Found`: Account does not exist.
  - `409 Conflict`: Duplicate transaction ID.
  - `422 Unprocessable Content`: Insufficient account balance.

### 5.3 Transfer Funds
- **Endpoint:** `POST /api/transactions/transfer`
- **Description:** Transfers funds between two distinct accounts. Deadlock-free two-row locking in ascending ID order.
- **Request Body (`TransferRequest`):**
  ```json
  {
    "transactionId": "TXN-TRF-20261128-003",
    "sourceAccountId": 1,
    "destinationAccountId": 2,
    "amount": 350.00,
    "description": "Invoice reimbursement"
  }
  ```
  - `sourceAccountId`: Long (Required, positive)
  - `destinationAccountId`: Long (Required, positive, must be different from source)
  - `amount`: BigDecimal (Required, positive, max 2 decimals)
- **Responses:**
  - `201 Created`: `TransactionResponse` JSON with both source and destination account details.
  - `400 Bad Request`: Source equals destination or account is frozen/inactive.
  - `404 Not Found`: Either source or destination account does not exist.
  - `409 Conflict`: Duplicate transaction ID.
  - `422 Unprocessable Content`: Source account balance is insufficient.

---

## 6. Concurrent Batch Processing API

### 6.1 Submit Transaction Batch
- **Endpoint:** `POST /api/transactions/batch`
- **Description:** Concurrently executes up to 1,000 transactions across the bounded thread pool. Returns per-item execution results and wall-clock throughput (TPS) metrics.
- **Request Body (`BatchTransactionRequest`):**
  ```json
  {
    "batchId": "BATCH-PAYROLL-001",
    "transactions": [
      {
        "transactionId": "TXN-B01-001",
        "type": "DEPOSIT",
        "sourceAccountId": null,
        "destinationAccountId": 1,
        "amount": 2500.00,
        "description": "Monthly payroll"
      },
      {
        "transactionId": "TXN-B01-002",
        "type": "TRANSFER",
        "sourceAccountId": 1,
        "destinationAccountId": 2,
        "amount": 500.00,
        "description": "Auto savings"
      }
    ]
  }
  ```
  - `batchId`: String (Optional, auto-generated if blank)
  - `transactions`: List of 1 to 1,000 `BatchTransactionItem`s.
- **Response `200 OK` (`BatchTransactionResult`):**
  ```json
  {
    "batchId": "BATCH-PAYROLL-001",
    "startedAt": "2026-11-28T09:15:00.100",
    "completedAt": "2026-11-28T09:15:00.250",
    "durationMs": 150,
    "totalSubmitted": 2,
    "accepted": 2,
    "rejected": 0,
    "successful": 2,
    "failed": 0,
    "tps": 13.33,
    "workerCount": 5,
    "queueCapacity": 500,
    "results": [
      {
        "transactionId": "TXN-B01-001",
        "type": "DEPOSIT",
        "amount": 2500.00,
        "status": "SUCCESS",
        "failureReason": null
      },
      {
        "transactionId": "TXN-B01-002",
        "type": "TRANSFER",
        "amount": 500.00,
        "status": "SUCCESS",
        "failureReason": null
      }
    ]
  }
  ```

---

## 7. Transaction History & Audit APIs

### 7.1 Get Transaction by Transaction ID
- **Endpoint:** `GET /api/transactions/{transactionId}`
- **Responses:**
  - `200 OK`: `TransactionResponse` JSON.
  - `404 Not Found`: Transaction not found.

### 7.2 Get Account Transaction History (Paginated)
- **Endpoint:** `GET /api/transactions/account/{accountId}?page=0&size=20&sortBy=createdAt&sortDir=desc`
- **Query Parameters:**
  - `page`: Page index $\ge 0$ (default: `0`)
  - `size`: Items per page $1 \dots 100$ (default: `20`)
  - `sortBy`: `id`, `amount`, `status`, `transactionType`, `createdAt` (default: `createdAt`)
  - `sortDir`: `asc`, `desc` (default: `desc`)
- **Responses:**
  - `200 OK`: `PagedResponse<TransactionResponse>` with pagination metadata.
  - `404 Not Found`: Account does not exist.

---

## 8. Health Check APIs

- `GET /api/v1/health` $\to$ `200 OK` (`{"status":"UP","timestamp":"..."}`)
- `GET /actuator/health` $\to$ `200 OK` (Spring Boot Actuator health)
