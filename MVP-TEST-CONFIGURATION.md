# MVP Test Configuration - Savings and Recurring Deposits Only

## Overview

For the MVP phase, integration tests have been configured to **only run tests related to Savings Accounts and Recurring Deposits**. This significantly reduces test execution time by excluding tests for features not included in the MVP scope.

## What's Excluded

The following product types and features are **excluded** from test execution:

### Product Types
- ❌ **Loans** (all 135+ loan-related tests)
- ❌ **Fixed Deposits**
- ❌ **Shares**
- ❌ **Working Capital Loans**
- ❌ **Progressive Loans**

### Loan-Related Features
- ❌ Guarantors
- ❌ Investors
- ❌ Product Mix
- ❌ Delinquency tracking
- ❌ Chargebacks
- ❌ Charge-offs
- ❌ Rescheduling
- ❌ Disbursements
- ❌ Repayments

## What's Included

The following tests **WILL RUN** (129 tests):

### ✅ Core MVP Features
- **Savings Accounts** (23 tests)
  - `SavingsAccountsTest`
  - `SavingsInterestPostingTest`
  - `SavingsAccrualTest`
  - `ClientSavingsIntegrationTest`
  - `GroupSavingsIntegrationTest`
  - And 18 more savings-related tests

- **Recurring Deposits** (1 test)
  - `RecurringDepositTest`

### ✅ Foundational Features (Kept for MVP functionality)
- **Client Management** (~15 tests)
  - Client creation, updates, search
  - Client transactions
  - Client auditing

- **Infrastructure** (~90 tests)
  - Authentication & Authorization
  - API & Batch operations
  - Accounting & Financial activities
  - Reports & Notifications
  - System configuration
  - Data tables & hooks
  - Staff & Office management

## Test Statistics

| Category | Count | Status |
|----------|-------|--------|
| **Tests Excluded** | 147 | ❌ Disabled |
| **Tests Included** | 129 | ✅ Running |
| **Total Tests** | 276 | - |
| **Reduction** | ~53% | ⚡ Faster |

## Technical Implementation

The test filtering is configured in `integration-tests/build.gradle`:

```groovy
tasks.named('test').configure {
    filter {
        excludeTestsMatching '*Loan*'
        excludeTestsMatching '*FixedDeposit*'
        excludeTestsMatching '*Share*'
        // ... and 12 more exclusion patterns
    }
}
```

## Running Tests

### Run All MVP Tests (Default)
```bash
./gradlew :integration-tests:test
```

This will run **only the 129 enabled tests** (Savings, Recurring Deposits, Client, Infrastructure).

### Run Specific Test
```bash
./gradlew :integration-tests:test --tests "SavingsAccountsTest"
```

### Run with Cargo Disabled (Faster for development)
```bash
./gradlew :integration-tests:test -PcargoDisabled
```

### Check Which Tests Will Run
```bash
./gradlew :integration-tests:test --dry-run
```

## Reverting Changes

To **re-enable all tests** (after MVP), simply remove the `filter { }` block from `integration-tests/build.gradle` (lines 148-166).

## Benefits

1. **⚡ 53% Faster Test Execution** - Only 129 tests instead of 276
2. **🎯 Focused Testing** - Only test MVP features (Savings & Recurring Deposits)
3. **💰 Cost Savings** - Less CI/CD compute time
4. **🔄 Easy to Revert** - Simple configuration change, no code modifications

## Notes

- This configuration is **MVP-specific** and should be reviewed before production release
- Client and infrastructure tests are kept because they're required for savings functionality
- Tests like `LoanAccountDisbursementToSavingsWithAutoDownPaymentTest` are excluded (loan feature)
- No test code was modified - all changes are in Gradle configuration only

## Date Configured
June 3, 2026

## Configured By
GitHub Copilot based on MVP requirements
