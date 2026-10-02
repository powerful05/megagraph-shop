import assert from 'node:assert/strict'
import test from 'node:test'
import { beijingLocalToOffsetIso, formatBeijingTime } from './index.ts'

test('Beijing wall times retain the explicit UTC offset regardless of host time zone', () => {
  assert.equal(beijingLocalToOffsetIso('2026-11-01T09:30'), '2026-11-01T09:30:00+08:00')
  assert.equal(Date.parse(beijingLocalToOffsetIso('2026-11-01T09:30')), Date.parse('2026-11-01T01:30:00Z'))
  assert.equal(Date.parse(beijingLocalToOffsetIso('2026-10-03T00:15')), Date.parse('2026-10-02T16:15:00Z'))
})

test('invalid or timezone-bearing wall time input cannot silently normalize', () => {
  for (const value of ['2026-02-29T10:00', '2026-04-31T10:00', '2026-10-03T24:00', '2026-10-03T10:60', '2026-10-03', '2026-10-03T10:00Z', '2026-10-03T10:00+08:00']) {
    assert.throws(() => beijingLocalToOffsetIso(value), Error)
  }
  assert.equal(beijingLocalToOffsetIso('2028-02-29T10:00'), '2028-02-29T10:00:00+08:00')
})

test('UTC and offset representations display the same Beijing appointment', () => {
  assert.equal(formatBeijingTime('2026-10-03T02:30:00Z'), formatBeijingTime('2026-10-03T10:30:00+08:00'))
  assert.match(formatBeijingTime('2026-10-03T02:30:00Z'), /10:30/)
})
