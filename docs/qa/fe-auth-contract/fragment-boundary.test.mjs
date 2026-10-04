import assert from 'node:assert/strict'
import test from 'node:test'
import { readBe } from './helpers.mjs'
import { loadEmailLinkBoundary, settingsReview as review } from './settings-harness.mjs'

const feRoot = process.env.FE_AUTH_SETTINGS_SOURCE_ROOT
const sourceTest = (name, fn) => test(name, { skip: !feRoot && 'Set FE_AUTH_SETTINGS_SOURCE_ROOT to the read-only FE231 checkout' }, fn)
const tokenA = 'A'.repeat(43), tokenB = 'B'.repeat(43)
const base = 'https://contract.example.invalid/verify-email'

test('fragment contract is pending; the reviewed BE still generates a query link', () => {
  assert.equal(review.fragmentAgreement, 'pending')
  assert.equal(review.deploymentOrActivationApproved, false)
  assert.ok(readBe('main-service/src/main/java/io/edupilot/auth/EmailVerificationService.java').includes('"/verify-email?token=" + raw'))
  assert.equal(review.fragmentFailurePlan.length, 5)
  for (const item of review.fragmentFailurePlan) assert.ok(item.decision.includes('pending'))
})

sourceTest('current boundary: fragment-only token is ignored and scrubbed on bootstrap and client navigation', () => {
  for (const bootstrap of [false, true]) {
    const parser = loadEmailLinkBoundary(feRoot, `${base}#token=${tokenA}`, bootstrap)
    assert.equal(parser.readEmailLinkToken(), null)
    assert.equal(parser.location.hash, '')
    assert.equal(parser.location.search, '')
    assert.equal(parser.network.length, 0)
  }
})

sourceTest('current boundary: one query token wins over a conflicting fragment; this is not an agreed precedence', () => {
  for (const bootstrap of [false, true]) {
    const parser = loadEmailLinkBoundary(feRoot, `${base}?token=${tokenA}#token=${tokenB}`, bootstrap)
    assert.equal(parser.readEmailLinkToken(), tokenA)
    assert.equal(parser.location.href, base)
    assert.equal(parser.historyWrites.at(-1).state, null)
    parser.clearEmailLinkToken()
    assert.equal(parser.readEmailLinkToken(), null)
  }
})

sourceTest('current boundary: duplicate or malformed query tokens never fall back to a valid fragment', () => {
  for (const suffix of [`?token=${tokenA}&token=${tokenB}#token=${tokenA}`, `?token=bad-format#token=${tokenA}`, `#token=${tokenA}&token=${tokenB}`]) {
    for (const bootstrap of [false, true]) {
      const parser = loadEmailLinkBoundary(feRoot, base + suffix, bootstrap)
      assert.equal(parser.readEmailLinkToken(), null)
      assert.equal(parser.network.length, 0)
    }
  }
})

sourceTest('current boundary: percent-encoded fragment forms also have no token support', () => {
  for (const fragment of [`#token%3D${tokenA}`, `#token=${tokenA}%20`, `#token=${tokenA}`]) {
    const parser = loadEmailLinkBoundary(feRoot, base + fragment, false)
    assert.equal(parser.readEmailLinkToken(), null)
  }
})
