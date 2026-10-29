import assert from 'node:assert/strict'
import test from 'node:test'
import { readBe } from './helpers.mjs'

// Source review guards only; they do not execute Java, send mail, or implement a proposed endpoint.
test('zero consent targets return before the required setting and recording skips empty selections', () => {
  const service = readBe('main-service/src/main/java/io/edupilot/policy/PolicyService.java')
  const signup = service.slice(service.indexOf('public SignupSelection validateSignup'))
  assert.match(signup, /if \(required\.isEmpty\(\)\)\s*\{\s*return new SignupSelection\(null, null, null\);/)
  assert.ok(signup.indexOf('required.isEmpty()') < signup.indexOf('!signupConsentRequired'))
  assert.match(service, /if \(selection\.agreedAt\(\) == null\)\s*\{\s*return;/)
  const tests = readBe('main-service/src/test/java/io/edupilot/policy/PolicyServiceTest.java')
  assert.ok(tests.includes('mandatorySettingDoesNotBlockSignupWhenNoDocumentRequiresConsent'))
  assert.ok(tests.includes('optionalSignupIgnoresDocumentsThatDoNotRequireConsent'))
})

test('current common signup examples include DOB and legacy email documentation has the V58 boundary', () => {
  const spec = readBe('docs/api-spec.md')
  const local = spec.slice(spec.indexOf('### POST `/api/auth/signup`'), spec.indexOf('### 이메일 소유 확인 API'))
  assert.match(local, /"dateOfBirth": "2000-01-01"/)
  assert.match(spec, /"idToken": "google-id-token",\s*"role": "LEARNER",\s*"dateOfBirth": "2000-01-01"/)
  assert.ok(readBe('docs/email-verification.md').includes('V58'))
  assert.ok(readBe('docs/email-verification.md').includes('UNKNOWN/PENDING'))
  assert.ok(spec.includes('동의 대상 문서가 하나도 없으면'))
})

test('mail link uses the FE route and DEV workflow applies the prod override base URL defaults', () => {
  assert.ok(readBe('main-service/src/main/java/io/edupilot/auth/EmailVerificationService.java').includes('"/verify-email#token=" + raw'))
  const template = readBe('main-service/src/main/java/io/edupilot/mail/EmailTemplates.java')
  assert.ok(template.includes('properties.baseUrl().replaceAll("/+$", "")'))
  assert.ok(template.includes('link.replaceAll("^/+", "")'))
  assert.ok(readBe('main-service/src/main/resources/application.yml').includes('base-url: ${EDUPILOT_MAIL_BASE_URL:https://dev.uteum.com}'))
  assert.ok(readBe('docker-compose.yml').includes('EDUPILOT_MAIL_BASE_URL: ${EDUPILOT_MAIL_BASE_URL:-https://dev.uteum.com}'))
  assert.ok(readBe('docker-compose.prod.yml').includes('EDUPILOT_MAIL_BASE_URL: ${EDUPILOT_MAIL_BASE_URL:-https://www.uteum.com}'))
  assert.ok(readBe('.github/workflows/deploy-dev.yml').includes('-f docker-compose.yml -f docker-compose.prod.yml'))
})

test('reviewed auth and health sources do not advertise the proposed capability contract', () => {
  for (const file of ['AuthController.java', 'EmailVerificationController.java', 'dto/LoginResponse.java']) {
    const source = readBe(`main-service/src/main/java/io/edupilot/auth/${file}`)
    assert.doesNotMatch(source, /backendRevision|emailVerificationSupported|signupDateOfBirthRequired|"\/capabilities"/)
  }
  const health = readBe('main-service/src/main/java/io/edupilot/global/config/HealthController.java')
  assert.ok(health.includes('ApiResponse.success(Map.of("status", "UP"))'))
  assert.doesNotMatch(health, /backendRevision|emailVerificationSupported/)
  assert.ok(readBe('docs/qa/fe-auth-contract/FE-FOLLOWUP.md').includes('미구현·미합의'))
})
