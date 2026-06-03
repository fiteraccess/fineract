# Fix GitHub Actions Failures Locally

## Quick Start

```powershell
# Run quick checks (no tests, ~2-3 minutes)
.\test-workflows-locally.ps1 -QuickCheck

# Run all checks except tests (~5-7 minutes)
.\test-workflows-locally.ps1

# Run full validation including tests (~15-20 minutes)
.\test-workflows-locally.ps1 -FullBuild
```

## Common Failure Fixes

### 1. ❌ Unsigned Commits (`verify-commits.yml` fails)

**Problem:** Your commits are not GPG signed.

**Check locally:**
```powershell
git log --pretty=format:"%H %G? %s" -5
# N = Not signed
# G = Good signature
# E = Expired signature
```

**Fix - Sign your last commit:**
```powershell
git commit --amend --no-edit -S
git push --force-with-lease
```

**Fix - Sign multiple commits:**
```powershell
# Sign last 3 commits
git rebase --exec 'git commit --amend --no-edit -n -S' -i HEAD~3
git push --force-with-lease
```

**Setup GPG signing permanently:**
```powershell
# 1. Generate GPG key (if you don't have one)
gpg --full-generate-key
# Choose: RSA and RSA, 4096 bits, enter your GitHub email

# 2. Get your GPG key ID
gpg --list-secret-keys --keyid-format=long
# Copy the ID after "sec rsa4096/"

# 3. Configure Git to use it
git config --global user.signingkey YOUR_KEY_ID
git config --global commit.gpgsign true

# 4. Export and add to GitHub
gpg --armor --export YOUR_KEY_ID
# Copy output and add to GitHub: Settings → SSH and GPG keys → New GPG key
```

---

### 2. ❌ Code Formatting (`spotlessCheck` fails)

**Problem:** Code is not formatted according to project standards.

**Fix:**
```powershell
.\gradlew spotlessApply
git add .
git commit -m "style: apply spotless formatting"
```

---

### 3. ❌ Checkstyle Violations

**Problem:** Code doesn't follow checkstyle rules.

**Check:**
```powershell
.\gradlew checkstyleMain checkstyleTest
```

**View reports:**
```powershell
# Open the HTML report
start build/reports/checkstyle/main.html
```

**Common fixes:**
- Remove unused imports
- Fix indentation
- Add missing JavaDoc
- Fix line length (max 150 characters)

---

### 4. ❌ Compilation Errors

**Problem:** Code doesn't compile.

**Check:**
```powershell
.\gradlew clean compileJava compileTestJava
```

**Common issues:**
- Missing imports
- Type mismatches
- API changes in dependencies

---

### 5. ❌ Test Failures

**Problem:** Unit or integration tests failing.

**Run specific test:**
```powershell
# Run single test class
.\gradlew :fineract-provider:test --tests "YourTestClass"

# Run all tests in a module
.\gradlew :fineract-core:test

# Run with PostgreSQL (like CI)
.\gradlew :fineract-provider:test -PdbType=postgresql
```

**View test reports:**
```powershell
start fineract-provider/build/reports/tests/test/index.html
```

---

### 6. ❌ Build Failures (PostgreSQL)

**Problem:** Integration tests need PostgreSQL running.

**Setup PostgreSQL locally:**
```powershell
# Using Docker
docker run -d --name fineract-postgres `
  -e POSTGRES_USER=root `
  -e POSTGRES_PASSWORD=postgres `
  -p 5432:5432 `
  postgres:18.2

# Create databases
.\gradlew createPGDB -PdbName=fineract_tenants
.\gradlew createPGDB -PdbName=fineract_default

# Run tests
.\gradlew test -PdbType=postgresql
```

---

## Workflow-Specific Testing

### Test Build Workflow (build-postgresql.yml)
```powershell
# Requires PostgreSQL running
.\gradlew :fineract-provider:test -PdbType=postgresql `
  -x checkstyleMain -x spotlessCheck -x javadoc
```

### Test Docker Build (build-fiter.yml)
```powershell
.\gradlew :fineract-provider:clean :fineract-provider:build `
  :fineract-provider:jibDockerBuild `
  -x test -x cucumber -x spotlessCheck -x rat -x checkstyleMain
```

### Test Commit Signing (verify-commits.yml)
```powershell
# Check if commits are signed
git log --show-signature -5
```

---

## Pre-Push Checklist

Before pushing to GitHub, run:

```powershell
# 1. Format code
.\gradlew spotlessApply

# 2. Run local workflow tests
.\test-workflows-locally.ps1

# 3. Fix any failures

# 4. Commit changes
git add .
git commit -S -m "fix: resolve GitHub Actions failures"

# 5. Push
git push
```

---

## Using `act` for Full Local Workflow Testing

For advanced users, you can run the actual GitHub Actions workflows locally using `act`:

### Install act:
```powershell
# Using winget
winget install nektos.act

# Or using Chocolatey
choco install act-cli
```

### Run workflows:
```powershell
# List available workflows
act -l

# Run a specific workflow
act pull_request -W .github/workflows/verify-commits.yml

# Run with specific event
act pull_request -W .github/workflows/build-postgresql.yml
```

**Note:** `act` requires Docker Desktop to be running.

---

## Troubleshooting

### GPG signing issues on Windows
```powershell
# Set GPG program for Git
git config --global gpg.program "C:\Program Files (x86)\GnuPG\bin\gpg.exe"

# Or if using gpg4win
git config --global gpg.program "C:\Program Files (x86)\Gnu\GnuPG\gpg.exe"
```

### Gradle daemon issues
```powershell
.\gradlew --stop
Remove-Item -Recurse -Force ~/.gradle/daemon
```

### Cache issues
```powershell
.\gradlew clean cleanBuildCache
Remove-Item -Recurse -Force ~/.gradle/caches
```

---

## CI/CD Optimization Tips

1. **Skip unnecessary checks in development:**
   ```powershell
   .\gradlew build -x test -x checkstyle -x spotbugs -x javadoc
   ```

2. **Use parallel execution:**
   ```powershell
   .\gradlew test --parallel --max-workers=8
   ```

3. **Use build cache:**
   ```powershell
   # Already enabled in gradle.properties
   org.gradle.caching=true
   ```

4. **Run only affected tests:**
   ```powershell
   # Test specific module
   .\gradlew :fineract-loan:test
   ```

