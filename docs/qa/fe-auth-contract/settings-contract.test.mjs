import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import test from 'node:test'
import { readBe, recordFields } from './helpers.mjs'
import { deferred, loadSettingsConsumer, plain, settingsReview as review, settingsSaveFrame } from './settings-harness.mjs'

const feRoot = process.env.FE_AUTH_SETTINGS_SOURCE_ROOT
const sourceTest = (name, fn) => test(name, { skip: !feRoot && 'Set FE_AUTH_SETTINGS_SOURCE_ROOT to the read-only FE231 checkout' }, fn)
const consumerTest = (name, fn) => sourceTest(name, () => fn(loadSettingsConsumer(feRoot)))
const errorIs = (code) => (error) => error.code === code

sourceTest('FE231 settings source is exact and clean; activation and fragment agreement remain pending', () => {
  const gitRead = (args) => execFileSync('git', ['-c', `safe.directory=${feRoot.replaceAll('\\', '/')}`, '-C', feRoot, ...args], { encoding: 'utf8' }).trim()
  assert.equal(gitRead(['rev-parse', 'HEAD']), review.feDevHead)
  assert.equal(gitRead(['status', '--porcelain', '--untracked-files=no']), '')
  assert.equal(review.deploymentOrActivationApproved, false)
  assert.equal(review.fragmentAgreement, 'pending')
})

test('BE preferences are nullable partial PATCH fields, reject all-null input and return a full snapshot', () => {
  const root = 'main-service/src/main/java/io/edupilot/user/'
  assert.deepEqual(recordFields(`${root}dto/UpdatePreferencesRequest.java`, 'UpdatePreferencesRequest').sort(), Object.keys(review.preferences).sort())
  assert.deepEqual(recordFields(`${root}dto/UserPreferencesResponse.java`, 'UserPreferencesResponse').sort(), Object.keys(review.preferences).sort())
  const dto = readBe(`${root}dto/UpdatePreferencesRequest.java`)
  assert.match(dto, /Boolean newMaterialNotification/)
  assert.doesNotMatch(dto, /@NotNull|@NotBlank/)
  const service = readBe(`${root}UserService.java`)
  assert.match(service, /request\.newMaterialNotification\(\) == null\s*&& request\.studyReminder\(\) == null\s*&& request\.aiAnswerStyle\(\) == null/)
  const entity = readBe(`${root}User.java`)
  for (const field of Object.keys(review.preferences)) assert.ok(entity.includes(`if (${field} != null)`))
  assert.ok(entity.includes('private boolean studyReminder = true;'))
  assert.ok(readBe(`${root}UserController.java`).includes('@PatchMapping("/me/preferences")'))
  assert.ok(readBe('main-service/src/main/java/io/edupilot/auth/EmailVerificationWebConfig.java').includes('"/api/users/me/preferences"'))
})

consumerTest('preferences GET consumes the full non-default BE snapshot with Bearer and the caller AbortSignal', async (fe) => {
  fe.preferences(review.preferences)
  const controller = new AbortController()
  assert.deepEqual(plain(await fe.settings.getPreferences(controller.signal)), review.preferences)
  assert.equal(fe.calls[0].signal, controller.signal)
  assert.equal(fe.requests[0].headers.authorization, 'Bearer synthetic-settings-not-a-jwt')
  assert.equal(fe.requests[0].body, undefined)
})

consumerTest('failed GET keeps the BE error instead of returning local defaults or starting a PATCH', async (fe) => {
  fe.failure('INTERNAL_SERVER_ERROR', 500)
  await assert.rejects(fe.settings.getPreferences(), (error) => errorIs('INTERNAL_SERVER_ERROR')(error) && error.traceId === 'synthetic-settings-trace')
  assert.deepEqual(fe.requests.map((request) => request.method), ['GET'])
})

consumerTest('partial wire PATCH preserves false, omits undefined fields and does not leak account/email fields', async (fe) => {
  fe.preferences({ ...review.preferences, studyReminder: false }, 'PATCH')
  const controller = new AbortController()
  await fe.settings.updatePreferences({ studyReminder: false, emailVerification: 'VERIFIED', dateOfBirth: '2000-01-01' }, controller.signal)
  assert.deepEqual(plain(fe.requests[0].body), { studyReminder: false })
  assert.equal(fe.calls[0].signal, controller.signal)
  assert.equal(fe.requests[0].method, 'PATCH')
})

consumerTest('empty/all-null PATCH and bad enum retain VALIDATION_FAILED/MALFORMED_REQUEST', async (fe) => {
  for (const [input, code] of [[{}, 'VALIDATION_FAILED'], [{ studyReminder: null }, 'VALIDATION_FAILED'], [{ aiAnswerStyle: 'BAD' }, 'MALFORMED_REQUEST']]) {
    fe.failure(code, 400, 'PATCH')
    await assert.rejects(fe.settings.updatePreferences(input), errorIs(code))
  }
  assert.equal(fe.requests.length, 3)
})

consumerTest('captured preference save merges a field into confirmed values and trusts the returned full snapshot', async (fe) => {
  const canonical = { aiAnswerStyle: 'CONCISE', newMaterialNotification: true, studyReminder: false }
  fe.preferences(canonical, 'PATCH')
  const frame = settingsSaveFrame(fe)
  await frame.savePreferences({ newMaterialNotification: true })
  assert.deepEqual(plain(fe.requests[0].body), { ...review.preferences, newMaterialNotification: true })
  assert.deepEqual(frame.applied.at(-1), canonical)
  assert.deepEqual(plain(frame.scope.confirmedPreferencesRef.current.values), canonical)
  assert.ok(fe.calls[0].signal instanceof AbortSignal)
})

consumerTest('failed preference PATCH rolls back the view; the next unrelated edit preserves the last confirmed values', async (fe) => {
  const frame = settingsSaveFrame(fe)
  fe.failure('INTERNAL_SERVER_ERROR', 500, 'PATCH')
  await frame.savePreferences({ newMaterialNotification: true })
  assert.deepEqual(frame.applied.at(-1), review.preferences)
  assert.deepEqual(plain(frame.scope.confirmedPreferencesRef.current.values), review.preferences)
  assert.equal(frame.toasts.length, 1)
  fe.preferences({ ...review.preferences, aiAnswerStyle: 'CONCISE' }, 'PATCH')
  await frame.savePreferences({ aiAnswerStyle: 'CONCISE' })
  assert.deepEqual(plain(fe.requests[1].body), { ...review.preferences, aiAnswerStyle: 'CONCISE' })
})

sourceTest('boundary: an uncertain transport outcome rolls back only the UI and a later full payload uses the old snapshot', async () => {
  const base = loadSettingsConsumer(feRoot)
  let count = 0
  const fe = loadSettingsConsumer(feRoot, async () => {
    if (++count === 1) throw new base.ApiClientError({ code: 'NETWORK_ERROR', message: '합성 응답 유실' })
    return { success: true, data: { ...review.preferences, aiAnswerStyle: 'CONCISE' }, message: '요청이 성공했습니다.' }
  })
  const frame = settingsSaveFrame(fe)
  await frame.savePreferences({ newMaterialNotification: true })
  await frame.savePreferences({ aiAnswerStyle: 'CONCISE' })
  assert.equal(fe.calls[0].body.newMaterialNotification, true)
  assert.equal(fe.calls[1].body.newMaterialNotification, false)
  // No actual BE commit is simulated or observed; cancellation/response loss is not rollback evidence.
})

sourceTest('late failed preference PATCH after an account change cannot roll back the new owner or clear its lock', async () => {
  const delayed = deferred()
  const fe = loadSettingsConsumer(feRoot, () => delayed.promise)
  const frame = settingsSaveFrame(fe)
  const saving = frame.savePreferences({ newMaterialNotification: true })
  const nextLock = { owner: 'id:910002', generation: 2, controller: new AbortController() }
  frame.scope.preferencesOwnerRef.current = 'id:910002'
  frame.scope.preferencesGenerationRef.current = 2
  frame.scope.preferencesSaveLockRef.current = nextLock
  const before = frame.applied.length
  delayed.reject(new fe.ApiClientError({ code: 'INTERNAL_SERVER_ERROR', message: '합성 늦은 실패', status: 500 }))
  await saving
  assert.equal(frame.applied.length, before)
  assert.equal(frame.toasts.length, 0)
  assert.equal(frame.scope.preferencesSaveLockRef.current, nextLock)
  assert.deepEqual(frame.busy, [true])
})

sourceTest('same-owner reload generation also rejects a stale failed save without changing the new lock', async () => {
  const delayed = deferred()
  const fe = loadSettingsConsumer(feRoot, () => delayed.promise)
  const frame = settingsSaveFrame(fe)
  const saving = frame.savePreferences({ studyReminder: false })
  frame.scope.preferencesGenerationRef.current = 2
  const nextLock = { owner: 'id:910001', generation: 2, controller: new AbortController() }
  frame.scope.preferencesSaveLockRef.current = nextLock
  delayed.reject(new fe.ApiClientError({ code: 'NETWORK_ERROR', message: '합성 오래된 실패' }))
  await saving
  assert.equal(frame.toasts.length, 0)
  assert.equal(frame.scope.preferencesSaveLockRef.current, nextLock)
  assert.equal(frame.applied.length, 1)
})

sourceTest('aborted save ignores its later rejection instead of showing an error or rolling back a newer view', async () => {
  const delayed = deferred()
  const fe = loadSettingsConsumer(feRoot, () => delayed.promise)
  const frame = settingsSaveFrame(fe)
  const saving = frame.savePreferences({ studyReminder: false })
  frame.scope.preferencesSaveLockRef.current.controller.abort()
  delayed.reject(new fe.ApiClientError({ code: 'REQUEST_ABORTED', message: '합성 취소' }))
  await saving
  assert.equal(frame.toasts.length, 0)
  assert.equal(frame.applied.length, 1)
})

consumerTest('boundary: a success envelope does not validate the preference data shape in the current repository', async (fe) => {
  for (const data of [{ studyReminder: true }, { ...review.preferences, aiAnswerStyle: 'BAD' }, null]) {
    fe.preferences(data)
    assert.deepEqual(plain(await fe.settings.getPreferences()), data)
  }
  // These are deliberately non-contract responses. The reviewed BE response is always the full DTO.
})

sourceTest('boundary: profile save is outside the preference owner guard and still forwards a delayed old profile callback', async () => {
  const delayed = deferred()
  const fe = loadSettingsConsumer(feRoot, () => delayed.promise)
  const frame = settingsSaveFrame(fe)
  const saving = frame.saveProfile()
  frame.scope.preferencesOwnerRef.current = 'id:910002'
  frame.scope.preferencesGenerationRef.current = 2
  delayed.resolve({ success: true, data: { id: 910001, email: 'settings-a@example.invalid', name: '합성 사용자', affiliation: '합성 소속', role: 'LEARNER' }, message: '요청이 성공했습니다.' })
  await saving
  assert.equal(frame.updatedUsers.length, 1)
  assert.equal(frame.updatedUsers[0].id, 910001)
  assert.deepEqual(plain(fe.calls[0].body), { affiliation: '합성 소속', name: '합성 사용자' })
  // Callback observation only; actual AuthProvider/browser account switching is not run here.
})

consumerTest('source-only: load failure/owner guards remain distinct from unconfirmed defaults and profile save', (fe) => {
  const page = fe.readFe('src/app/pages/SettingsPage.tsx')
  assert.ok(page.includes('loadedPreferencesOwner === preferencesOwner'))
  assert.ok(page.includes('confirmedPreferencesRef.current?.owner === preferencesOwner'))
  assert.ok(page.includes('preferencesGenerationRef.current !== generation'))
  assert.ok(page.includes('preferencesOwnerRef.current !== preferencesOwner'))
  assert.ok(page.includes('disabled={!arePreferencesReady || isSavingPreferences}'))
  assert.ok(page.includes("const [studyReminder, setStudyReminder] = useState(false)"))
  const profile = page.slice(page.indexOf('  async function saveProfile('), page.indexOf('  async function savePreferences('))
  assert.doesNotMatch(profile, /preferencesOwnerRef|preferencesGenerationRef|AbortController/)
})
