import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { stripTypeScriptTypes } from 'node:module'
import { compileFunction, createContext } from 'node:vm'
import { loadFeConsumer } from './helpers.mjs'

export const settingsReview = JSON.parse(readFileSync(new URL('./settings-fragment-review.json', import.meta.url), 'utf8'))
export const plain = (value) => JSON.parse(JSON.stringify(value))
export function deferred() {
  let resolvePromise, rejectPromise
  const promise = new Promise((resolve, reject) => { resolvePromise = resolve; rejectPromise = reject })
  return { promise, resolve: resolvePromise, reject: rejectPromise }
}
const js = (source) => stripTypeScriptTypes(source, { mode: 'transform' }).replace(/^export\s+/gm, '')

export function loadSettingsConsumer(feRoot, injectedRequest) {
  const fe = loadFeConsumer(feRoot)
  const calls = []
  const request = (path, options = {}) => {
    calls.push({ path, ...options })
    return injectedRequest ? injectedRequest(path, options) : fe.apiRequest(path, { ...options, accessToken: 'synthetic-settings-not-a-jwt' })
  }
  const source = fe.readFe('src/features/auth/userSettingsRepository.ts')
  const factory = compileFunction(`${js(source)}\nreturn createUserSettingsRepository;`, [], {
    parsingContext: createContext({ FormData }), filename: resolve(feRoot, 'src/features/auth/userSettingsRepository.ts'),
  })()
  return {
    ...fe, calls, settings: factory(request, fe.rawApiRequest),
    preferences(data, method = 'GET') {
      fe.respond('statusPending', { path: '/api/users/me/preferences', method, body: { success: true, data, message: '요청이 성공했습니다.' } })
    },
    failure(code, status, method = 'GET') {
      fe.respond('statusAnonymous', { path: '/api/users/me/preferences', method, status, body: {
        success: false, error: { code, message: '합성 실패', details: [] }, traceId: 'synthetic-settings-trace', timestamp: '2026-10-04T12:41:00Z',
      } })
    },
  }
}

// Execute the unchanged closure bodies only. This is not a React render, lifecycle runner or browser test.
export function settingsSaveFrame(fe, repository = fe.settings, ready = true) {
  const source = fe.readFe('src/app/pages/SettingsPage.tsx')
  function body(start, end) {
    const from = source.indexOf(start), to = source.indexOf(end, from)
    if (from < 0 || to < 0) throw new Error(`Pinned SettingsPage closure boundary changed: ${start}`)
    return js(source.slice(from, to))
  }
  const preferences = body('  async function savePreferences(', '  async function uploadAvatar(')
  const profile = body('  async function saveProfile(', '  async function savePreferences(')
  const applied = [], busy = [], toasts = [], updatedUsers = []
  const scope = {
    confirmedPreferencesRef: { current: { owner: 'id:910001', values: { ...settingsReview.preferences } } },
    preferencesGenerationRef: { current: 1 }, preferencesOwner: 'id:910001', arePreferencesReady: ready,
    preferencesOwnerRef: { current: 'id:910001' }, preferencesSaveLockRef: { current: null },
    applyPreferences: (value) => applied.push(plain(value)), setIsSavingPreferences: (value) => busy.push(value),
    repository, showToast: (...args) => toasts.push(args), getRequestErrorMessage: (error) => error.message,
    user: { id: 910001, email: 'settings-a@example.invalid' }, isSavingProfile: false,
    setIsSavingProfile: () => {}, name: '  합성 사용자  ', affiliation: '  합성 소속  ', updateUser: (value) => updatedUsers.push(plain(value)),
  }
  const functions = compileFunction(`const {${Object.keys(scope).join(',')}} = __scope;\n${preferences}\n${profile}\nreturn { savePreferences, saveProfile };`, ['__scope'], {
    parsingContext: createContext({ AbortController }), filename: 'SettingsPage captured closure bodies (no React)',
  })(scope)
  return { ...functions, scope, applied, busy, toasts, updatedUsers }
}

// Minimal window/document stand-ins run the actual token parser and optional early HTML script.
// No real DOM, navigation, storage, server access or token is used.
export function loadEmailLinkBoundary(feRoot, url, bootstrap, beforeModule = () => {}) {
  let location = new URL(url), meta
  const historyWrites = [], events = {}, network = []
  const window = {
    get location() { return location },
    history: { replaceState: (state, _title, path) => { historyWrites.push({ state, path }); location = new URL(path, location) } },
    addEventListener: (name, callback, options) => { (events[name] ??= []).push({ callback, once: options?.once === true }) },
    removeEventListener: (name, callback) => { events[name] = (events[name] ?? []).filter((entry) => entry.callback !== callback) },
  }
  const document = {
    querySelector: () => meta ?? null, createElement: () => ({ name: '', content: '' }),
    head: { append: (element) => { meta = element } },
  }
  const context = createContext({ window, document, URLSearchParams, fetch: (...args) => { network.push(args); throw new Error('No network allowed') } })
  if (bootstrap) {
    const html = readFileSync(resolve(feRoot, 'index.html'), 'utf8')
    const script = html.match(/<script>\s*([\s\S]*?)<\/script>/)?.[1]
    if (!script) throw new Error('Pinned early token script missing')
    compileFunction(script, [], { parsingContext: context, filename: 'FE index.html early script' })()
  }
  beforeModule({ url: location.href, historyWrites, network })
  const parser = compileFunction(`${js(readFileSync(resolve(feRoot, 'src/features/auth/emailLinkToken.ts'), 'utf8'))}\nreturn { readEmailLinkToken, clearEmailLinkToken, ...(typeof readEmailLinkIssue === 'function' ? { readEmailLinkIssue } : {}) };`, [], {
    parsingContext: context, filename: 'FE emailLinkToken.ts',
  })()
  return {
    ...parser, window, historyWrites, network, events, get location() { return location },
    navigate: (url) => { location = new URL(url, location) },
    fire: (name, extra = {}) => {
      for (const entry of [...(events[name] ?? [])]) {
        if (entry.once) window.removeEventListener(name, entry.callback)
        entry.callback({ type: name, ...extra })
      }
    },
  }
}
