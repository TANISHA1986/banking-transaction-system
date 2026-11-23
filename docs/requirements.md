# Banking Transaction Processing System

## 1. Introduction

The Banking Transaction Processing System is a Java-based application developed to manage and process banking transactions.

The main purpose of this project is to handle operations such as deposits, withdrawals and fund transfers while keeping account balances accurate. The system will also support multiple transactions at the same time and ensure that account data remains consistent.

## 2. Technologies Used

The following technologies will be used for development:

- **Java:** Core programming language
- **Spring Boot:** Backend development
- **Maven:** Project build and dependency management
- **PostgreSQL:** Database management
- **Spring Data JPA:** Database interaction
- **React.js:** Frontend development
- **Tailwind CSS:** Frontend styling
- **Git and GitHub:** Version control and project management
- **JUnit 5:** Application testing
- **SLF4J and Logback:** Application logging

## 3. Main Features

The application will include the following features:

1. Manage customer details and their accounts.
2. Create and maintain bank accounts.
3. Deposit money into an account.
4. Withdraw money from an account.
5. Transfer money between two accounts.
6. Check account balance and account status.
7. View transaction history.
8. Validate account details and transaction amounts before processing.
9. Handle cases such as insufficient balance and inactive accounts.
10. Maintain records of successful, failed and rejected transactions along with their reasons.

## 4. Concurrent Transaction Processing

The system should be able to process multiple banking transactions simultaneously without affecting the correctness of account balances.

For this, a thread pool will be used to manage transaction processing.

The main requirements are:

- Handle multiple transactions at the same time.
- Prevent incorrect balance updates when multiple requests access the same account.
- Ensure that an account balance never becomes negative.
- Use proper database transactions and locking mechanisms.
- Handle simultaneous withdrawal requests safely.
- Process at least 50,000 simulated transactions and record the results.

A concurrency test will also be performed in which two withdrawals of ₹7,000 and ₹5,000 are attempted simultaneously from an account having ₹10,000. Both withdrawals should not be successful.

## 5. Duplicate Transaction Handling

Every transaction will have a unique transaction ID.

If the same transaction request is received more than once, the system should identify it as a duplicate and prevent the amount from being processed again.

Duplicate transaction handling will be implemented at both the application and database levels.

## 6. Database Design

PostgreSQL will be used to store customer, account and transaction-related information.

The database will contain the following main tables:

- **CUSTOMER:** Stores customer information.
- **ACCOUNT:** Stores account details, balance and account status.
- **TRANSACTION:** Maintains transaction details and processing status.
- **TRANSACTION_AUDIT:** Stores transaction-related audit information.
- **FAILED_TRANSACTION:** Stores details of transactions that could not be processed successfully.

Primary keys, foreign keys, unique constraints, check constraints and indexes will be added wherever required to maintain data integrity and improve query performance.

## 7. Logging and Exception Handling

The application will maintain logs to track transaction processing and identify errors.

The logs will include details such as transaction ID, operation type, account reference, processing status, timestamp and error information.

Custom exceptions will be created for common situations, including:

- Insufficient balance
- Account not found
- Duplicate transaction
- Invalid transaction details

Sensitive information such as passwords and confidential account details will not be included in application logs.

## 8. SQL Reports and Performance Testing

The system will provide SQL reports for the following requirements:

1. Find the top 10 accounts based on transaction volume.
2. Display failed transactions grouped by failure reason.
3. Calculate daily transaction amounts based on transaction type.
4. Find accounts having more than a specified number of transactions.
5. Display the complete transaction history of a customer.

Important SQL queries will also be analysed using EXPLAIN or EXPLAIN ANALYZE to understand their execution plans and identify possible improvements.

For performance testing, at least 50,000 transactions will be processed using different thread counts:

- 1 worker thread
- 5 worker threads
- 10 worker threads
- 20 worker threads

The total processing time, number of successful and failed transactions, and transactions processed per second will be recorded and compared.

## 9. Testing

Testing will be performed throughout development to check whether the application works as expected.

The testing process will include:

- Unit testing of individual components.
- Integration testing with the PostgreSQL database.
- Testing of deposit, withdrawal and transfer operations.
- Testing of invalid and failed transactions.
- Concurrency testing to verify balance consistency.
- Duplicate transaction testing to ensure that the same transaction is not processed twice.
- Performance testing with a large number of simulated transactions.

## 10. Expected Outcome

The final application should be able to process banking transactions reliably, handle multiple requests safely and maintain accurate account balances.

It should also provide proper transaction records, error logs, SQL reports and test results. The project will be documented so that its setup, execution and important design decisions can be understood easily.