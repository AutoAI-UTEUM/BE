import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { stripTypeScriptTypes } from 'node:module'
import { compileFunction, createContext } from 'node:vm'

export const beRoot = fileURLToPath(new URL('../../../', import.meta.url))
export const fixtures = JSON.parse(readFileSync(new URL('./fixtures.json', import.meta.url), 'utf8'))
export const readBe = (path) => readFileSync(resolve(beRoot, path), 'utf8')

// Read record component names, ignoring annotation arguments (which may contain commas).
// This checks source shape only; it does not compile or execute Spring/Jackson.
export function recordFields(path, name) {
  const source = readBe(path)
  const start = source.search(new RegExp(`public record ${name}(?:<[^>]+>)?\\s*\\(`))
  if (start < 0) throw new Error(`Record not found: ${name}`)
  let cursor = source.indexOf('(', start) + 1
  let depth = 1
  let quoted = false
  let components = ''
  for (; cursor < source.length && depth; cursor++) {
    const char = source[cursor]
    if (char === '"' && source[cursor - 1] !== '\\') quoted = !quoted
    if (!quoted) {
      if (char === '(') depth++
      if (char === ')') depth--
    }
    if (depth) components += char
  }
  components = components.replace(/@[\w.]+(?:\s*\((?:[^()"]|"(?:\\.|[^"\\])*"|\([^()]*\))*\))?/g, '')
  return components.split(',').map((part) => part.trim().split(/\s+/).at(-1))
}

// Execute unchanged FE modules with their actual API/error/envelope logic.
// Only module wiring, getApiBaseUrl and fetch are injected; no sockets are opened.
export function loadFeConsumer(feRoot) {
  const readFe = (path) => readFileSync(resolve(feRoot, path), 'utf8')
  const requests = []
  let responseFactory = () => { throw new Error('No synthetic response configured') }
  const deps = { getApiBaseUrl: () => 'https://contract.example.invalid' }
  const context = createContext({
    Headers, Response, Blob, FormData, URLSearchParams, ArrayBuffer, DOMException,
    fetch: async (url, init) => {
      const request = {
        path: new URL(url).pathname,
        method: init.method ?? 'GET',
        headers: Object.fromEntries(init.headers.entries()),
        credentials: init.credentials,
        body: init.body ? JSON.parse(init.body) : undefined,
      }
      requests.push(request)
      return responseFactory(request)
    },
  })
  function load(path, exports) {
    const js = stripTypeScriptTypes(readFe(path), { mode: 'transform' })
      .replace(/import\s+\{([^}]+)\}\s+from\s+['"][^'"]+['"];?/g, (_, names) => `const {${names}} = __deps;`)
      .replace(/^export\s+/gm, '')
    if (/^\s*import\s/m.test(js)) throw new Error(`Unresolved import in ${path}`)
    const result = compileFunction(`${js}\nreturn {${exports.join(',')}};`, ['__deps'], {
      parsingContext: context, filename: resolve(feRoot, path),
    })(deps)
    Object.assign(deps, result)
    return result
  }
  load('src/shared/api/ApiClientError.ts', ['ApiClientError'])
  load('src/shared/api/contracts.ts', ['isApiFailure', 'isApiSuccess'])
  load('src/shared/api/rateLimitError.ts', ['mapRateLimitError'])
  load('src/shared/api/apiClient.ts', ['apiRequest'])
  load('src/shared/api/rawApiClient.ts', ['rawApiRequest'])
  load('src/features/auth/authErrors.ts', ['AuthValidationError'])
  const { getAuthRepository } = load('src/features/auth/authRepository.ts', ['getAuthRepository'])
  return {
    ...deps, readFe, requests, repository: getAuthRepository(),
    respond(name) {
      const fixture = fixtures.responses[name]
      if (!fixture) throw new Error(`Unknown response fixture: ${name}`)
      responseFactory = (request) => {
        if (request.path !== fixture.path || request.method !== fixture.method) {
          throw new Error(`Unexpected request ${request.method} ${request.path}`)
        }
        return new Response(JSON.stringify(fixture.body), {
          status: fixture.status,
          headers: { 'Content-Type': 'application/json', ...fixture.headers },
        })
      }
    },
  }
}
