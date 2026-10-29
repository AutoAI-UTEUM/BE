import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import test from 'node:test'
import { fixtures, loadFeConsumer } from './helpers.mjs'

const feRoot = process.env.FE_AUTH_SOURCE_ROOT
const sourceTest = (name, fn) => test(name, { skip: !feRoot && 'Set FE_AUTH_SOURCE_ROOT to a read-only FE checkout' }, fn)
const apiTest = (name, fn) => sourceTest(name, () => fn(loadFeConsumer(feRoot)))
const plain = (value) => JSON.parse(JSON.stringify(value))

sourceTest('FE baseline assertions use the exact reviewed source head', () => {
  // Trust only this explicitly selected, task-owned checkout for this read; no global config writes.
  const gitRead = (args) => execFileSync('git', ['-c', `safe.directory=${feRoot.replaceAll('\\', '/')}`, '-C', feRoot, ...args], { encoding: 'utf8' }).trim()
  const head = gitRead(['rev-parse', 'HEAD'])
  assert.equal(head, fixtures.source.feDevelopHead, 'New FE head needs fresh review; these tests characterize existing gaps')
  assert.equal(gitRead(['status', '--porcelain', '--untracked-files=no']), '')
})

apiTest('KNOWN GAP: LOCAL builder omits supplied DOB/consents and DOB validation bypasses field mapping', async (fe) => {
  fe.respond('localMissingDob')
  await assert.rejects(fe.repository.signup(fixtures.inputs.localSignup), (error) => {
    assert.ok(error instanceof fe.ApiClientError)
    assert.equal(error instanceof fe.AuthValidationError, false)
    assert.equal(error.code, 'VALIDATION_FAILED')
    assert.equal(error.details[0].field, 'dateOfBirth')
    return true
  })
  assert.equal('dateOfBirth' in fe.requests[0].body, false)
  assert.equal('consents' in fe.requests[0].body, false)
  assert.equal(fe.requests[0].body.email, fixtures.inputs.localSignup.email)
  assert.equal(fe.requests.length, 1)
})

apiTest('KNOWN GAP: new Google builder drops DOB/consents even if caller provides them', async (fe) => {
  fe.respond('googleSignupRequired')
  await assert.rejects(fe.repository.loginWithGoogle(fixtures.inputs.newGoogleSignup), (error) => error.code === 'SIGNUP_REQUIRED' && error.status === 409)
  assert.equal('dateOfBirth' in fe.requests[0].body, false)
  assert.equal('consents' in fe.requests[0].body, false)
  assert.equal(fe.requests[0].body.idToken, fixtures.inputs.newGoogleSignup.idToken)
  assert.equal(fe.requests.length, 1)
})

apiTest('KNOWN GAP: login maps identity/session but drops all email verification evidence', async (fe) => {
  fe.respond('loginPending')
  const result = await fe.repository.login({ email: fixtures.inputs.localSignup.email, password: fixtures.inputs.localSignup.password })
  assert.equal(result.user.id, 900001)
  assert.equal(result.accessToken, 'synthetic-access-not-a-jwt')
  for (const field of ['emailVerification', 'emailVerificationRequired', 'emailVerifiedAt']) assert.equal(field in result.user, false)
  assert.equal(fixtures.responses.loginPending.body.data.user.emailVerificationRequired, true)
  assert.equal(fe.requests[0].credentials, 'include')
})

apiTest('KNOWN GAP: me/Google identity mapping drops legacy and new-email gate fields', async (fe) => {
  fe.respond('meLegacy')
  const legacy = await fe.repository.getMe('synthetic-access-not-a-jwt')
  assert.equal(legacy.id, 900003)
  assert.equal('emailVerificationRequired' in legacy, false)
  fe.respond('newGoogleSignup')
  const google = await fe.repository.loginWithGoogle(fixtures.inputs.existingGoogleLogin)
  assert.equal(google.user.id, 900002)
  assert.equal('emailVerificationRequired' in google.user, false)
})

apiTest('KNOWN GAP: repository, signup types/UI and router have no email verification connection', (fe) => {
  for (const name of ['requestEmailVerification', 'confirmEmailVerification', 'getEmailVerificationStatus']) assert.equal(name in fe.repository, false)
  assert.doesNotMatch(fe.readFe('src/features/auth/authValidation.ts'), /dateOfBirth/)
  assert.doesNotMatch(fe.readFe('src/app/pages/SignupPage.tsx'), /dateOfBirth/)
  assert.doesNotMatch(fe.readFe('src/app/routes.ts'), /verify-email/)
  assert.doesNotMatch(fe.readFe('src/app/AppRoutes.tsx'), /[Ee]mail[Vv]erif/)
})

apiTest('existing generic client consumes pending status with Bearer and preserves nullable evidence', async (fe) => {
  fe.respond('statusPending')
  const result = await fe.apiRequest('/api/auth/email-verification/status', { accessToken: 'synthetic-access-not-a-jwt' })
  assert.deepEqual(plain(result.data), fixtures.responses.statusPending.body.data)
  assert.equal(fe.requests[0].headers.authorization, 'Bearer synthetic-access-not-a-jwt')
})

apiTest('existing generic client preserves UNKNOWN/PENDING legacy exceptions without converting them to VERIFIED', async (fe) => {
  for (const name of ['statusLegacyUnknown', 'statusLegacyPending']) {
    fe.respond(name)
    const { data } = await fe.apiRequest('/api/auth/email-verification/status', { accessToken: 'synthetic-access-not-a-jwt' })
    assert.equal(data.emailVerificationRequired, false)
    assert.equal(data.emailVerifiedAt, null)
    assert.notEqual(data.emailVerification, 'VERIFIED')
  }
})

apiTest('existing generic client consumes request 202 with null data and no email/token body', async (fe) => {
  fe.respond('requestAccepted')
  const { data } = await fe.apiRequest('/api/auth/email-verification/request', { method: 'POST', accessToken: 'synthetic-access-not-a-jwt' })
  assert.equal(data, null)
  assert.equal(fe.requests[0].body, undefined)
  assert.equal(fe.requests.length, 1)
})

apiTest('existing generic client sends public confirm POST and receives evidence without a login grant', async (fe) => {
  fe.respond('confirmVerified')
  const { data } = await fe.apiRequest('/api/auth/email-verification/confirm', { method: 'POST', body: fixtures.inputs.confirm })
  assert.equal(data.emailVerification, 'VERIFIED')
  assert.equal('accessToken' in data, false)
  assert.equal(fe.requests[0].headers.authorization, undefined)
  assert.equal(fe.requests[0].headers['content-type'], 'application/json')
  assert.deepEqual(plain(fe.requests[0].body), fixtures.inputs.confirm)
})

apiTest('confirm success then replay failure remains an error with no client automatic resend', async (fe) => {
  fe.respond('confirmVerified')
  await fe.apiRequest('/api/auth/email-verification/confirm', { method: 'POST', body: fixtures.inputs.confirm })
  fe.respond('confirmInvalid')
  await assert.rejects(fe.apiRequest('/api/auth/email-verification/confirm', { method: 'POST', body: fixtures.inputs.confirm }), (error) => error.code === 'EMAIL_VERIFICATION_TOKEN_INVALID' && error.status === 400)
  assert.equal(fe.requests.length, 2)
})

apiTest('GET confirm, invalid link and malformed token retain distinct error codes', async (fe) => {
  for (const name of ['confirmGet', 'confirmInvalid', 'confirmBadFormat']) {
    const fixture = fixtures.responses[name]
    fe.respond(name)
    const body = name === 'confirmBadFormat' ? { token: 'bad-format' } : fixtures.inputs.confirm
    await assert.rejects(fe.apiRequest(fixture.path, { method: fixture.method, ...(fixture.method === 'POST' ? { body } : {}) }),
      (error) => error.code === fixture.body.error.code && error.status === fixture.status)
  }
  assert.equal(fe.requests.length, 3)
})

apiTest('429 request/confirm retain RATE_LIMIT_EXCEEDED and tolerate absent Retry-After', async (fe) => {
  for (const name of ['requestRateLimited', 'confirmRateLimited']) {
    const fixture = fixtures.responses[name]
    fe.respond(name)
    const options = name === 'confirmRateLimited'
      ? { method: 'POST', body: fixtures.inputs.confirm }
      : { method: 'POST', accessToken: 'synthetic-access-not-a-jwt' }
    await assert.rejects(fe.apiRequest(fixture.path, options), (error) => {
      assert.equal(error.code, 'RATE_LIMIT_EXCEEDED')
      assert.equal(error.status, 429)
      assert.equal(error.retryAfterSeconds, null)
      return true
    })
  }
  assert.equal(fe.requests.length, 2)
})

apiTest('401 status and 403 email gate are separately preserved by the generic client', async (fe) => {
  for (const name of ['statusAnonymous', 'emailGateDenied']) {
    const fixture = fixtures.responses[name]
    fe.respond(name)
    await assert.rejects(fe.apiRequest(fixture.path), (error) => error.code === fixture.body.error.code && error.status === fixture.status)
  }
})

apiTest('raw file/stream client also preserves email gate 403 before reading resource bytes', async (fe) => {
  fe.respond('emailGateDenied')
  await assert.rejects(fe.rawApiRequest('/api/materials', { accessToken: 'synthetic-access-not-a-jwt' }), (error) => {
    assert.equal(error.code, 'EMAIL_VERIFICATION_REQUIRED')
    assert.equal(error.status, 403)
    return true
  })
  assert.equal(fe.requests.length, 1)
})

apiTest('Google email conflict remains EMAIL_ALREADY_EXISTS without signup retries', async (fe) => {
  fe.respond('googleEmailConflict')
  await assert.rejects(fe.repository.loginWithGoogle(fixtures.inputs.existingGoogleLogin), (error) => error.code === 'EMAIL_ALREADY_EXISTS' && error.status === 409)
  assert.equal(fe.requests.length, 1)
})

apiTest('malformed DOB and policy failures retain their stable codes and trace IDs', async (fe) => {
  for (const name of ['malformedDob', 'policyRequired']) {
    fe.respond(name)
    const body = { ...fixtures.inputs.localSignup }
    if (name === 'malformedDob') body.dateOfBirth = 'not-a-date'
    else delete body.consents
    await assert.rejects(fe.apiRequest('/api/auth/signup', { method: 'POST', body }), (error) => error.code === fixtures.responses[name].body.error.code && error.traceId === 'synthetic-trace')
  }
})
