import { readFile, writeFile, mkdir } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import path from 'node:path'

const base = new URL(process.env.GUIDE_EVAL_BASE_URL ?? 'http://127.0.0.1:8081')
if (base.protocol !== 'http:' || !['127.0.0.1', 'localhost', '[::1]'].includes(base.hostname)) {
  throw new Error('Evaluation is restricted to an explicitly local test server')
}
const args = process.argv.slice(2)
const option = name => args.includes(name) ? args[args.indexOf(name) + 1] : undefined
const cases = JSON.parse(await readFile(new URL('../src/test/resources/guide-evaluation.json', import.meta.url), 'utf8'))
const selected = option('--case')?.split(',')
const limit = Number(option('--limit') ?? cases.length)
if (!Number.isInteger(limit) || limit < 1 || limit > cases.length) throw new Error('Invalid evaluation limit')
const output = path.resolve(option('--out') ?? fileURLToPath(new URL('../var/guide-evaluation.json', import.meta.url)))
const cookies = new Map()

async function request(route, init = {}) {
  const response = await fetch(new URL(route, base), {
    ...init,
    headers: { ...init.headers, Cookie: [...cookies].map(([name, value]) => `${name}=${value}`).join('; ') },
    signal: AbortSignal.timeout(75000),
  })
  for (const cookie of response.headers.getSetCookie()) {
    const pair = cookie.split(';')[0]
    const split = pair.indexOf('=')
    cookies.set(pair.slice(0, split), pair.slice(split + 1))
  }
  const body = await response.json()
  if (!response.ok) throw new Error(`HTTP ${response.status}: ${body.code ?? 'UNKNOWN'}`)
  return body
}

const capabilities = await request('/api/shop/v1/capabilities')
if (!capabilities.guideAvailable) throw new Error('A real guide model must be enabled')
const catalog = await request('/api/shop/v1/products')
const expectedCodes = new Set(['TEST-BLUE-M', 'TEST-IVORY-M', 'TEST-GRAY-M'])
if (catalog.length !== 3 || catalog.some(product => product.evidence.dataOrigin !== 'LOCAL_TEST')
    || catalog.flatMap(product => product.skus).some(sku => !expectedCodes.has(sku.code))) {
  throw new Error('Fixed questions require the three explicitly labelled supply-demo products')
}
const csrf = await request('/api/auth/v1/csrf')
const report = { createdAt: new Date().toISOString(), model: capabilities.guideModelName, cases: [] }
for (const item of cases.filter(item => !selected || selected.includes(item.id)).slice(0, limit)) {
  const started = performance.now()
  const history = []
  let reply
  let referenceCodes
  try {
    for (const text of item.messages) {
      history.push({ role: 'user', content: text })
      reply = await request('/api/shop/v1/guide/messages', {
        method: 'POST', headers: { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': csrf.token },
        body: JSON.stringify({ messages: history }),
      })
      if (!referenceCodes && item.selectPosition) referenceCodes = reply.products[item.selectPosition - 1]?.skus.map(sku => sku.code)
      history.push({ role: 'assistant', content: reply.products.length
        ? `${reply.answer.slice(0, 1400)}\n上轮展示顺序（仅标识，不代表当前可售）：${JSON.stringify(reply.products.map(product => ({ id: product.id, title: product.title })))}`
        : reply.answer })
    }
    const codes = reply.products.flatMap(product => product.skus.map(sku => sku.code))
    const checks = {
      hasAnswer: typeof reply.answer === 'string' && reply.answer.trim().length > 0,
      noDemoFallback: reply.demoProductIds.length === 0,
      testSource: reply.products.every(product => product.evidence.dataOrigin === 'LOCAL_TEST'
        && product.evidence.confirmationStatus === 'TEST_CONFIRMED'),
      productBoundary: !item.noProducts || reply.products.length === 0,
      expectedSelection: !item.allowedSkus || codes.every(code => item.allowedSkus.includes(code)),
      requestedMatch: !item.requireProducts || reply.products.length > 0,
      followUp: !item.requireQuestion || /[?？]/.test(reply.answer),
      answerSignal: !item.answerAny || item.answerAny.some(text => reply.answer.includes(text)),
      noUnsupportedNudge: !item.answerNone || item.answerNone.every(text => !reply.answer.includes(text)),
      referenceSelection: !item.selectPosition || !!referenceCodes?.length && codes.length > 0 && codes.every(code => referenceCodes.includes(code)),
    }
    const result = { id: item.id, pass: Object.values(checks).every(Boolean), milliseconds: Math.round(performance.now() - started),
      checks, answer: reply.answer, selectedSkus: codes, turns: item.messages.length }
    report.cases.push(result)
    console.log(JSON.stringify({ id: result.id, pass: result.pass, milliseconds: result.milliseconds,
      selectedSkus: codes, failedChecks: Object.entries(checks).filter(([, passed]) => !passed).map(([name]) => name) }))
  } catch (error) {
    report.cases.push({ id: item.id, pass: false, failure: error.name === 'TimeoutError' ? 'TIMEOUT' : error.message })
    console.log(JSON.stringify({ id: item.id, pass: false, failure: report.cases.at(-1).failure }))
  }
}
report.passed = report.cases.filter(item => item.pass).length
report.total = report.cases.length
await mkdir(path.dirname(output), { recursive: true })
await writeFile(output, JSON.stringify(report, null, 2) + '\n', 'utf8')
console.log(JSON.stringify({ passed: report.passed, total: report.total, output }))
if (!report.total || report.passed !== report.total) process.exitCode = 1
