import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { fixtures, loadFeConsumer, readBe, recordFields } from './helpers.mjs'

const review = JSON.parse(readFileSync(new URL('./pr229-review.json', import.meta.url), 'utf8'))
const feRoot = process.env.FE_AUTH_CURRENT_SOURCE_ROOT
const sourceTest = (name, fn) => test(name, { skip: !feRoot && 'Set FE_AUTH_CURRENT_SOURCE_ROOT to the read-only FE PR229 merge checkout' }, fn)
const apiTest = (name, fn) => sourceTest(name, () => fn(loadFeConsumer(feRoot, { readiness: review.syntheticReadiness })))
const plain = (value) => JSON.parse(JSON.stringify(value))
// Supply the authenticated wrapper's Bearer only; React/session/race behavior is outside this harness.
const selfRequest = (fe) => (path, options) => fe.apiRequest(path, { ...options, accessToken: 'synthetic-access-not-a-jwt' })
const errorIs = (code, status) => (error) => error.code === code && (status === undefined || error.status === status)

sourceTest('PR229 assertions read its exact merge head with no tracked FE changes', () => {
  const gitRead = (args) => execFileSync('git', ['-c', `safe.directory=${feRoot.replaceAll('\\', '/')}`, '-C', feRoot, ...args], { encoding: 'utf8' }).trim()
  assert.equal(gitRead(['rev-parse', 'HEAD']), review.feHead)
  assert.equal(gitRead(['status', '--porcelain', '--untracked-files=no']), '')
  assert.equal(review.historicalFeHead, fixtures.source.feDevelopHead)
  assert.equal(review.deploymentOrActivationApproved, false)
  assert.equal(review.syntheticOnly, true)
})

sourceTest('readiness uses only the exact FE build value; missing, empty and different values stay OFF', () => {
  for (const readiness of [undefined, '', 'true', 'be-auth-ee69e425-v1 ', 'signup-dob-email-v1']) {
    assert.equal(loadFeConsumer(feRoot, { readiness }).isLaunchAuthReady(), false)
  }
  const fe = loadFeConsumer(feRoot, { readiness: review.syntheticReadiness })
  assert.equal(fe.LAUNCH_AUTH_CONTRACT, review.syntheticReadiness)
  assert.equal(fe.isLaunchAuthReady(), true)
})

apiTest('resolved gap 1 ON: LOCAL sends DOB/consents and preserves the account-created response', async (fe) => {
  fe.respond('localSignup')
  const result = await fe.repository.signup(fixtures.inputs.localSignup)
  assert.deepEqual(plain(fe.requests[0].body), fixtures.inputs.localSignup)
  assert.deepEqual(plain(result), fixtures.responses.localSignup.body.data)
  assert.equal(result.emailVerificationRequired, true)
  assert.equal('emailVerifiedAt' in result, false)
  assert.equal('accessToken' in result, false)
  assert.equal(fe.requests.length, 1)
})

apiTest('resolved gap 1 ON: remote DOB field validation becomes a form error', async (fe) => {
  fe.respond('localMissingDob')
  const values = { ...fixtures.inputs.localSignup }
  delete values.dateOfBirth
  await assert.rejects(fe.repository.signup(values), (error) => {
    assert.ok(error instanceof fe.AuthValidationError)
    assert.equal(error.formErrors.dateOfBirth, fixtures.responses.localMissingDob.body.error.details[0].reason)
    return true
  })
  assert.deepEqual(plain(fe.requests[0].body.consents), values.consents)
  assert.equal(fe.requests.length, 1)
})

apiTest('resolved gap 2 ON: new Google role signup sends DOB/consents and receives PENDING', async (fe) => {
  fe.respond('newGoogleSignup')
  const result = await fe.repository.loginWithGoogle(fixtures.inputs.newGoogleSignup)
  assert.deepEqual(plain(fe.requests[0].body), fixtures.inputs.newGoogleSignup)
  assert.equal(result.user.emailVerification, 'PENDING')
  assert.equal(result.user.emailVerificationRequired, true)
  assert.equal(result.user.emailVerifiedAt, null)
  assert.equal(fe.requests.length, 1)
})

apiTest('existing Google token-only login stays free of signup DOB/consents; SIGNUP_REQUIRED is not retried', async (fe) => {
  fe.respond('googleSignupRequired')
  await assert.rejects(fe.repository.loginWithGoogle(fixtures.inputs.existingGoogleLogin), errorIs('SIGNUP_REQUIRED', 409))
  assert.deepEqual(plain(fe.requests[0].body), fixtures.inputs.existingGoogleLogin)
  assert.equal(fe.requests.length, 1)
  fe.respond('googleEmailConflict')
  await assert.rejects(fe.repository.loginWithGoogle(fixtures.inputs.existingGoogleLogin), errorIs('EMAIL_ALREADY_EXISTS', 409))
  assert.equal(fe.requests.length, 2)
})

sourceTest('resolved gaps 3/4: login, Google and me preserve optional email evidence even while readiness is OFF', async () => {
  const fe = loadFeConsumer(feRoot)
  fe.respond('loginPending')
  const local = await fe.repository.login(fixtures.inputs.localSignup)
  fe.respond('newGoogleSignup')
  const google = await fe.repository.loginWithGoogle(fixtures.inputs.existingGoogleLogin)
  fe.respond('meLegacy')
  const legacy = await fe.repository.getMe('synthetic-access-not-a-jwt')
  for (const [result, expected] of [[local.user, fixtures.responses.loginPending.body.data.user], [google.user, fixtures.responses.newGoogleSignup.body.data.user], [legacy, fixtures.responses.meLegacy.body.data]]) {
    for (const field of ['emailVerification', 'emailVerificationRequired', 'emailVerifiedAt']) assert.equal(result[field], expected[field])
  }
  assert.equal(fe.requiresEmailVerification(local.user), true)
  assert.equal(fe.requiresEmailVerification(legacy), false)
  assert.equal(legacy.emailVerification, 'UNKNOWN')
  assert.equal(legacy.emailVerifiedAt, null)
})

apiTest('older login/Google/me responses retain absence without fabricating VERIFIED or legacy evidence', async (fe) => {
  const fields = ['emailVerification', 'emailVerificationRequired', 'emailVerifiedAt']
  const omit = (user) => Object.fromEntries(Object.entries(user).filter(([key]) => !fields.includes(key)))
  for (const [name, call] of [
    ['loginPending', () => fe.repository.login(fixtures.inputs.localSignup)],
    ['newGoogleSignup', () => fe.repository.loginWithGoogle(fixtures.inputs.existingGoogleLogin)],
    ['meLegacy', () => fe.repository.getMe('synthetic-access-not-a-jwt')],
  ]) {
    const fixture = fixtures.responses[name]
    const data = fixture.body.data
    const body = { ...fixture.body, data: data.user ? { ...data, user: omit(data.user) } : omit(data) }
    fe.respond(name, { body })
    const result = await call()
    const user = result.user ?? result
    for (const field of fields) assert.equal(field in user, false)
    assert.equal(fe.hasEmailVerificationSupport(user), false)
  }
})

apiTest('resolved gap 5 ON: status module uses the Bearer wrapper and preserves all new/legacy states', async (fe) => {
  for (const name of ['statusPending', 'statusNewUnknown', 'statusLegacyUnknown', 'statusLegacyPending', 'confirmVerified']) {
    const expected = fixtures.responses[name].body.data
    fe.respond('statusPending', { body: { ...fixtures.responses.statusPending.body, data: expected } })
    const result = await fe.getEmailVerificationStatus(selfRequest(fe))
    assert.deepEqual(plain(result), expected)
    assert.equal(fe.requiresEmailVerification(result), expected.emailVerificationRequired)
    const request = fe.requests.at(-1)
    assert.equal(request.headers.authorization, 'Bearer synthetic-access-not-a-jwt')
    assert.equal(request.cache, 'no-store')
    assert.equal(request.body, undefined)
  }
})

apiTest('status module rejects absent/null malformed evidence instead of treating it as verified', async (fe) => {
  for (const data of [null, {}, { emailVerification: 'PENDING', emailVerificationRequired: false }, { emailVerification: 'BAD', emailVerificationRequired: true, emailVerifiedAt: null }]) {
    fe.respond('statusPending', { body: { ...fixtures.responses.statusPending.body, data } })
    await assert.rejects(fe.getEmailVerificationStatus(selfRequest(fe)), errorIs('INVALID_RESPONSE'))
  }
})

apiTest('resolved gap 5 ON: resend accepts 202/null with no token/email body and no automatic follow-up', async (fe) => {
  fe.respond('requestAccepted')
  assert.equal(await fe.requestEmailVerification(selfRequest(fe)), undefined)
  const request = fe.requests[0]
  assert.equal(request.headers.authorization, 'Bearer synthetic-access-not-a-jwt')
  assert.equal(request.body, undefined)
  assert.equal(request.cache, 'no-store')
  assert.equal(fe.requests.length, 1)
})

apiTest('resolved gap 5 ON: confirm is public POST, omits cookies/Bearer and discards the token-account response', async (fe) => {
  fe.respond('confirmVerified')
  assert.equal(await fe.confirmEmailVerification(fixtures.inputs.confirm.token), undefined)
  const request = fe.requests[0]
  assert.deepEqual(plain(request.body), fixtures.inputs.confirm)
  assert.equal(request.headers.authorization, undefined)
  assert.equal(request.credentials, 'omit')
  assert.equal(request.cache, 'no-store')
  assert.equal(request.referrerPolicy, 'no-referrer')
  assert.equal(fe.requests.length, 1)
})

apiTest('confirm success/replay, malformed token and expiry errors do not trigger automatic retries or login', async (fe) => {
  fe.respond('confirmVerified')
  await fe.confirmEmailVerification(fixtures.inputs.confirm.token)
  for (const name of ['confirmInvalid', 'confirmBadFormat']) {
    fe.respond(name)
    await assert.rejects(fe.confirmEmailVerification(name === 'confirmBadFormat' ? 'bad-format' : fixtures.inputs.confirm.token), errorIs(fixtures.responses[name].body.error.code, 400))
  }
  assert.equal(fe.requests.length, 3)
})

apiTest('request/confirm 429 without Retry-After remains a manual retry error', async (fe) => {
  for (const [name, call] of [
    ['requestRateLimited', () => fe.requestEmailVerification(selfRequest(fe))],
    ['confirmRateLimited', () => fe.confirmEmailVerification(fixtures.inputs.confirm.token)],
  ]) {
    fe.respond(name)
    await assert.rejects(call(), (error) => errorIs('RATE_LIMIT_EXCEEDED', 429)(error) && error.retryAfterSeconds === null)
  }
  assert.equal(fe.requests.length, 2)
})

apiTest('status 401 and business JSON/raw 403 retain distinct API contracts', async (fe) => {
  fe.respond('statusAnonymous')
  await assert.rejects(fe.getEmailVerificationStatus(selfRequest(fe)), errorIs('AUTHENTICATION_REQUIRED', 401))
  for (const client of [fe.apiRequest, fe.rawApiRequest]) {
    fe.respond('emailGateDenied')
    await assert.rejects(client('/api/materials', { accessToken: 'synthetic-access-not-a-jwt' }), (error) => {
      assert.ok(errorIs('EMAIL_VERIFICATION_REQUIRED', 403)(error))
      assert.equal(fe.isEmailVerificationError(error), true)
      return true
    })
  }
  assert.equal(fe.requests.length, 3)
})

sourceTest('OFF still omits LOCAL/new Google DOB/consents even when the caller supplies them', async () => {
  const fe = loadFeConsumer(feRoot)
  for (const [name, call] of [
    ['localSignup', () => fe.repository.signup(fixtures.inputs.localSignup)],
    ['newGoogleSignup', () => fe.repository.loginWithGoogle(fixtures.inputs.newGoogleSignup)],
  ]) {
    fe.respond(name)
    await call()
    assert.equal('dateOfBirth' in fe.requests.at(-1).body, false)
    assert.equal('consents' in fe.requests.at(-1).body, false)
  }
})

sourceTest('OFF rejects all three email operations locally without issuing HTTP requests', async () => {
  const fe = loadFeConsumer(feRoot)
  for (const call of [
    () => fe.getEmailVerificationStatus(selfRequest(fe)),
    () => fe.requestEmailVerification(selfRequest(fe)),
    () => fe.confirmEmailVerification(fixtures.inputs.confirm.token),
  ]) await assert.rejects(call(), errorIs('AUTH_CONTRACT_UNAVAILABLE'))
  assert.equal(fe.requests.length, 0)
})

apiTest('DOB validation is calendar-only and zero required policies can be submitted as an empty array', async (fe) => {
  for (const value of [undefined, '2015-02-29', '0000-01-01', '2000/01/01']) assert.equal(typeof fe.validateDateOfBirth(value), 'string')
  for (const value of ['2000-02-29', '2015-01-02', '2099-01-01']) assert.equal(fe.validateDateOfBirth(value), undefined)
  assert.equal(fe.validateSignupForm({ ...fixtures.inputs.localSignup, dateOfBirth: '' }).dateOfBirth, '생년월일을 입력하세요.')
  fe.respond('localSignup')
  await fe.repository.signup({ ...fixtures.inputs.localSignup, consents: [] })
  assert.deepEqual(plain(fe.requests[0].body.consents), [])
})

apiTest('policy error remains POLICY_CONSENT_REQUIRED and FE policy source matches BE routes and field names', async (fe) => {
  fe.respond('policyRequired')
  await assert.rejects(fe.repository.signup(fixtures.inputs.localSignup), errorIs('POLICY_CONSENT_REQUIRED', 400))
  const fields = recordFields('main-service/src/main/java/io/edupilot/policy/dto/PolicyDocumentSummary.java', 'PolicyDocumentSummary')
  for (const name of ['type', 'version', 'title', 'summary', 'requiresConsent']) assert.ok(fields.includes(name))
  const hook = fe.readFe('src/features/auth/useSignupContract.ts')
  assert.ok(hook.includes("'/api/policies/current'"))
  assert.ok(hook.includes('documents.every((item) =>'))
  assert.ok(fe.readFe('src/features/auth/SignupContractFields.tsx').includes('/api/policies/${encodeURIComponent(document.type)}/${encodeURIComponent(document.version)}'))
  assert.ok(readBe('main-service/src/main/java/io/edupilot/policy/PolicyController.java').includes('@GetMapping("/{type}/{version}")'))
})

apiTest('source-only: public route/manual confirm and current-account status/me refresh are wired', (fe) => {
  assert.ok(fe.readFe('src/app/routes.ts').includes("verifyEmail: '/verify-email'"))
  assert.ok(fe.readFe('src/app/AppRoutes.tsx').includes('<Route path={routes.verifyEmail} element={<VerifyEmailPage />} />'))
  const page = fe.readFe('src/app/pages/VerifyEmailPage.tsx')
  assert.ok(page.includes("onClick={() => void act('confirm')}"))
  assert.ok(page.includes('await confirmEmailVerification(token, controller.signal)'))
  assert.ok(page.includes('await refresh?.(controller.signal)'))
  const provider = fe.readFe('src/features/auth/AuthProvider.tsx')
  assert.ok(provider.includes('await getEmailVerificationStatus(authenticatedRequest, signal)'))
  assert.ok(provider.includes('await repository.getMe(active.accessToken, signal)'))
  assert.ok(provider.includes('me.id !== current.user.id || me.email !== current.user.email'))
  assert.ok(fe.readFe('src/features/auth/SignupContractFields.tsx').includes('signup-date-of-birth'))
})

apiTest('source-only: JSON/raw 403 hold runs before refresh/logout and account-management paths remain allowed', (fe) => {
  const source = fe.readFe('src/features/auth/AuthProvider.tsx')
  for (const marker of ['const authenticatedRequest =', 'const authenticatedRawRequest =']) {
    const section = source.slice(source.indexOf(marker))
    assert.ok(section.indexOf('if (isEmailVerificationError(error))') < section.indexOf('getTerminalLogoutReason(error)'))
    assert.ok(section.includes('requiresEmailVerification(requestSession.user) && !isAccountManagementRequest(path)'))
  }
  for (const path of ['/api/users/me', '/api/users/me/password', '/api/users/me/consents', '/api/auth/email-verification/status', '/api/policies/current']) assert.equal(fe.isAccountManagementRequest(path), true)
  for (const path of ['/api/materials', '/api/classrooms', '/api/users/other']) assert.equal(fe.isAccountManagementRequest(path), false)
})
