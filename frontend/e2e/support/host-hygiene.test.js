import assert from 'node:assert/strict'
import { describe, it } from 'node:test'
import { evaluateGithubHttpsProbe } from './host-hygiene.js'

describe('evaluateGithubHttpsProbe', () => {
  it('rejects an HTTP status when curl exits nonzero', () => {
    const result = evaluateGithubHttpsProbe({
      ok: false,
      stdout: '301\nexit=60',
      stderr: 'curl: (60) SSL certificate problem',
    })

    assert.equal(result.reachable, false)
    assert.equal(result.httpCode, null)
    assert.equal(result.exitCode, 60)
    assert.match(result.actual, /http=301/)
    assert.match(result.actual, /exit=60/)
    assert.match(result.actual, /SSL certificate problem/)
  })

  it('accepts a redirect after a clean curl exit', () => {
    const result = evaluateGithubHttpsProbe({
      ok: true,
      stdout: '301\nexit=0',
      stderr: '',
    })

    assert.equal(result.reachable, true)
    assert.equal(result.httpCode, '301')
    assert.equal(result.exitCode, 0)
  })

  it('rejects malformed output without a transport exit status', () => {
    const result = evaluateGithubHttpsProbe({
      ok: true,
      stdout: '200',
      stderr: '',
    })

    assert.equal(result.reachable, false)
    assert.equal(result.exitCode, null)
  })
})
