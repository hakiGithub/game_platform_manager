#!/usr/bin/env node

import { readFile, readdir, writeFile } from 'node:fs/promises'
import { dirname, join, relative, resolve, sep } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const scratchRoot = resolve(here, '..')
const repoRoot = resolve(scratchRoot, '..')
const checkOnly = process.argv.includes('--check')

const closureStatuses = new Set(['done', 'resolved', 'closed', 'wontfix', 'cancelled', 'canceled'])
const priorityOrder = { P0: 0, P1: 1, P2: 2, P3: 3 }
const unregisteredStatus = 'open (unregistered)'
const directoryStatus = {
  backlog: 'open (directory)',
  architectureReview: 'open (review)',
}

async function readText(file) {
  return readFile(file, 'utf8')
}

async function findMarkdown(dir, output = []) {
  let entries
  try {
    entries = await readdir(dir, { withFileTypes: true })
  } catch {
    return output
  }

  for (const entry of entries) {
    const fullPath = join(dir, entry.name)
    if (entry.isDirectory()) {
      await findMarkdown(fullPath, output)
    } else if (entry.isFile() && entry.name.endsWith('.md')) {
      output.push(fullPath)
    }
  }
  return output
}

function toPosix(value) {
  return value.split(sep).join('/')
}

function repoRelative(file) {
  return toPosix(relative(repoRoot, file))
}

function relativeFromTickets(file) {
  return toPosix(relative(here, file))
}

function firstMatch(text, pattern) {
  return text.match(pattern)?.[1]?.trim() ?? ''
}

function parseMetadata(text) {
  const metadataLine = (label) => new RegExp(`^\\*{0,2}${label}:\\*{0,2}\\s*(.+)$`, 'im')
  const status = firstMatch(text, metadataLine('Status'))
  const type = firstMatch(text, metadataLine('Type'))
  const priority = firstMatch(text, metadataLine('Priority'))
  const source = firstMatch(text, metadataLine('来源'))
  const blockedBy = firstMatch(text, metadataLine('Blocked by'))
  return { status, type, priority, source, blockedBy }
}

function isClosureStatus(status) {
  const normalized = status.trim()
  if (closureStatuses.has(normalized)) return true
  return /^(done|resolved|closed|wontfix|cancelled|canceled)(?:\b|[（(]|$)/i.test(normalized)
}

function sectionBody(text, heading) {
  const escaped = heading.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const start = text.search(new RegExp(`^##\\s+${escaped}\\s*$`, 'im'))
  if (start < 0) return ''
  const rest = text.slice(start).replace(/^##[^\n]*\n?/, '')
  const nextHeading = rest.search(/^##\s+/m)
  return nextHeading < 0 ? rest.trim() : rest.slice(0, nextHeading).trim()
}

function cleanItem(item) {
  return item
    .replace(/^\s*(?:[-*+]|\d+[.)])\s+/, '')
    .replace(/\[[ xX]\]\s*/, '')
    .replace(/\[([^\]]+)\]\([^)]+\)/g, '$1')
    .replace(/[`*_>#]/g, '')
    .replace(/\s+/g, ' ')
    .trim()
}

function listItems(body) {
  return body
    .split(/\r?\n/)
    .map(cleanItem)
    .filter((item) => item && !item.startsWith('|') && !item.startsWith('---'))
}

function checkboxes(body) {
  return body
    .split(/\r?\n/)
    .filter((line) => /^\s*[-*]\s+\[[ xX]\]/.test(line))
    .map(cleanItem)
}

function firstParagraph(text) {
  return listItems(sectionBody(text, 'Problem Statement'))[0]
    || listItems(sectionBody(text, '现象'))[0]
    || ''
}

function ticketArea(feature) {
  if (feature === 'backlog') return 'Backlog'
  if (feature.startsWith('architecture-review-')) return 'Architecture review'
  if (feature === 'ui-e2e-automation') return 'E2E automation'
  if (feature === 'mattpocock-skills-refresh') return 'Agent workflow'
  if (feature === 'cloud-drive-host-capability') return 'Cloud drive'
  if (feature === 'l4d2-cloud-map-install') return 'L4D2 cloud maps'
  if (feature.startsWith('l4d2-')) return 'L4D2 UI / plugins'
  if (feature === 'dnf-tw-compose-variable-handoff') return 'Deployment'
  return 'Other'
}

function areaOrder(area) {
  return [
    'L4D2 UI / plugins',
    'L4D2 cloud maps',
    'Backlog',
    'E2E automation',
    'Architecture review',
    'Cloud drive',
    'Deployment',
    'Agent workflow',
    'Other',
  ].indexOf(area)
}

function inferStatus(file, feature, metadata) {
  if (metadata.status) {
    return { status: metadata.status, statusSource: 'registered' }
  }
  if (feature === 'backlog') {
    return { status: directoryStatus.backlog, statusSource: 'directory' }
  }
  if (feature.startsWith('architecture-review-')) {
    return { status: directoryStatus.architectureReview, statusSource: 'directory' }
  }
  return { status: unregisteredStatus, statusSource: 'unregistered' }
}

function statusLabel(status) {
  const labels = {
    'needs-triage': '待分诊',
    'needs-info': '待补充信息',
    'ready-for-agent': 'Agent 可执行',
    'ready-for-human': '需要人工处理',
    'open (directory)': '目录内待处理',
    'open (review)': '架构评审待处理',
    'open (unregistered)': '待登记状态',
    done: '已完成',
    resolved: '已解决',
    closed: '已关闭',
    wontfix: '不予处理',
    claimed: '处理中',
  }
  return labels[status] ?? status
}

function statusTone(status) {
  if (/done|resolved|closed/i.test(status)) return 'success'
  if (/wontfix|cancelled/i.test(status)) return 'muted'
  if (/needs-triage/i.test(status)) return 'warning'
  if (/ready-for-agent/i.test(status)) return 'agent'
  if (/ready-for-human/i.test(status)) return 'human'
  if (/claimed/i.test(status)) return 'active'
  return 'open'
}

function normalizePriority(value) {
  const match = value.match(/P[0-3]/i)
  return match ? match[0].toUpperCase() : ''
}

async function readFeatureSpecs(featureDir, feature) {
  const candidates = ['spec.md', 'README.md', 'map.md']
  for (const candidate of candidates) {
    try {
      return {
        path: repoRelative(join(featureDir, candidate)),
        text: await readText(join(featureDir, candidate)),
      }
    } catch {
      // Try the next conventional feature document.
    }
  }
  return null
}

function ticketNumber(title, fileName, relativePath) {
  const fromTitle = firstMatch(title, /^(\d+)\s*[:—-]/)
  if (fromTitle) return fromTitle.padStart(2, '0')
  const fromPath = firstMatch(relativePath, /[/\\](\d+)-[^/\\]+\.md$/i)
  return fromPath.padStart(2, '0')
}

function parseBlockedBy(value) {
  if (!value) return []
  return value
    .split(/[,，]/)
    .map((part) => cleanItem(part))
    .filter(Boolean)
}

function summaryStats(tickets) {
  const byStatus = {}
  const byTone = {}
  for (const ticket of tickets) {
    byStatus[ticket.status] = (byStatus[ticket.status] ?? 0) + 1
    byTone[ticket.statusTone] = (byTone[ticket.statusTone] ?? 0) + 1
  }
  return {
    total: tickets.length,
    unclosed: tickets.filter((ticket) => !ticket.closed).length,
    byStatus,
    byTone,
  }
}

async function build() {
  const markdownFiles = await findMarkdown(scratchRoot)
  const issueFiles = markdownFiles.filter((file) => {
    const rel = toPosix(relative(scratchRoot, file))
    return /(^|\/)issues\/[^/]+\.md$/.test(rel)
  })
  const tickets = []

  for (const file of issueFiles) {
    const relFromScratch = toPosix(relative(scratchRoot, file))
    const segments = relFromScratch.split('/')
    const feature = segments[0]
    const featureDir = join(scratchRoot, feature)
    const text = await readText(file)
    const title = firstMatch(text, /^#\s+(.+)$/m) || file.split(sep).at(-1).replace(/\.md$/, '')
    const metadata = parseMetadata(text)
    const inferred = inferStatus(file, feature, metadata)
    const spec = await readFeatureSpecs(featureDir, feature)
    const acceptanceBody = sectionBody(text, '验收') || sectionBody(text, 'Acceptance')
    const questionsBody = sectionBody(text, '待 triage 确认')
    const commentsBody = sectionBody(text, 'Comments')
    const type = metadata.type || (metadata.priority ? 'task' : '')
    const priority = normalizePriority(metadata.priority)
    const blockedBy = parseBlockedBy(metadata.blockedBy)
    const acceptanceItems = listItems(acceptanceBody)
    const acceptanceChecks = checkboxes(acceptanceBody)
    const questionItems = listItems(questionsBody)
    const comments = commentsBody ? listItems(commentsBody).slice(0, 5) : []
    const closed = isClosureStatus(inferred.status)

    tickets.push({
      id: repoRelative(file),
      title,
      number: ticketNumber(title, file.split(sep).at(-1), relFromScratch),
      feature,
      area: ticketArea(feature),
      status: inferred.status,
      statusLabel: statusLabel(inferred.status),
      statusTone: statusTone(inferred.status),
      statusSource: inferred.statusSource,
      type: type || 'unspecified',
      priority,
      source: metadata.source,
      blockedBy,
      closed,
      summary: firstParagraph(text),
      acceptance: acceptanceItems.slice(0, 8),
      acceptanceChecks,
      questions: questionItems.slice(0, 8),
      comments,
      href: relativeFromTickets(file),
      specHref: spec ? relativeFromTickets(join(repoRoot, spec.path)) : '',
      specPath: spec?.path || '',
      excerpt: text.slice(0, 1200),
    })
  }

  tickets.sort((left, right) => {
    const areaDiff = areaOrder(left.area) - areaOrder(right.area)
    if (areaDiff !== 0) return areaDiff
    const priorityDiff = (priorityOrder[left.priority] ?? 9) - (priorityOrder[right.priority] ?? 9)
    if (priorityDiff !== 0) return priorityDiff
    return left.title.localeCompare(right.title, 'zh-CN')
  })

  const features = [...new Set(tickets.map((ticket) => ticket.feature))]
    .map((feature) => {
      const featureTickets = tickets.filter((ticket) => ticket.feature === feature)
      return {
        id: feature,
        label: ticketArea(feature),
        count: featureTickets.length,
        unclosed: featureTickets.filter((ticket) => !ticket.closed).length,
      }
    })
    .sort((left, right) => areaOrder(left.label) - areaOrder(right.label)
      || left.id.localeCompare(right.id))

  const payload = {
    generatedAt: new Date().toISOString(),
    sourceRoot: '.scratch',
    policy: 'Excluded statuses: done, resolved, closed, wontfix, cancelled. Directory semantics keep backlog and architecture review open.',
    summary: summaryStats(tickets),
    features,
    tickets,
  }

  if (checkOnly) {
    const previous = await readFile(join(here, 'tickets.json'), 'utf8')
    const current = JSON.parse(previous)
    if (stableFingerprint(current) !== stableFingerprint(payload)) {
      throw new Error('tickets.json is stale; rerun node .scratch/tickets/build-tickets.mjs')
    }
    console.log(`Ticket board is current: ${payload.tickets.length} tickets`)
    return
  }

  await writeFile(join(here, 'tickets.json'), JSON.stringify(payload, null, 2), 'utf8')
  await writeFile(join(here, 'index.html'), renderPage(payload), 'utf8')
  const unclosed = payload.tickets.filter((ticket) => !ticket.closed).length
  console.log(`Wrote ${payload.tickets.length} tickets (${unclosed} open) to ${repoRelative(join(here, 'index.html'))}`)
}

function stableFingerprint(data) {
  const copy = {
    ...data,
    generatedAt: '',
    tickets: data.tickets.map((ticket) => ({
      ...ticket,
      excerpt: ticket.excerpt.slice(0, 600),
    })),
  }
  return JSON.stringify(copy)
}

function renderPage(data) {
  return `<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta name="color-scheme" content="dark">
  <title>Game Platform · Ticket Board</title>
  <style>
    :root {
      --bg: #0d171f;
      --panel: #121f29;
      --panel-2: #172832;
      --panel-3: #1c303b;
      --line: #2a414c;
      --line-soft: #223740;
      --text: #e9f1f5;
      --muted: #91a5b1;
      --faint: #6d828e;
      --cyan: #53c8e8;
      --cyan-soft: rgba(83, 200, 232, .13);
      --green: #68d391;
      --green-soft: rgba(104, 211, 145, .13);
      --amber: #f4c36e;
      --amber-soft: rgba(244, 195, 110, .12);
      --red: #f08080;
      --red-soft: rgba(240, 128, 128, .12);
      --violet: #c9a9f5;
      --violet-soft: rgba(201, 169, 245, .12);
      --radius: 6px;
      --mono: ui-monospace, SFMono-Regular, Menlo, Consolas, "Liberation Mono", monospace;
      --sans: -apple-system, BlinkMacSystemFont, "Segoe UI", "Microsoft YaHei", sans-serif;
    }
    * { box-sizing: border-box; }
    html, body { margin: 0; min-height: 100%; }
    body {
      background: var(--bg);
      color: var(--text);
      font: 14px/1.55 var(--sans);
    }
    button, input, select { font: inherit; }
    button { color: inherit; }
    a { color: var(--cyan); text-decoration: none; }
    a:hover { text-decoration: underline; }
    .app { min-height: 100vh; display: grid; grid-template-rows: auto 1fr; }
    .topbar {
      position: sticky;
      top: 0;
      z-index: 20;
      display: flex;
      align-items: center;
      gap: 18px;
      min-height: 64px;
      padding: 13px 24px;
      border-bottom: 1px solid var(--line);
      background: rgba(18, 31, 41, .96);
      backdrop-filter: blur(10px);
    }
    .brand { min-width: 210px; }
    .brand h1 { margin: 0; font-size: 16px; font-weight: 650; }
    .brand p { margin: 3px 0 0; font-size: 11px; color: var(--faint); }
    .search {
      flex: 1 1 400px;
      max-width: 560px;
      position: relative;
    }
    .search input {
      width: 100%;
      padding: 9px 13px 9px 34px;
      border: 1px solid var(--line);
      border-radius: 5px;
      outline: none;
      background: var(--bg);
      color: var(--text);
    }
    .search input:focus { border-color: var(--cyan); box-shadow: 0 0 0 3px var(--cyan-soft); }
    .search::before {
      content: "";
      position: absolute;
      left: 12px;
      top: 11px;
      width: 12px;
      height: 12px;
      border: 1.5px solid var(--faint);
      border-radius: 50%;
    }
    .search::after {
      content: "";
      position: absolute;
      left: 23px;
      top: 23px;
      width: 6px;
      height: 1.5px;
      background: var(--faint);
      transform: rotate(45deg);
    }
    .top-actions { margin-left: auto; display: flex; gap: 8px; align-items: center; }
    .button {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      gap: 6px;
      min-height: 34px;
      padding: 7px 11px;
      border: 1px solid var(--line);
      border-radius: 5px;
      background: var(--panel-2);
      color: var(--muted);
      cursor: pointer;
    }
    .button:hover { color: var(--text); border-color: var(--faint); }
    .button.primary { color: #061a22; background: var(--cyan); border-color: var(--cyan); font-weight: 650; }
    .button.small { min-height: 29px; padding: 4px 9px; font-size: 12px; }
    .layout {
      min-height: 0;
      display: grid;
      grid-template-columns: 242px minmax(0, 1fr);
    }
    .rail {
      position: sticky;
      top: 64px;
      height: calc(100vh - 64px);
      overflow: auto;
      padding: 20px 14px 32px;
      border-right: 1px solid var(--line);
      background: var(--panel);
    }
    .rail-label {
      margin: 0 9px 8px;
      font: 10px/1 var(--mono);
      letter-spacing: .08em;
      text-transform: uppercase;
      color: var(--faint);
    }
    .rail-group { margin-bottom: 20px; }
    .filter {
      width: 100%;
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 8px;
      padding: 8px 9px;
      margin: 2px 0;
      border: 1px solid transparent;
      border-radius: 5px;
      background: transparent;
      color: var(--muted);
      text-align: left;
      cursor: pointer;
    }
    .filter:hover { background: var(--panel-2); color: var(--text); }
    .filter.active { background: var(--cyan-soft); border-color: rgba(83, 200, 232, .5); color: var(--text); }
    .count {
      min-width: 23px;
      padding: 1px 6px;
      border-radius: 999px;
      background: var(--panel-3);
      color: var(--faint);
      font: 10px/18px var(--mono);
      text-align: center;
    }
    .filter.active .count { color: var(--cyan); background: rgba(83, 200, 232, .15); }
    .content { min-width: 0; padding: 23px 28px 64px; }
    .summary-strip {
      display: grid;
      grid-template-columns: repeat(5, minmax(100px, 1fr));
      gap: 1px;
      margin-bottom: 22px;
      border: 1px solid var(--line);
      border-radius: var(--radius);
      overflow: hidden;
      background: var(--line);
    }
    .stat { padding: 15px 16px; background: var(--panel); }
    .stat span { display: block; font-size: 11px; color: var(--faint); }
    .stat strong { display: block; margin-top: 5px; font: 650 22px/1 var(--mono); }
    .stat.open strong { color: var(--amber); }
    .stat.triage strong { color: var(--amber); }
    .stat.work strong { color: var(--cyan); }
    .stat.blocked strong { color: var(--violet); }
    .stat.total strong { color: var(--text); }
    .toolbar {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 8px;
      margin-bottom: 14px;
      padding-bottom: 13px;
      border-bottom: 1px solid var(--line-soft);
    }
    .toolbar h2 { margin: 0 8px 0 0; font-size: 15px; }
    .selection {
      padding: 5px 9px;
      border: 1px solid var(--line);
      border-radius: 4px;
      background: var(--bg);
      color: var(--text);
      outline: none;
    }
    .selection:focus { border-color: var(--cyan); }
    .result-note { margin-left: auto; color: var(--faint); font-size: 12px; }
    .board {
      display: grid;
      gap: 12px;
    }
    .column {
      min-width: 0;
      border: 1px solid var(--line);
      border-radius: var(--radius);
      background: rgba(18, 31, 41, .55);
      overflow: hidden;
    }
    .column-head {
      display: flex;
      align-items: center;
      gap: 8px;
      width: 100%;
      padding: 12px 13px;
      border-bottom: 1px solid var(--line);
      background: var(--panel-2);
      color: inherit;
      cursor: pointer;
      text-align: left;
    }
    .column-head:hover { background: var(--panel-3); }
    .column-head:focus-visible { outline: 2px solid var(--cyan); outline-offset: -2px; }
    .column-head h3 { margin: 0; font-size: 13px; font-weight: 650; }
    .column-head .count { margin-left: auto; }
    .column-caret {
      width: 7px;
      height: 7px;
      margin: 0 2px 3px 3px;
      border-right: 1px solid var(--muted);
      border-bottom: 1px solid var(--muted);
      transform: rotate(45deg);
      transition: transform .15s;
    }
    .column.collapsed .column-caret { margin-bottom: -2px; transform: rotate(-45deg); }
    .column.collapsed .column-body { display: none; }
    .column-body {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(285px, 1fr));
      gap: 9px;
      padding: 10px;
    }
    .ticket-card {
      min-width: 0;
      padding: 12px;
      border: 1px solid var(--line);
      border-left: 3px solid var(--faint);
      border-radius: 5px;
      background: var(--panel);
      cursor: pointer;
      transition: border-color .14s, background .14s, transform .14s;
    }
    .ticket-card:hover { background: var(--panel-2); border-color: var(--faint); transform: translateY(-1px); }
    .ticket-card:focus-visible { outline: 2px solid var(--cyan); outline-offset: 2px; }
    .ticket-card[data-tone="warning"] { border-left-color: var(--amber); }
    .ticket-card[data-tone="agent"] { border-left-color: var(--cyan); }
    .ticket-card[data-tone="human"] { border-left-color: var(--violet); }
    .ticket-card[data-tone="active"] { border-left-color: var(--green); }
    .card-top { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; margin-bottom: 8px; }
    .number { font: 10px/1 var(--mono); color: var(--faint); }
    .tag {
      display: inline-flex;
      align-items: center;
      min-height: 20px;
      padding: 1px 6px;
      border: 1px solid var(--line);
      border-radius: 3px;
      background: var(--panel-3);
      color: var(--muted);
      font-size: 10px;
      line-height: 1.4;
      white-space: nowrap;
    }
    .tag.status.warning { border-color: rgba(244, 195, 110, .45); color: var(--amber); background: var(--amber-soft); }
    .tag.status.agent { border-color: rgba(83, 200, 232, .45); color: var(--cyan); background: var(--cyan-soft); }
    .tag.status.human { border-color: rgba(201, 169, 245, .45); color: var(--violet); background: var(--violet-soft); }
    .tag.status.active, .tag.status.success { border-color: rgba(104, 211, 145, .45); color: var(--green); background: var(--green-soft); }
    .tag.status.muted { color: var(--faint); }
    .tag.type { color: var(--faint); }
    .tag.priority { color: var(--red); border-color: rgba(240, 128, 128, .35); background: var(--red-soft); }
    .tag.blocked { color: var(--violet); border-color: rgba(201, 169, 245, .35); background: var(--violet-soft); }
    .card-title { margin: 0; color: var(--text); font-size: 13px; font-weight: 600; line-height: 1.45; overflow-wrap: anywhere; }
    .card-summary {
      display: -webkit-box;
      -webkit-line-clamp: 2;
      -webkit-box-orient: vertical;
      overflow: hidden;
      margin: 7px 0 0;
      color: var(--muted);
      font-size: 11px;
      line-height: 1.55;
    }
    .card-meta { display: flex; gap: 7px; flex-wrap: wrap; margin-top: 9px; color: var(--faint); font-size: 10px; }
    .card-meta span { overflow-wrap: anywhere; }
    .empty { padding: 28px 14px; color: var(--faint); text-align: center; }
    .drawer-wrap {
      position: fixed;
      inset: 0;
      z-index: 40;
      display: none;
    }
    .drawer-wrap.open { display: block; }
    .backdrop { position: absolute; inset: 0; background: rgba(2, 9, 14, .62); }
    .drawer {
      position: absolute;
      top: 0;
      right: 0;
      width: min(700px, 92vw);
      height: 100%;
      overflow: auto;
      border-left: 1px solid var(--line);
      background: var(--panel);
      box-shadow: -18px 0 42px rgba(0, 0, 0, .3);
    }
    .drawer-head { position: sticky; top: 0; z-index: 1; padding: 17px 22px; border-bottom: 1px solid var(--line); background: var(--panel); }
    .drawer-head .eyebrow { margin-bottom: 7px; color: var(--faint); font: 10px/1 var(--mono); }
    .drawer-head h2 { margin: 0; padding-right: 38px; font-size: 19px; line-height: 1.35; overflow-wrap: anywhere; }
    .close {
      position: absolute;
      top: 15px;
      right: 16px;
      width: 31px;
      height: 31px;
      border: 1px solid var(--line);
      border-radius: 5px;
      background: var(--panel-2);
      color: var(--muted);
      cursor: pointer;
      font-size: 17px;
    }
    .close:hover { color: var(--text); border-color: var(--faint); }
    .drawer-body { padding: 18px 22px 40px; }
    .tag-row { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 16px; }
    .detail-grid { display: grid; grid-template-columns: 104px minmax(0, 1fr); gap: 10px 12px; margin: 0 0 18px; }
    .detail-grid dt { color: var(--faint); font: 10px/1.7 var(--mono); text-transform: uppercase; }
    .detail-grid dd { margin: 0; color: var(--muted); font-size: 12px; overflow-wrap: anywhere; }
    .detail-grid code { padding: 1px 4px; border: 1px solid var(--line); border-radius: 3px; background: var(--bg); font: 11px var(--mono); }
    .section { margin-top: 18px; }
    .section h3 { margin: 0 0 8px; padding-bottom: 7px; border-bottom: 1px solid var(--line-soft); font-size: 13px; }
    .section p { margin: 0; color: var(--muted); font-size: 12px; line-height: 1.7; }
    .section ul { margin: 0; padding-left: 18px; color: var(--muted); font-size: 12px; }
    .section li { margin: 4px 0; }
    .section li::marker { color: var(--faint); }
    .section a { font-size: 12px; }
    .excerpt {
      max-height: 230px;
      overflow: auto;
      margin: 0;
      padding: 12px;
      border: 1px solid var(--line);
      border-radius: 4px;
      background: var(--bg);
      color: var(--muted);
      font: 11px/1.65 var(--mono);
      white-space: pre-wrap;
      overflow-wrap: anywhere;
    }
    .notice { margin: 0 0 18px; padding: 10px 12px; border: 1px solid rgba(244, 195, 110, .3); border-radius: 4px; background: var(--amber-soft); color: var(--amber); font-size: 11px; }
    .sr-only { position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; border: 0; }
    @media (max-width: 1180px) {
      .summary-strip { grid-template-columns: repeat(3, minmax(100px, 1fr)); }
    }
    @media (max-width: 780px) {
      .topbar { flex-wrap: wrap; gap: 10px; padding: 12px 16px; }
      .brand { min-width: 0; flex: 1; }
      .brand p { display: none; }
      .top-actions { margin-left: 0; }
      .search { order: 3; flex-basis: 100%; max-width: none; }
      .layout { display: block; }
      .rail { position: static; height: auto; overflow: visible; padding: 12px 16px; border-right: 0; border-bottom: 1px solid var(--line); }
      .rail-group { margin-bottom: 10px; }
      .rail .filter { display: inline-flex; width: auto; max-width: 100%; min-height: 31px; margin: 2px; }
      .rail .feature-filter { max-width: calc(100% - 4px); }
      .rail .feature-filter > span:first-child { overflow-wrap: anywhere; }
      .rail-label { display: none; }
      .content { padding: 16px 14px 48px; }
      .summary-strip { grid-template-columns: repeat(2, minmax(100px, 1fr)); }
      .board { grid-template-columns: 1fr; }
      .column-body { grid-template-columns: 1fr; }
      .result-note { width: 100%; margin-left: 0; }
      .drawer { width: 100%; }
      .detail-grid { grid-template-columns: 84px minmax(0, 1fr); }
    }
    @media (hover: none) {
      .ticket-card:hover { transform: none; }
    }
    @media (max-width: 430px) {
      .summary-strip { grid-template-columns: 1fr 1fr; }
      .stat { padding: 12px; }
      .stat strong { font-size: 19px; }
      .toolbar h2 { width: 100%; }
    }
  </style>
</head>
<body>
  <div class="app">
    <header class="topbar">
      <div class="brand">
        <h1>Ticket Board</h1>
        <p>本地 issue tracker · 未完成票视图</p>
      </div>
      <label class="search">
        <span class="sr-only">搜索 ticket</span>
        <input id="search" type="search" autocomplete="off" placeholder="搜索标题、编号、状态、来源、标签……">
      </label>
      <div class="top-actions">
        <button class="button small" id="refresh" title="重新读取最新生成的 ticket 数据">刷新数据</button>
        <button class="button small" id="clear" title="清除搜索与筛选">清除筛选</button>
      </div>
    </header>
    <main class="layout">
      <aside class="rail" aria-label="Ticket 筛选">
        <div class="rail-group">
          <div class="rail-label">视图</div>
          <button class="filter active" data-filter="open" data-value="all">全部未完成 <span class="count" id="open-count"></span></button>
          <button class="filter" data-filter="triage" data-value="all">待分诊 <span class="count" id="triage-count"></span></button>
          <button class="filter" data-filter="work" data-value="all">可执行 / 处理中 <span class="count" id="work-count"></span></button>
          <button class="filter" data-filter="all" data-value="all">全部 issue <span class="count" id="all-count"></span></button>
        </div>
        <div class="rail-group">
          <div class="rail-label">项目域</div>
          <button class="filter active" data-filter="feature" data-value="all">全部项目 <span class="count" id="feature-all-count"></span></button>
          <div id="feature-filters"></div>
        </div>
        <div class="rail-group">
          <div class="rail-label">状态来源</div>
          <button class="filter" data-filter="source" data-value="registered">显式 Status <span class="count" id="source-registered-count"></span></button>
          <button class="filter" data-filter="source" data-value="directory">目录语义 <span class="count" id="source-directory-count"></span></button>
          <button class="filter" data-filter="source" data-value="unregistered">待登记 <span class="count" id="source-unregistered-count"></span></button>
        </div>
      </aside>
      <section class="content">
        <div class="summary-strip" aria-label="Ticket 概览">
          <div class="stat open"><span>未完成</span><strong id="stat-open"></strong></div>
          <div class="stat triage"><span>待分诊</span><strong id="stat-triage"></strong></div>
          <div class="stat work"><span>可执行 / 处理中</span><strong id="stat-work"></strong></div>
          <div class="stat blocked"><span>有依赖</span><strong id="stat-blocked"></strong></div>
          <div class="stat total"><span>全部 issue</span><strong id="stat-total"></strong></div>
        </div>
        <div class="toolbar">
          <h2 id="result-title">未完成的 ticket</h2>
          <select class="selection" id="sort" aria-label="排序">
            <option value="area">按项目域</option>
            <option value="priority">按优先级</option>
            <option value="number">按票号</option>
            <option value="recent">按文件深度</option>
          </select>
          <span class="result-note" id="result-note"></span>
        </div>
        <div class="board" id="board"></div>
      </section>
    </main>
  </div>
  <div class="drawer-wrap" id="drawer-wrap" aria-hidden="true">
    <div class="backdrop" id="backdrop"></div>
    <aside class="drawer" role="dialog" aria-modal="true" aria-labelledby="drawer-title">
      <div class="drawer-head">
        <div class="eyebrow" id="drawer-eyebrow"></div>
        <h2 id="drawer-title"></h2>
        <button class="close" id="drawer-close" aria-label="关闭详情">×</button>
      </div>
      <div class="drawer-body" id="drawer-body"></div>
    </aside>
  </div>
  <script id="ticket-data" type="application/json">${escapeJsonForScript(data)}</script>
  <script>
    (function () {
      'use strict';
      const payload = JSON.parse(document.getElementById('ticket-data').textContent);
      const state = { view: 'open', feature: 'all', source: 'all', search: '', sort: 'area', expanded: null, columns: {} };
      const columnOrder = [
        { key: 'triage', label: '待分诊', tones: ['warning'] },
        { key: 'agent', label: 'Agent 可执行', tones: ['agent'] },
        { key: 'human', label: '需要人工处理', tones: ['human'] },
        { key: 'open', label: '目录内待处理', tones: ['open'] },
        { key: 'active', label: '处理中', tones: ['active'] },
      ];
      const $ = (selector) => document.querySelector(selector);
      const esc = (value) => String(value ?? '').replace(/[&<>"']/g, (char) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[char]));
      const openTickets = () => payload.tickets.filter((ticket) => !ticket.closed);
      const textMatches = (ticket, query) => {
        if (!query) return true;
        const haystack = [ticket.title, ticket.id, ticket.feature, ticket.area, ticket.status, ticket.statusLabel, ticket.type, ticket.source, ticket.summary, ...ticket.blockedBy].join(' ').toLowerCase();
        return query.toLowerCase().split(/\s+/).every((part) => haystack.includes(part));
      };
      function viewMatches(ticket) {
        if (state.search && !textMatches(ticket, state.search)) return false;
        if (state.feature !== 'all' && ticket.feature !== state.feature) return false;
        if (state.source !== 'all' && ticket.statusSource !== state.source) return false;
        if (state.view === 'open') return !ticket.closed;
        if (state.view === 'triage') return ticket.statusTone === 'warning';
        if (state.view === 'work') return ticket.statusTone === 'agent' || ticket.statusTone === 'human' || ticket.statusTone === 'active';
        return true;
      }
      function sorted(tickets) {
        const copy = tickets.slice();
        if (state.sort === 'priority') {
          const priority = { P0: 0, P1: 1, P2: 2, P3: 3 };
          copy.sort((a, b) => (priority[a.priority] ?? 9) - (priority[b.priority] ?? 9) || a.title.localeCompare(b.title, 'zh-CN'));
        } else if (state.sort === 'number') {
          copy.sort((a, b) => a.number.localeCompare(b.number) || a.area.localeCompare(b.area));
        } else if (state.sort === 'recent') {
          copy.sort((a, b) => b.id.split('/').length - a.id.split('/').length || a.title.localeCompare(b.title, 'zh-CN'));
        }
        return copy;
      }
      function toneFor(ticket) {
        if (ticket.statusTone === 'warning') return 'warning';
        if (ticket.statusTone === 'agent') return 'agent';
        if (ticket.statusTone === 'human') return 'human';
        if (ticket.statusTone === 'active') return 'active';
        return 'open';
      }
      function statusTag(ticket) {
        return '<span class="tag status ' + esc(ticket.statusTone) + '">' + esc(ticket.statusLabel) + '</span>';
      }
      function card(ticket) {
        const tags = [statusTag(ticket)];
        if (ticket.priority) tags.push('<span class="tag priority">' + esc(ticket.priority) + '</span>');
        if (ticket.blockedBy.length) tags.push('<span class="tag blocked">依赖 ' + esc(ticket.blockedBy.join(',')) + '</span>');
        return '<article class="ticket-card" data-id="' + esc(ticket.id) + '" data-tone="' + toneFor(ticket) + '" tabindex="0" role="button" aria-label="查看 ' + esc(ticket.title) + '">'
          + '<div class="card-top"><span class="number">' + esc(ticket.number) + '</span>' + tags.join('') + '</div>'
          + '<h4 class="card-title">' + esc(ticket.title) + '</h4>'
          + (ticket.summary ? '<p class="card-summary">' + esc(ticket.summary) + '</p>' : '')
          + '<div class="card-meta"><span>' + esc(ticket.area) + '</span><span>·</span><span>' + esc(ticket.type) + '</span><span>·</span><span>' + esc(ticket.feature) + '</span></div>'
          + '</article>';
      }
      function renderFilters() {
        const searchMatched = payload.tickets.filter((ticket) => textMatches(ticket, state.search));
        const features = payload.features
          .map((feature) => ({
            ...feature,
            visibleCount: searchMatched.filter((ticket) => ticket.feature === feature.id).length,
          }))
          .filter((feature) => feature.visibleCount > 0 || state.feature === feature.id);
        $('#feature-filters').innerHTML = features.map((feature) =>
          '<button class="filter feature-filter' + (state.feature === feature.id ? ' active' : '') + '" data-filter="feature" data-value="' + esc(feature.id) + '" title="' + esc(feature.id) + '">'
          + '<span>' + esc(feature.label) + '</span><span class="count">' + feature.visibleCount + '</span></button>'
        ).join('');
        document.querySelectorAll('.filter').forEach((button) => {
          button.classList.toggle('active', button.dataset.filter === state.view
            || button.dataset.filter === state.feature && button.dataset.value === state.feature
            || button.dataset.filter === state.source && button.dataset.value === state.source);
          if (button.dataset.filter === 'feature' && button.dataset.value === 'all') button.classList.toggle('active', state.feature === 'all');
          if (button.dataset.filter === 'source' && button.dataset.value === 'all') button.classList.toggle('active', state.source === 'all');
        });
        const all = payload.tickets;
        const open = openTickets();
        $('#open-count').textContent = open.length;
        $('#triage-count').textContent = open.filter((ticket) => ticket.statusTone === 'warning').length;
        $('#work-count').textContent = open.filter((ticket) => ['agent', 'human', 'active'].includes(ticket.statusTone)).length;
        $('#all-count').textContent = all.length;
        $('#feature-all-count').textContent = searchMatched.length;
        const sourceCandidates = searchMatched.filter((ticket) => state.feature === 'all' || ticket.feature === state.feature);
        $('#source-registered-count').textContent = sourceCandidates.filter((ticket) => ticket.statusSource === 'registered').length;
        $('#source-directory-count').textContent = sourceCandidates.filter((ticket) => ticket.statusSource === 'directory').length;
        $('#source-unregistered-count').textContent = sourceCandidates.filter((ticket) => ticket.statusSource === 'unregistered').length;
        $('#stat-open').textContent = open.length;
        $('#stat-triage').textContent = open.filter((ticket) => ticket.statusTone === 'warning').length;
        $('#stat-work').textContent = open.filter((ticket) => ['agent', 'human', 'active'].includes(ticket.statusTone)).length;
        $('#stat-blocked').textContent = open.filter((ticket) => ticket.blockedBy.length).length;
        $('#stat-total').textContent = all.length;
      }
      function renderFiltersToolbar() {
        document.querySelectorAll('.filter').forEach((button) => {
          const isMainView = button.dataset.filter === state.view;
          const isFeature = button.dataset.filter === 'feature' && button.dataset.value === state.feature;
          const isSource = button.dataset.filter === 'source' && button.dataset.value === state.source;
          button.classList.toggle('active', isMainView || isFeature || isSource);
        });
      }
      function renderBoard() {
        const visible = sorted(payload.tickets.filter(viewMatches));
        const groups = new Map(columnOrder.map((column) => [column.key, []]));
        for (const ticket of visible) {
          const key = toneFor(ticket);
          if (groups.has(key)) groups.get(key).push(ticket);
          else groups.get('open').push(ticket);
        }
        $('#board').innerHTML = columnOrder.map((column) => {
          const items = groups.get(column.key);
          const collapsed = state.columns[column.key] === true;
          return '<section class="column' + (collapsed ? ' collapsed' : '') + '"><button class="column-head" data-column="' + column.key + '" aria-expanded="' + String(!collapsed) + '"><span class="column-caret" aria-hidden="true"></span><h3>' + column.label + '</h3><span class="count">' + items.length + '</span></button>'
            + '<div class="column-body">' + (items.length ? items.map(card).join('') : '<div class="empty">当前筛选下没有 ticket</div>') + '</div></section>';
        }).join('');
        document.querySelectorAll('.column-head').forEach((element) => {
          element.addEventListener('click', () => {
            const key = element.dataset.column;
            state.columns[key] = !state.columns[key];
            renderBoard();
          });
        });
        const titleText = state.view === 'open' ? '未完成的 ticket' : state.view === 'triage' ? '待分诊的 ticket' : state.view === 'work' ? '可执行 / 处理中的 ticket' : '全部 issue';
        $('#result-title').textContent = titleText;
        $('#result-note').textContent = '显示 ' + visible.length + ' / ' + payload.tickets.length + ' 张 · 生成于 ' + new Date(payload.generatedAt).toLocaleString('zh-CN');
        document.querySelectorAll('.ticket-card').forEach((element) => {
          element.addEventListener('click', () => openTicket(element.dataset.id));
          element.addEventListener('keydown', (event) => {
            if (event.key === 'Enter' || event.key === ' ') {
              event.preventDefault();
              openTicket(element.dataset.id);
            }
          });
        });
        renderFilters();
      }
      function detail(ticket) {
        const statusSource = ticket.statusSource === 'registered' ? '文件内 Status 行' : ticket.statusSource === 'directory' ? '目录约定（未写 Status）' : '未登记';
        const tags = [statusTag(ticket), '<span class="tag type">' + esc(ticket.type) + '</span>'];
        if (ticket.priority) tags.push('<span class="tag priority">' + esc(ticket.priority) + '</span>');
        if (ticket.blockedBy.length) tags.push('<span class="tag blocked">Blocked by ' + esc(ticket.blockedBy.join(', ')) + '</span>');
        const acceptance = ticket.acceptance.length
          ? '<ul>' + ticket.acceptance.map((item) => '<li>' + esc(item) + '</li>').join('') + '</ul>'
          : '<p>票内没有提取到验收条目。</p>';
        const questions = ticket.questions.length
          ? '<ul>' + ticket.questions.map((item) => '<li>' + esc(item) + '</li>').join('') + '</ul>'
          : '';
        const comments = ticket.comments.length
          ? '<div class="section"><h3>最近评论</h3><ul>' + ticket.comments.map((item) => '<li>' + esc(item) + '</li>').join('') + '</ul></div>'
          : '';
        return '<div class="tag-row">' + tags.join('') + '</div>'
          + '<dl class="detail-grid">'
          + '<dt>状态</dt><dd>' + esc(ticket.status) + ' · ' + esc(statusSource) + '</dd>'
          + '<dt>项目域</dt><dd>' + esc(ticket.area) + ' / <code>' + esc(ticket.feature) + '</code></dd>'
          + '<dt>来源</dt><dd>' + (ticket.source ? esc(ticket.source) : '未登记') + '</dd>'
          + '<dt>文件</dt><dd><code>' + esc(ticket.id) + '</code></dd>'
          + (ticket.specPath ? '<dt>Feature 文档</dt><dd><code>' + esc(ticket.specPath) + '</code></dd>' : '')
          + '</dl>'
          + (ticket.statusSource !== 'registered' ? '<div class="notice">这张票没有显式 Status 行，当前状态来自目录约定或“待登记”。处理前建议补齐 Status，页面才能在刷新后准确分栏。</div>' : '')
          + '<div class="section"><h3>摘要</h3><p>' + (ticket.summary ? esc(ticket.summary) : '票内没有摘要段落。') + '</p></div>'
          + '<div class="section"><h3>验收</h3>' + acceptance + '</div>'
          + (questions ? '<div class="section"><h3>待确认</h3>' + questions + '</div>' : '')
          + comments
          + '<div class="section"><h3>原文线索</h3><pre class="excerpt">' + esc(ticket.excerpt) + '</pre></div>'
          + '<div class="section"><h3>打开文件</h3><p><a href="' + esc(ticket.href) + '">查看 Markdown 原文</a>'
          + (ticket.specHref ? ' · <a href="' + esc(ticket.specHref) + '">查看 feature 文档</a>' : '')
          + '</p></div>';
      }
      function openTicket(id) {
        const ticket = payload.tickets.find((item) => item.id === id);
        if (!ticket) return;
        state.expanded = id;
        $('#drawer-eyebrow').textContent = ticket.area + ' · ' + ticket.number + ' · ' + ticket.statusLabel;
        $('#drawer-title').textContent = ticket.title;
        $('#drawer-body').innerHTML = detail(ticket);
        $('#drawer-wrap').classList.add('open');
        $('#drawer-wrap').setAttribute('aria-hidden', 'false');
        $('#drawer-close').focus();
      }
      function closeDrawer() {
        state.expanded = null;
        $('#drawer-wrap').classList.remove('open');
        $('#drawer-wrap').setAttribute('aria-hidden', 'true');
      }
      function setFilter(filter, value) {
        if (filter === 'feature') state.feature = value;
        else if (filter === 'source') state.source = value;
        else state.view = value;
        renderBoard();
      }
      $('.rail').addEventListener('click', (event) => {
        const button = event.target.closest('.filter');
        if (button) setFilter(button.dataset.filter, button.dataset.value);
      });
      $('#search').addEventListener('input', (event) => { state.search = event.target.value.trim(); renderBoard(); });
      $('#sort').addEventListener('change', (event) => { state.sort = event.target.value; renderBoard(); });
      $('#clear').addEventListener('click', () => {
        state.view = 'open'; state.feature = 'all'; state.source = 'all'; state.search = ''; state.sort = 'area';
        $('#search').value = ''; $('#sort').value = 'area'; renderBoard();
      });
      $('#refresh').addEventListener('click', () => window.location.reload());
      $('#drawer-close').addEventListener('click', closeDrawer);
      $('#backdrop').addEventListener('click', closeDrawer);
      document.addEventListener('keydown', (event) => { if (event.key === 'Escape') closeDrawer(); });
      renderBoard();
    })();
  </script>
</body>
</html>
`
}

function escapeJsonForScript(value) {
  return JSON.stringify(value).replace(/</g, '\\u003c').replace(/>/g, '\\u003e').replace(/&/g, '\\u0026')
}

await build()
