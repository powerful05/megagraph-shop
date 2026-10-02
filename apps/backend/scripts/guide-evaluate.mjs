import { readFile, writeFile, mkdir } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import path from 'node:path'

const base = new URL(process.env.GUIDE_EVAL_BASE_URL ?? 'http://127.0.0.1:8081')
if (base.protocol !== 'http:' || !['127.0.0.1', 'localhost', '[::1]'].includes(base.hostname)) {
  throw new Error('Evaluation is restricted to an explicitly local test server')
}
const args = process.argv.slice(2)
const option = name => args.includes(name) ? args[args.indexOf(name) + 1] : undefined
const dataset = option('--dataset') ?? 'baseline'
if (!['baseline', 'expanded'].includes(dataset)) throw new Error('Unknown evaluation dataset')
const caseFile = dataset === 'expanded' ? 'guide-evaluation-expanded.json' : 'guide-evaluation.json'
const cases = JSON.parse(await readFile(new URL(`../src/test/resources/${caseFile}`, import.meta.url), 'utf8'))
const selected = option('--case')?.split(',')
const limit = Number(option('--limit') ?? cases.length)
if (!Number.isInteger(limit) || limit < 1 || limit > cases.length) throw new Error('Invalid evaluation limit')
const repeats = Number(option('--repeat') ?? 1)
if (!Number.isInteger(repeats) || repeats < 1 || repeats > 5) throw new Error('Invalid evaluation repetition count')
if (selected?.some(id => !cases.some(item => item.id === id))) throw new Error('Unknown evaluation case')
const output = path.resolve(option('--out') ?? fileURLToPath(new URL('../var/guide-evaluation.json', import.meta.url)))
const cookies = new Map()

async function request(route, init = {}) {
  const response = await fetch(new URL(route, base), {
    ...init,
    headers: { ...init.headers, Cookie: [...cookies].map(([name, value]) => `${name}=${value}`).join('; ') },
    signal: AbortSignal.timeout(105000),
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
const expectedCodes = new Set(['TEST-BLUE-M', 'TEST-IVORY-M', 'TEST-GRAY-M', 'TEST-BAG-A4', 'TEST-NOTE-A5'])
if (dataset === 'expanded') {
  for (let index = 1; index <= 15; index++) {
    for (const kind of ['BLUE', 'IVORY', 'BAG', 'NOTE']) expectedCodes.add(`EXP-${kind}-${String(index).padStart(2, '0')}`)
  }
}
const actualCodes = catalog.flatMap(product => product.skus.map(sku => sku.code))
if (catalog.length !== expectedCodes.size || catalog.some(product => product.evidence.dataOrigin !== 'LOCAL_TEST'
      || product.evidence.confirmationStatus !== 'TEST_CONFIRMED')
    || actualCodes.length !== expectedCodes.size || new Set(actualCodes).size !== expectedCodes.size
    || actualCodes.some(code => !expectedCodes.has(code))) {
  throw new Error(`Fixed questions require the exact ${dataset} test catalog (${expectedCodes.size} products)`)
}
if (dataset === 'expanded' && catalog.slice(0, 50).some(product => product.skus.some(sku => sku.code.startsWith('TEST-')))) {
  throw new Error('Expanded retrieval trials require all five baseline products beyond the newest 50 products')
}
const csrf = await request('/api/auth/v1/csrf')
const report = { createdAt: new Date().toISOString(), model: capabilities.guideModelName, dataset,
  catalogSize: catalog.length, repeats, cases: [] }
const scheduled = cases.filter(item => !selected || selected.includes(item.id)).slice(0, limit)
  .flatMap(item => Array.from({ length: repeats }, (_, index) => ({ item, repetition: index + 1 })))

function factTable(product) {
  const evidence = product.evidence
  const details = evidence?.details
  if (!details || !['MERCHANT_CONFIRMED', 'TEST_CONFIRMED'].includes(evidence.confirmationStatus)) return new Map()
  const entries = [['color', '颜色', details.color], ['material', '材质', details.fabric], ['scenarios', '标注用途', details.scenarios.join('、')]]
  const codes = new Set(product.skus.map(sku => sku.code))
  if (details.profile === 'APPAREL') {
    entries.push(['fit', '版型', details.fit], ['seasons', '季节', details.seasons.join('、')])
    details.sizeChart.forEach((row, index) => {
      if (!codes.has(row.skuCode)) return
      let value = `${row.sizeLabel}：胸围 ${row.chestCm} cm，衣长 ${row.lengthCm} cm`
      if (row.shoulderCm !== null) value += `，肩宽 ${row.shoulderCm} cm`
      if (row.sleeveCm !== null) value += `，袖长 ${row.sleeveCm} cm`
      entries.push([`size-${index}`, '成衣测量', value])
    })
  } else details.specifications.forEach((row, index) => {
    if (codes.has(row.skuCode)) entries.push([`spec-${index}`, row.name, row.value])
  })
  return new Map(entries.filter(([, , value]) => value).map(([key, label, value]) => [key, { label, value }]))
}

function verifiedBasis(reply) {
  const basis = reply.recommendationBasis
  if (!Array.isArray(basis) || basis.length !== reply.products.length || new Set(basis.map(item => item.productId)).size !== basis.length) return false
  return basis.every(item => {
    const product = reply.products.find(product => product.id === item.productId)
    if (!product || item.productVersion !== product.evidence.productVersion || item.dataOrigin !== product.evidence.dataOrigin
        || !Array.isArray(item.facts) || !Array.isArray(item.limitations) || !item.limitations.length) return false
    const table = factTable(product)
    return (!table.size || item.facts.length > 0) && item.facts.length <= 6
      && new Set(item.facts.map(fact => fact.key)).size === item.facts.length
      && item.facts.every(fact => table.get(fact.key)?.label === fact.label && table.get(fact.key)?.value === fact.value)
  })
}

for (const { item, repetition } of scheduled) {
  const started = performance.now()
  const history = []
  const turnResults = []
  let reply
  let referenceCodes
  let initialCount
  try {
    for (const text of item.messages) {
      history.push({ role: 'user', content: text })
      reply = await request('/api/shop/v1/guide/messages', {
        method: 'POST', headers: { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': csrf.token },
        body: JSON.stringify({ messages: history }),
      })
      turnResults.push({ turn: turnResults.length + 1, serverAnswer: reply.answer, advice: reply.advice,
        selectedSkus: reply.products.flatMap(product => product.skus.map(sku => sku.code)),
        basisVerified: verifiedBasis(reply), demoProductCount: reply.demoProductIds.length })
      if (!referenceCodes && item.selectPosition) referenceCodes = reply.products[item.selectPosition - 1]?.skus.map(sku => sku.code)
      initialCount ??= reply.products.length
      history.push({ role: 'assistant', content: [reply.answer, reply.advice].filter(Boolean).join('\n'),
        displayedProductIds: reply.products.map(product => product.id) })
    }
    const codes = reply.products.flatMap(product => product.skus.map(sku => sku.code))
    const answer = [reply.answer, reply.advice].filter(Boolean).join('\n')
    const checks = {
      hasAnswer: typeof reply.answer === 'string' && reply.answer.trim().length > 0,
      noDemoFallback: reply.demoProductIds.length === 0,
      testSource: reply.products.every(product => product.evidence.dataOrigin === 'LOCAL_TEST'
        && product.evidence.confirmationStatus === 'TEST_CONFIRMED'),
      productBoundary: !item.noProducts || reply.products.length === 0,
      expectedSelection: !item.allowedSkus || codes.every(code => item.allowedSkus.includes(code)),
      requestedMatch: !item.requireProducts || reply.products.length > 0,
      resultLimit: reply.products.length <= 3,
      priceBoundary: item.maxPrice === undefined || reply.products.every(product =>
        product.skus.every(sku => sku.currency === 'CNY' && Number(sku.price) <= item.maxPrice)),
      followUp: !item.requireQuestion || /[?？]/.test(answer),
      answerSignal: !item.answerAny || item.answerAny.some(text => answer.includes(text)),
      explicitMonitoringBoundary: !item.requireMonitoringBoundary || reply.products.length === 0
        && reply.answer.includes('不能持续监控') && reply.answer.includes('主动通知') && reply.answer.includes('没有创建'),
      noUnsupportedNudge: !item.answerNone || item.answerNone.every(text => !answer.includes(text)),
      verifiedBasis: verifiedBasis(reply),
      everyTurnBasis: turnResults.every(turn => turn.basisVerified && turn.demoProductCount === 0),
      everyTurnAdviceBoundary: !item.adviceNone || turnResults.every(turn =>
        item.adviceNone.every(text => !turn.advice.includes(text))),
      adviceSeparated: typeof reply.advice === 'string' && reply.advice.length <= 600,
      referenceSelection: !item.selectPosition || !!referenceCodes?.length && codes.length > 0 && codes.every(code => referenceCodes.includes(code)),
      initialDisplayCount: !item.initialCount || initialCount === item.initialCount,
      initialEmpty: !item.initialNoProducts || initialCount === 0,
    }
    const result = { id: item.id, repetition, pass: Object.values(checks).every(Boolean), milliseconds: Math.round(performance.now() - started),
      checks, answer, serverAnswer: reply.answer, advice: reply.advice, basis: reply.recommendationBasis, selectedSkus: codes, turns: item.messages.length, turnResults }
    report.cases.push(result)
    console.log(JSON.stringify({ id: result.id, repetition, pass: result.pass, milliseconds: result.milliseconds,
      selectedSkus: codes, failedChecks: Object.entries(checks).filter(([, passed]) => !passed).map(([name]) => name) }))
  } catch (error) {
    report.cases.push({ id: item.id, repetition, pass: false, failure: error.name === 'TimeoutError' ? 'TIMEOUT' : error.message, turnResults })
    console.log(JSON.stringify({ id: item.id, repetition, pass: false, failure: report.cases.at(-1).failure }))
  }
}
report.passed = report.cases.filter(item => item.pass).length
report.total = report.cases.length
await mkdir(path.dirname(output), { recursive: true })
await writeFile(output, JSON.stringify(report, null, 2) + '\n', 'utf8')
console.log(JSON.stringify({ passed: report.passed, total: report.total, output }))
if (!report.total || report.passed !== report.total) process.exitCode = 1
