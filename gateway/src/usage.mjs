import { createHash } from 'node:crypto';

const number = value => typeof value === 'number' && Number.isFinite(value) ? value : null;
const iso = value => typeof value === 'string' && Number.isFinite(Date.parse(value)) ? new Date(value).toISOString() : null;
const list = value => Array.isArray(value) ? value : [];
const text = (value, fallback) => typeof value === 'string' && value.trim() ? value.trim().slice(0, 70) : fallback;
const providerNames = { codex: 'Codex', claude: 'Claude', cursor: 'Cursor', gemini: 'Gemini', opencode: 'OpenCode',
  copilot: 'GitHub Copilot', 'github-copilot': 'GitHub Copilot', openrouter: 'OpenRouter', deepseek: 'DeepSeek' };
const id = parts => createHash('sha256').update(JSON.stringify(parts)).digest('base64url').slice(0, 22);
const isOld = (value, now, budget) => !value || now - Date.parse(value) > budget;

function totals(period) {
  return { tokens: Number.isSafeInteger(period?.totalTokens) ? period.totalTokens : null, costUsd: number(period?.costUsd), unpricedTokens: Number.isSafeInteger(period?.unpricedTokens) ? period.unpricedTokens : null };
}

export function projectStats(input, now = Date.now()) {
  if (!input || typeof input !== 'object' || Array.isArray(input) || !input.periods) {
    throw new Error('Unexpected Hub stats shape');
  }
  const upstreamAt = iso(input.updatedAt);
  const freshnessMs = Math.trunc(Math.min(7 * 86400000, Math.max(60000, number(input.staleAfterMs) ?? 1800000)));
  const readings = [];
  const duplicates = new Map();
  for (const account of list(input.limits?.providers).slice(0, 120)) {
    if (!account || typeof account !== 'object') continue;
    const provider = text(account.provider, 'Unknown provider');
    const label = text(account.providerLabel, Object.hasOwn(providerNames, provider) ? providerNames[provider] : provider);
    const accountName = text(account.accountName ?? account.accountLabel, null);
    const accountId = account.accountKey ?? account.accountId ?? account.id ?? accountName ?? '';
    const observedAt = iso(account.updatedAt) ?? iso(input.limits?.updatedAt);
    const windows = list(account.windows).slice(0, 24).filter(window => window && typeof window === 'object');
    if ((account.balance || number(account.balanceUsd) !== null) && !windows.some(window => window.metric === 'credits')) {
      windows.push({ kind: 'balance', metric: 'credits', label: 'Balance', remaining: account.balance?.amount ?? account.balanceUsd, resetsAt: account.balance?.expiresAt,
        currency: account.balance?.currency ?? (number(account.balanceUsd) !== null ? 'USD' : null), unlimited: account.balance?.unlimited, boundaryKind: 'expiry' });
    }
    for (const window of windows) {
      const windowLabel = text(window.label, text(window.kind, 'Quota'));
      const kind = window.metric === 'credits' ? 'balance' : window.metric === 'spend' ? 'spend' : 'quota';
      const identity = [provider, accountId, kind, window.limitId ?? window.kind ?? windowLabel, window.limitId ? null : windowLabel];
      const identityKey = JSON.stringify(identity);
      const index = duplicates.get(identityKey) ?? 0;
      duplicates.set(identityKey, index + 1);
      const used = number(window.usedPercent);
      const pct = number(window.remainingPercent) ?? (used === null ? null : 100 - used);
      const boundaryAt = iso(window.resetsAt ?? window.expiresAt);
      readings.push({
        id: id([...identity, index]), provider: label, account: accountName, label: windowLabel, kind,
        remainingPercent: kind === 'quota' && pct !== null ? Math.max(0, Math.min(100, pct)) : null,
        amount: kind === 'spend' ? number(window.used) : number(window.remaining), limit: number(window.limit),
        currency: text(window.currency, null),
        boundaryAt, boundaryKind: window.boundaryKind === 'expiry' || window.boundaryKind === 'mixed' ? window.boundaryKind : 'reset',
        observedAt, stale: account.stale === true || (account.status != null && account.status !== 'ok') || isOld(observedAt, now, freshnessMs) ||
          (boundaryAt !== null && Date.parse(boundaryAt) <= now),
        unlimited: window.unlimited === true,
      });
    }
  }
  return {
    schema: 1, freshnessMs, retrievedAt: new Date(now).toISOString(), upstreamAt, stale: isOld(upstreamAt, now, freshnessMs),
    today: totals(input.periods.today), month: totals(input.periods.month), readings,
  };
}

export function forWatch(snapshot, pinnedId) {
  const pinned = snapshot.readings.find(reading => reading.id === pinnedId);
  const ordered = [...snapshot.readings].sort((a, b) =>
    Number(a.stale) - Number(b.stale) || (a.remainingPercent ?? 101) - (b.remainingPercent ?? 101)
  );
  const quota = ordered.find(reading => reading.kind === 'quota');
  const balance = ordered.find(reading => reading.kind === 'balance');
  const selected = [...new Map([pinned, quota, balance].filter(Boolean).map(reading => [reading.id, reading])).values()];
  return { ...snapshot, readings: selected };
}

export function usageCache(readUpstream, { clock = Date.now, maxAge = 30_000 } = {}) {
  let last, pending, expires = 0;
  return async function read() {
    if (clock() < expires && last) return last;
    if (!pending) {
      pending = (async () => {
        try {
          last = projectStats(await readUpstream(), clock());
          expires = clock() + maxAge;
          return last;
        } catch (error) {
          if (!last) throw error;
          return { ...last, stale: true, readings: last.readings.map(row => ({ ...row, stale: true })) };
        } finally {
          pending = undefined;
        }
      })();
    }
    return pending;
  };
}
