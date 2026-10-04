import assert from 'node:assert/strict'
import { existsSync } from 'node:fs'
import { resolve } from 'node:path'
import test from 'node:test'
import { beRoot, fixtures, readBe, recordFields } from './helpers.mjs'

const auth = 'main-service/src/main/java/io/edupilot/auth/'
const user = 'main-service/src/main/java/io/edupilot/user/'
const global = 'main-service/src/main/java/io/edupilot/global/'
const sorted = (values) => [...values].sort()
function matchesRecord(data, path, name) {
  assert.deepEqual(sorted(Object.keys(data)), sorted(recordFields(path, name)))
}

test('synthetic inputs cover the reviewed LOCAL and new Google DTO fields', () => {
  matchesRecord(fixtures.inputs.localSignup, `${auth}dto/SignupRequest.java`, 'SignupRequest')
  matchesRecord(fixtures.inputs.newGoogleSignup, `${auth}dto/GoogleLoginRequest.java`, 'GoogleLoginRequest')
  matchesRecord(fixtures.inputs.confirm, `${auth}dto/EmailVerificationConfirmRequest.java`, 'EmailVerificationConfirmRequest')
  assert.match(readBe(`${auth}dto/SignupRequest.java`), /@NotNull\([^)]*\)[\s\S]*java\.time\.LocalDate dateOfBirth/)
  assert.match(fixtures.inputs.localSignup.dateOfBirth, /^\d{4}-\d{2}-\d{2}$/)
  assert.match(fixtures.inputs.confirm.token, /^[A-Za-z0-9_-]{43}$/)
  assert.deepEqual(Object.keys(fixtures.inputs.existingGoogleLogin), ['idToken'])
})

test('success envelope and signup/me/login response shapes match BE records without DOB or cohort leaks', () => {
  const responses = fixtures.responses
  matchesRecord(responses.localSignup.body.data, `${auth}dto/SignupResponse.java`, 'SignupResponse')
  matchesRecord(responses.meLegacy.body.data, `${user}dto/UserResponse.java`, 'UserResponse')
  for (const name of ['loginPending', 'newGoogleSignup']) {
    matchesRecord(responses[name].body.data, `${auth}dto/LoginResponse.java`, 'LoginResponse')
    matchesRecord(responses[name].body.data.user, `${user}dto/UserResponse.java`, 'UserResponse')
    matchesRecord(responses[name].body.data.session, `${auth}dto/AuthSessionResponse.java`, 'AuthSessionResponse')
  }
  for (const fixture of Object.values(responses).filter((item) => item.body.success)) {
    matchesRecord(fixture.body, `${global}response/ApiResponse.java`, 'ApiResponse')
    assert.equal(typeof fixture.body.message, 'string')
    for (const secret of ['dateOfBirth', 'accessCohort', 'tokenHash', 'token', 'idToken', 'password']) {
      assert.equal(JSON.stringify(fixture.body).includes(`"${secret}":`), false)
    }
  }
})

test('status fixtures preserve verification evidence independently from legacy access exceptions', () => {
  for (const name of ['statusPending', 'statusNewUnknown', 'statusLegacyUnknown', 'statusLegacyPending', 'confirmVerified']) {
    const data = fixtures.responses[name].body.data
    matchesRecord(data, `${auth}dto/EmailVerificationResponse.java`, 'EmailVerificationResponse')
    assert.equal(data.emailVerification === 'VERIFIED', data.emailVerifiedAt !== null)
  }
  assert.equal(fixtures.responses.statusPending.body.data.emailVerificationRequired, true)
  assert.equal(fixtures.responses.statusLegacyPending.body.data.emailVerificationRequired, false)
  assert.equal(fixtures.responses.statusLegacyUnknown.body.data.emailVerifiedAt, null)
  assert.equal(fixtures.responses.statusNewUnknown.body.data.emailVerificationRequired, true)
  assert.equal(fixtures.responses.confirmVerified.body.data.emailVerificationRequired, false)
  assert.equal('accessToken' in fixtures.responses.confirmVerified.body.data, false)
  assert.equal('Set-Cookie' in fixtures.responses.confirmVerified.headers, false)
  assert.equal(fixtures.responses.requestAccepted.body.data, null)
})

test('all failure fixtures use BE ErrorCode HTTP status and common error record fields', () => {
  const errorSource = readBe(`${global}error/ErrorCode.java`)
  const statuses = {
    BAD_REQUEST: 400, UNAUTHORIZED: 401, FORBIDDEN: 403, CONFLICT: 409,
    METHOD_NOT_ALLOWED: 405, TOO_MANY_REQUESTS: 429,
  }
  for (const fixture of Object.values(fixtures.responses).filter((item) => !item.body.success)) {
    const { code } = fixture.body.error
    const match = errorSource.match(new RegExp(`${code}\\(\\s*"${code}",\\s*HttpStatus\\.(\\w+),\\s*"([^"]+)"`))
    assert.ok(match, `ErrorCode ${code} not found`)
    assert.equal(fixture.status, statuses[match[1]], code)
    assert.equal(fixture.body.error.message, match[2], code)
    matchesRecord(fixture.body, `${global}response/ErrorResponse.java`, 'ErrorResponse')
    matchesRecord(fixture.body.error, `${global}response/ApiError.java`, 'ApiError')
    assert.ok(Array.isArray(fixture.body.error.details))
  }
})

test('email route methods, authentication ordering and no-store success match reviewed controller source', () => {
  const controller = readBe(`${auth}EmailVerificationController.java`)
  assert.match(controller, /@RequestMapping\("\/api\/auth\/email-verification"\)/)
  for (const annotation of ['@PostMapping("/request")', '@GetMapping("/status")', '@PostMapping("/confirm")']) {
    assert.ok(controller.includes(annotation))
  }
  assert.equal(controller.includes('@GetMapping("/confirm")'), false)
  assert.equal((controller.match(/cacheControl\(CacheControl\.noStore\(\)\)/g) ?? []).length, 3)
  const security = readBe(`${auth}SecurityConfig.java`)
  const protectedPaths = security.indexOf('"/api/auth/email-verification/request", "/api/auth/email-verification/status"')
  assert.ok(protectedPaths > 0 && protectedPaths < security.indexOf('"/api/auth/**"'))
  assert.equal(fixtures.responses.confirmGet.headers.Allow, 'POST')
  for (const name of ['statusPending', 'requestAccepted', 'confirmVerified', 'confirmGet']) {
    assert.equal(fixtures.responses[name].headers['Cache-Control'], 'no-store')
  }
})

test('reviewed source retains strict expiry, single use, resend invalidation and 30 minute lifetime', () => {
  const token = readBe(`${auth}EmailVerificationToken.java`)
  assert.match(token, /usedAt == null && expiresAt\.isAfter\(now\) && emailHash\.equals\(currentEmailHash\)/)
  const service = readBe(`${auth}EmailVerificationService.java`)
  assert.match(service, /Duration\.ofMinutes\(30\)/)
  assert.match(service, /tokens\.invalidateUnused\(user\.getId\(\), now\)/)
  assert.ok(service.includes('token.use(now);'))
  assert.ok(service.includes('"/verify-email?token=" + raw'))
  const limits = readBe(`${auth}PasswordResetRateLimiter.java`)
  assert.match(limits, /requestsByEmail\.increment\(email\) <= 3/)
  assert.match(limits, /requestsByIp\.increment\(ip\) <= 10/)
  assert.match(limits, /confirmsByIp\.increment\(ip\) <= 10/)
  assert.equal('Retry-After' in fixtures.responses.requestRateLimited.headers, false)
})

test('V58 cohort defaults and gate source preserve legacy exception without approval backfill', () => {
  const migration = readBe('main-service/src/main/resources/db/migration/V58__legacy_account_access_cohort.sql')
  assert.ok(migration.includes("ADD COLUMN access_cohort VARCHAR(24) NOT NULL DEFAULT 'LEGACY_EXEMPT'"))
  assert.ok(migration.includes("ALTER COLUMN access_cohort SET DEFAULT 'NEW_SIGNUP'"))
  const model = readBe(`${user}User.java`)
  assert.match(model, /accessCohort\s*=\s*AccountAccessCohort\.NEW_SIGNUP/)
  assert.ok(model.includes('return !isEmailVerified() && !isLegacyAccessExempt();'))
  const gate = readBe(`${auth}EmailVerificationGate.java`)
  assert.ok(gate.indexOf('if (!user.isActive())') < gate.indexOf('user.isEmailVerificationRequired()'))
  assert.ok(readBe(`${auth}GoogleAccountService.java`).indexOf('findByGoogleSub') < readBe(`${auth}GoogleAccountService.java`).indexOf('request.dateOfBirth()==null'))
})

test('handoff local links resolve and examples remain synthetic', () => {
  const root = resolve(beRoot, 'docs/qa/fe-auth-contract')
  for (const filename of ['README.md', 'EVIDENCE.md']) {
    const markdown = readBe(`docs/qa/fe-auth-contract/${filename}`)
    for (const match of markdown.matchAll(/\]\(([^)]+)\)/g)) {
      const target = match[1].split('#')[0]
      if (!target || /^https?:/.test(target)) continue
      assert.ok(existsSync(resolve(root, target)), `${filename}: missing ${target}`)
    }
  }
  assert.equal(fixtures.source.syntheticOnly, true)
  for (const input of [fixtures.inputs.localSignup]) assert.ok(input.email.endsWith('@example.invalid'))
})
