import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import test from 'node:test'
import { fixtures, readBe, readBeAt } from './helpers.mjs'
import { loadEmailLinkBoundary, plain } from './settings-harness.mjs'
import { emailPageFrame, fragmentReview as review } from './fragment-page-harness.mjs'

const feRoot = process.env.FE_AUTH_FRAGMENT_SOURCE_ROOT
const sourceTest = (name, fn) => test(name, { skip: !feRoot && 'Set FE_AUTH_FRAGMENT_SOURCE_ROOT to the read-only FE232 checkout' }, fn)
const tokenA = 'A'.repeat(43), tokenB = 'B'.repeat(43)
const base = 'https://contract.example.invalid/verify-email'

sourceTest('FE232 uses its exact clean source; FE231 pending evidence is historical and activation stays OFF', () => {
  const gitRead = (args) => execFileSync('git', ['-c', `safe.directory=${feRoot.replaceAll('\\', '/')}`, '-C', feRoot, ...args], { encoding: 'utf8' }).trim()
  assert.equal(gitRead(['rev-parse', 'HEAD']), review.feHead)
  assert.equal(gitRead(['status', '--porcelain', '--untracked-files=no']), '')
  assert.equal(review.feAcceptedLink, 'fragment-only')
  assert.equal(review.deploymentOrActivationApproved, false)
  assert.equal(review.historicalFe231, '21f4ad2d30f13bafd05bfcc289515c2caac98810')
})

sourceTest('early HTML scrub executes before a module observer; static no-referrer/script/resource ordering matches source', () => {
  const observed = []
  const frame = emailPageFrame(feRoot, `${base}#token=${tokenA}`, { beforeModule: (value) => observed.push(value.url) })
  assert.deepEqual(observed, [base])
  assert.equal(frame.fe.requests.length, 0)
  const html = frame.fe.readFe('index.html')
  const firstScript = html.indexOf('<script>'), end = html.indexOf('</script>', firstScript)
  assert.ok(html.indexOf('name="referrer"') < firstScript)
  for (const marker of ['<link', 'type="module"', 'reloadStorageKey']) assert.ok(end < html.indexOf(marker))
  assert.equal(frame.state.hasToken, true)
})

sourceTest('canonical fragment flows to one explicit public POST; simultaneous duplicate clicks do not submit twice', async () => {
  const frame = emailPageFrame(feRoot, `${base}#token=${tokenA}`)
  frame.fe.respond('confirmVerified')
  assert.equal(frame.fe.requests.length, 0)
  const first = frame.act('confirm'), second = frame.act('confirm')
  assert.equal(frame.scope.tokenRef.current, null)
  assert.equal(frame.parser.readEmailLinkToken(), null)
  await Promise.all([first, second])
  assert.equal(frame.fe.requests.length, 1)
  const request = frame.fe.requests[0]
  assert.deepEqual(plain(request.body), { token: tokenA })
  assert.equal(request.path, '/api/auth/email-verification/confirm')
  assert.equal(request.method, 'POST')
  assert.equal(request.credentials, 'omit')
  assert.equal(request.headers.authorization, undefined)
  assert.equal(request.referrerPolicy, 'no-referrer')
  assert.equal(frame.refreshSignals.length, 1)
})

sourceTest('confirm response belongs to the token account; the supplied current-account refresh uses Bearer status/me separately', async () => {
  const current = { ...fixtures.responses.newGoogleSignup.body.data.user }
  const frame = emailPageFrame(feRoot, `${base}#token=${tokenA}`, { refresh: async (fe, signal) => {
    fe.respond('statusPending')
    await fe.getEmailVerificationStatus((path, options) => fe.apiRequest(path, { ...options, signal, accessToken: 'synthetic-current-account-not-a-jwt' }))
    fe.respond('meLegacy', { body: { ...fixtures.responses.meLegacy.body, data: current } })
    const user = await fe.repository.getMe('synthetic-current-account-not-a-jwt', signal)
    assert.equal(user.emailVerification, 'PENDING')
    assert.equal(user.emailVerificationRequired, true)
  } })
  frame.fe.respond('confirmVerified')
  await frame.act('confirm')
  assert.deepEqual(frame.fe.requests.map((request) => request.method), ['POST', 'GET', 'GET'])
  assert.equal(frame.fe.requests[0].headers.authorization, undefined)
  for (const request of frame.fe.requests.slice(1)) assert.equal(request.headers.authorization, 'Bearer synthetic-current-account-not-a-jwt')
})

sourceTest('reload of the sanitized URL has no token and cannot silently reuse the previous fragment', async () => {
  const original = emailPageFrame(feRoot, `${base}#token=${tokenA}`)
  const reload = emailPageFrame(feRoot, original.parser.location.href)
  await reload.act('confirm')
  assert.equal(reload.state.hasToken, false)
  assert.equal(reload.fe.requests.length, 0)
})

sourceTest('pagehide/back stand-in clears token, aborts pending confirm and releases busy before a late success', async () => {
  const frame = emailPageFrame(feRoot, `${base}#token=${tokenA}`)
  frame.fe.respond('confirmVerified')
  const saving = frame.act('confirm'), controller = frame.scope.activeRef.current
  frame.parser.fire('pagehide', { persisted: true })
  assert.equal(controller.signal.aborted, true)
  assert.equal(frame.state.busy, false)
  assert.equal(frame.state.hasToken, false)
  frame.parser.fire('pageshow', { persisted: true })
  await saving
  assert.equal(frame.refreshSignals.length, 0)
  assert.equal(frame.state.message, '')
  await frame.act('confirm')
  assert.equal(frame.fe.requests.length, 1)
})

sourceTest('SPA replacement aborts old confirm and rejects its late result while the new fragment submits once', async () => {
  const frame = emailPageFrame(feRoot, `${base}#token=${tokenA}`)
  frame.fe.respond('confirmVerified')
  const first = frame.act('confirm'), old = frame.scope.activeRef.current
  frame.enter(`${base}#token=${tokenB}`)
  assert.equal(old.signal.aborted, true)
  assert.equal(frame.state.busy, false)
  await first
  assert.equal(frame.refreshSignals.length, 0)
  assert.equal(frame.state.message, '')
  frame.fe.respond('confirmVerified')
  await frame.act('confirm')
  assert.deepEqual(frame.fe.requests.map((request) => request.body.token), [tokenA, tokenB])
  assert.equal(frame.refreshSignals.length, 1)
})

sourceTest('navigation discard and unmount prevent late response UI/refresh without claiming server rollback', async () => {
  const frame = emailPageFrame(feRoot, `${base}#token=${tokenA}`)
  frame.fe.respond('confirmVerified')
  const saving = frame.act('confirm'), controller = frame.scope.activeRef.current
  frame.discardPending()
  frame.unmount()
  await saving
  assert.equal(controller.signal.aborted, true)
  assert.equal(frame.refreshSignals.length, 0)
  assert.equal(frame.parser.readEmailLinkToken(), null)
  assert.equal(frame.state.message, '')
})

sourceTest('query, encoded-query and mixed links give obsolete-query with no public confirmation', async () => {
  for (const suffix of [`?token=${tokenA}`, `?%74oken=${tokenA}#token=${tokenB}`, `?token=#token=${tokenB}`, `?token=${tokenA}#token=${tokenB}`]) {
    for (const bootstrap of [false, true]) {
      const frame = emailPageFrame(feRoot, base + suffix, { bootstrap })
      assert.equal(frame.state.issue, 'obsolete-query')
      await frame.act('confirm')
      assert.equal(frame.fe.requests.length, 0)
      assert.equal(frame.parser.location.href, base)
    }
  }
})

sourceTest('fragment duplicates, extra fields and encoded token values are rejected without a POST', async () => {
  for (const hash of [`#token=${tokenA}&token=${tokenB}`, `#token=${tokenA}&x=1`, '#token=short', '#token=%41' + 'A'.repeat(42)]) {
    const frame = emailPageFrame(feRoot, base + hash)
    assert.equal(frame.state.issue, 'invalid-fragment')
    await frame.act('confirm')
    assert.equal(frame.fe.requests.length, 0)
  }
})

sourceTest('OFF clears initial and replacement fragments and never invokes the new APIs', async () => {
  const frame = emailPageFrame(feRoot, `${base}#token=${tokenA}`, { ready: false })
  assert.equal(frame.parser.readEmailLinkToken(), null)
  frame.enter(`${base}#token=${tokenB}`)
  for (const kind of ['confirm', 'resend', 'reload']) await frame.act(kind)
  assert.equal(frame.scope.tokenRef.current, null)
  assert.equal(frame.fe.requests.length, 0)
})

sourceTest('BE invalid-token/429 responses keep the token discarded and require a deliberate next action', async () => {
  for (const name of ['confirmInvalid', 'confirmRateLimited']) {
    const frame = emailPageFrame(feRoot, `${base}#token=${tokenA}`)
    frame.fe.respond(name)
    await frame.act('confirm')
    await frame.act('confirm')
    assert.equal(frame.fe.requests.length, 1)
    assert.equal(frame.scope.tokenRef.current, null)
    assert.equal(frame.state.busy, false)
    assert.ok(frame.state.message.includes(name === 'confirmInvalid' ? '유효하지' : '잠시 후 직접'))
  }
})

sourceTest('historical e949 BE query issuance was incompatible: an accepted resend did not prove a usable fragment link', async () => {
  assert.equal(review.beGeneratedLinkAtReviewedHead, 'query')
  assert.ok(readBeAt(review.beReviewedCandidate, 'main-service/src/main/java/io/edupilot/auth/EmailVerificationService.java').includes('"/verify-email?token=" + raw'))
  const frame = emailPageFrame(feRoot, `${base}?token=${tokenA}`)
  frame.fe.respond('requestAccepted')
  await frame.act('resend')
  assert.equal(frame.fe.requests[0].headers.authorization, 'Bearer synthetic-current-account-not-a-jwt')
  assert.equal(frame.fe.requests[0].body, undefined)
  assert.ok(frame.state.message.includes('접수'))
  const generatedAgain = loadEmailLinkBoundary(feRoot, `${base}?token=${tokenB}`, true)
  assert.equal(generatedAgain.readEmailLinkToken(), null)
  assert.equal(generatedAgain.readEmailLinkIssue(), 'obsolete-query')
})

sourceTest('current BE fragment issuer matches the pinned FE232 parser and explicit public confirmation body', async () => {
  const issuer = readBe('main-service/src/main/java/io/edupilot/auth/EmailVerificationService.java')
  const relativeLink = issuer.match(/"(\/verify-email[#?]token=)" \+ raw/)?.[1]
  assert.equal(relativeLink, '/verify-email#token=')
  assert.doesNotMatch(issuer, /"\/verify-email\?token=" \+ raw/)
  const frame = emailPageFrame(feRoot, `https://contract.example.invalid${relativeLink}${tokenA}`)
  assert.equal(frame.parser.location.href, base)
  assert.equal(frame.state.hasToken, true)
  assert.equal(frame.fe.requests.length, 0)
  frame.fe.respond('confirmVerified')
  await frame.act('confirm')
  assert.equal(frame.fe.requests.length, 1)
  assert.equal(frame.fe.requests[0].path, '/api/auth/email-verification/confirm')
  assert.deepEqual(plain(frame.fe.requests[0].body), { token: tokenA })
})

test('outbox review boundary: the source encrypts complete messages and claims their stored payload', () => {
  const source = readBe('main-service/src/main/java/io/edupilot/mail/EmailOutboxStore.java')
  assert.ok(source.includes('cipher.encrypt(id, message)'))
  assert.ok(source.includes('cipher.decrypt(id, job.payload())'))
  assert.equal(review.jointBackendTransition, 'pending-core-and-operations')
})
