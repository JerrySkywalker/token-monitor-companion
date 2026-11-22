import { createHash, randomBytes, randomUUID } from 'node:crypto';
import { mkdirSync } from 'node:fs';
import { dirname } from 'node:path';
import { DatabaseSync } from 'node:sqlite';

const digest = value => createHash('sha256').update(value).digest('hex');
export const watchIdPattern = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;
export const defaultConfig = { mode: 'PHONE', pinnedId: null, paused: false, refreshMinutes: 30, refreshRequest: 0 };

export function openStore(file) {
  if (file !== ':memory:') mkdirSync(dirname(file), { recursive: true });
  const db = new DatabaseSync(file);
  db.exec('PRAGMA busy_timeout = 5000; PRAGMA journal_mode = WAL;');
  db.exec(
    'CREATE TABLE IF NOT EXISTS pairing (hash TEXT PRIMARY KEY, kind TEXT NOT NULL, watch_id TEXT, expires INTEGER NOT NULL);' +
    'CREATE TABLE IF NOT EXISTS devices (id TEXT PRIMARY KEY, hash TEXT UNIQUE NOT NULL, kind TEXT NOT NULL, label TEXT NOT NULL, watch_id TEXT);' +
    'CREATE TABLE IF NOT EXISTS watches (id TEXT PRIMARY KEY, label TEXT NOT NULL, version INTEGER NOT NULL, config TEXT NOT NULL, report TEXT);'
  );

  const watchById = db.prepare('SELECT * FROM watches WHERE id = ?');
  const createWatch = db.prepare(
    'INSERT INTO watches (id, label, version, config) VALUES (?, ?, 0, ?) ON CONFLICT(id) DO NOTHING'
  );
  function watchView(row) {
    if (!row) return null;
    return { id: row.id, label: row.label, version: row.version, ...JSON.parse(row.config), report: JSON.parse(row.report ?? 'null') };
  }
  function transaction(action) {
    db.exec('BEGIN IMMEDIATE');
    try {
      const result = action();
      db.exec('COMMIT');
      return result;
    } catch (error) {
      db.exec('ROLLBACK');
      throw error;
    }
  }
  return {
    close: () => db.close(),
    issue(kind, watchId = null, now = Date.now()) {
      const code = randomBytes(9).toString('hex').toUpperCase();
      const expires = now + 5 * 60_000;
      return transaction(() => {
        db.prepare('DELETE FROM pairing WHERE expires <= ?').run(now);
        db.prepare('INSERT INTO pairing VALUES (?, ?, ?, ?)').run(digest(code), kind, watchId, expires);
        return { code, expires };
      });
    },
    redeem(code, requestedKind, label, installationId, now = Date.now()) {
      return transaction(() => {
        const key = digest(code.toUpperCase());
        const pair = db.prepare('SELECT * FROM pairing WHERE hash = ? AND expires > ?').get(key, now);
        if (!pair || pair.kind !== requestedKind) return null;
        const watchId = pair.kind === 'wear' ? installationId : null;
        if (pair.kind === 'wear' && (!watchIdPattern.test(watchId ?? '') || (pair.watch_id && pair.watch_id !== watchId))) return null;
        db.prepare('DELETE FROM pairing WHERE hash = ?').run(key);
        if (watchId) createWatch.run(watchId, label, JSON.stringify({ ...defaultConfig, mode: 'DIRECT' }));
        const id = randomUUID();
        const token = randomBytes(32).toString('base64url');
        if (watchId) db.prepare('DELETE FROM devices WHERE watch_id = ?').run(watchId);
        db.prepare('INSERT INTO devices VALUES (?, ?, ?, ?, ?)').run(id, digest(token), pair.kind, label, watchId);
        return { id, token, kind: pair.kind, watchId };
      });
    },
    device(token) {
      if (!token || token.length !== 43) return null;
      const row = db.prepare('SELECT id, kind, label, watch_id FROM devices WHERE hash = ?').get(digest(token));
      return row ? { id: row.id, kind: row.kind, label: row.label, watchId: row.watch_id } : null;
    },
    devices() {
      return db.prepare('SELECT id, kind, label, watch_id AS watchId FROM devices ORDER BY label').all();
    },
    revoke(id) {
      return db.prepare('DELETE FROM devices WHERE id = ?').run(id).changes > 0;
    },
    revokeWatch(id) {
      return transaction(() => {
        const removed = db.prepare('DELETE FROM watches WHERE id = ?').run(id).changes > 0;
        db.prepare('DELETE FROM devices WHERE watch_id = ?').run(id);
        db.prepare('DELETE FROM pairing WHERE watch_id = ?').run(id);
        return removed;
      });
    },
    registerWatch(id, label) {
      createWatch.run(id, label, JSON.stringify(defaultConfig));
      return watchView(watchById.get(id));
    },
    watch(id) { return watchView(watchById.get(id)); },
    watches() {
      return db.prepare('SELECT * FROM watches ORDER BY label, id').all().map(watchView);
    },
    updateWatch(id, expectedVersion, config) {
      const changed = db.prepare('UPDATE watches SET version = version + 1, config = ? WHERE id = ? AND version = ?')
        .run(JSON.stringify(config), id, expectedVersion);
      return { updated: changed.changes > 0, watch: watchView(watchById.get(id)) };
    },
    report(id, report) {
      return transaction(() => {
        const existing = watchView(watchById.get(id));
        if (!existing || report.appliedVersion > existing.version) return null;
        const prior = existing.report;
        if (!prior || (report.appliedVersion >= prior.appliedVersion && report.sequence > prior.sequence)) {
          db.prepare('UPDATE watches SET report = ? WHERE id = ?')
            .run(JSON.stringify({ ...report, receivedAt: new Date().toISOString() }), id);
        }
        return watchView(watchById.get(id));
      });
    },
  };
}
