import assert from 'node:assert/strict'
import test from 'node:test'
import { canRetryIngestion, needsIngestionPolling, nextIngestionPollingDelay, type IngestionStatus } from './index.ts'

test('only active ingestion states permit cancellation and continued polling', () => {
  const states: IngestionStatus[] = ['QUEUED', 'RUNNING', 'RETRY_WAIT', 'SUCCEEDED', 'FAILED', 'DEAD_LETTER', 'CANCELLED']
  for (const status of states) {
    assert.equal(needsIngestionPolling({ status }), ['QUEUED', 'RUNNING', 'RETRY_WAIT'].includes(status))
    assert.equal(canRetryIngestion({ status }), ['FAILED', 'DEAD_LETTER'].includes(status))
  }
})

test('ingestion polling respects its deadline without granting another full interval', () => {
  assert.equal(nextIngestionPollingDelay(1_000, 1_000), 2_000)
  assert.equal(nextIngestionPollingDelay(1_000, 60_500), 500)
  assert.equal(nextIngestionPollingDelay(1_000, 61_000), null)
  assert.equal(nextIngestionPollingDelay(1_000, 62_000), null)
})
