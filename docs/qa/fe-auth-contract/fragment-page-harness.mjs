import { readFileSync } from 'node:fs'
import { stripTypeScriptTypes } from 'node:module'
import { compileFunction, createContext } from 'node:vm'
import { loadFeConsumer } from './helpers.mjs'
import { loadEmailLinkBoundary } from './settings-harness.mjs'

export const fragmentReview = JSON.parse(readFileSync(new URL('./pr232-review.json', import.meta.url), 'utf8'))

// Capture actual callbacks/effect bodies; refs, setters and lifecycle dispatch are stand-ins.
// No React render, browser/BFCache, sockets or real accounts are run here.
export function emailPageFrame(feRoot, url, { ready = true, bootstrap = true, refresh, beforeModule } = {}) {
  const parser = loadEmailLinkBoundary(feRoot, url, bootstrap, beforeModule)
  const fe = loadFeConsumer(feRoot, { readiness: ready ? fragmentReview.syntheticReadiness : undefined })
  const original = new URL(url)
  const location = { search: original.search, hash: original.hash }
  const effects = [], navigations = [], refreshSignals = []
  let layout
  const state = { busy: false, hasToken: Boolean(parser.readEmailLinkToken()), message: '', issue: parser.readEmailLinkIssue() }
  const scope = {
    ready: fe.isLaunchAuthReady(), location,
    tokenRef: { current: parser.readEmailLinkToken() }, activeRef: { current: null }, mountedRef: { current: false },
    setBusy: (value) => { state.busy = value }, setHasToken: (value) => { state.hasToken = value },
    setMessage: (value) => { state.message = value }, setLinkIssue: (value) => { state.issue = value },
    readEmailLinkToken: parser.readEmailLinkToken, readEmailLinkIssue: parser.readEmailLinkIssue, clearEmailLinkToken: parser.clearEmailLinkToken,
    confirmEmailVerification: fe.confirmEmailVerification, requestEmailVerification: fe.requestEmailVerification,
    refresh: async (signal) => { refreshSignals.push(signal); return refresh?.(fe, signal) },
    auth: { apiRequest: (path, options) => fe.apiRequest(path, { ...options, accessToken: 'synthetic-current-account-not-a-jwt' }) },
    ApiClientError: fe.ApiClientError, routes: { verifyEmail: '/verify-email' },
    useCallback: (callback) => callback, useLayoutEffect: (callback) => { layout = callback },
    useEffect: (callback) => { effects.push(callback) },
    navigate: (path, options) => { navigations.push({ path, options }); parser.navigate(path); location.search = ''; location.hash = '' },
  }
  const source = fe.readFe('src/app/pages/VerifyEmailPage.tsx')
  function snippet(start, end) {
    const from = source.indexOf(start), to = source.indexOf(end, from)
    if (from < 0 || to < 0) throw new Error(`Pinned VerifyEmailPage callback boundary changed: ${start}`)
    return stripTypeScriptTypes(source.slice(from, to), { mode: 'transform' })
  }
  const discardAndLayout = snippet('  const discardPending = useCallback(', '  useEffect(() => {')
  const mount = snippet('  useEffect(() => {', '  useEffect(() => {\n    if (!ready || !auth.isAuthenticated')
  const act = snippet('  async function act(', '  const supported =')
  const callbacks = compileFunction(`const {${Object.keys(scope).join(',')}} = __scope;\n${discardAndLayout}\n${mount}\n${act}\nreturn { act, discardPending };`, ['__scope'], {
    parsingContext: createContext({ AbortController, window: parser.window, queueMicrotask }), filename: 'VerifyEmailPage captured callbacks (no React)',
  })(scope)
  const cleanup = effects[0]()
  layout()
  return {
    ...callbacks, fe, parser, state, scope, navigations, refreshSignals,
    enter(url) {
      const target = new URL(url, parser.location)
      parser.navigate(target.href)
      location.search = target.search; location.hash = target.hash
      parser.readEmailLinkToken() // actual useRef argument also executes while rendering a replacement link
      layout()
    },
    unmount: cleanup,
  }
}
