#!/usr/bin/env node
/**
 * PixelPlayer Connect — LAN session broker.
 *
 * The Spotify-Connect-style control plane for PixelPlayer: holds one
 * canonical playback session per Plex (home) user, fans state out to every
 * connected device over WebSocket, and relays commands to whichever device
 * is the active output. Audio never flows through here — players stream
 * straight from the Plex Media Server.
 *
 * Also serves the web remote/player (./web) and proxies the Plex PIN auth
 * flow so browsers never talk to plex.tv directly (avoids CORS surprises).
 *
 * Config (env):
 *   PORT             broker port                      (default 32599)
 *   PLEX_SERVER_URL  base URL of the PMS, advertised to clients for
 *                    artwork/stream URLs, e.g. http://192.168.1.10:32400
 */

'use strict';

const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { WebSocketServer, WebSocket } = require('ws');

const PORT = parseInt(process.env.PORT || '32599', 10);
const PLEX_SERVER_URL = (process.env.PLEX_SERVER_URL || '').replace(/\/+$/, '') || null;
const WEB_ROOT = path.join(__dirname, 'web');
const DATA_DIR = path.join(__dirname, 'data');

const PLEX_TV = 'https://plex.tv';
const PRODUCT = 'PixelPlayer Connect';
const USER_CACHE_TTL_MS = 12 * 60 * 60 * 1000;
const POSITION_BROADCAST_MIN_MS = 900;

// ─── Broker identity (stable client id for plex.tv calls) ────────────────

function loadBrokerCid() {
  try {
    fs.mkdirSync(DATA_DIR, { recursive: true });
    const file = path.join(DATA_DIR, 'broker.json');
    if (fs.existsSync(file)) {
      const saved = JSON.parse(fs.readFileSync(file, 'utf8'));
      if (saved.cid) return saved.cid;
    }
    const cid = 'pixelplay-connect-' + crypto.randomUUID();
    fs.writeFileSync(file, JSON.stringify({ cid }));
    return cid;
  } catch {
    return 'pixelplay-connect-ephemeral';
  }
}
const BROKER_CID = loadBrokerCid();

function plexHeaders(token) {
  const headers = {
    Accept: 'application/json',
    'X-Plex-Client-Identifier': BROKER_CID,
    'X-Plex-Product': PRODUCT,
    'X-Plex-Version': '1.0',
    'X-Plex-Platform': 'Node',
  };
  if (token) headers['X-Plex-Token'] = token;
  return headers;
}

// ─── Token → Plex user resolution (per-user session scoping) ─────────────

const userCache = new Map(); // token -> { user, at }

async function resolveUser(token) {
  const cached = userCache.get(token);
  if (cached && Date.now() - cached.at < USER_CACHE_TTL_MS) return cached.user;

  const res = await fetch(`${PLEX_TV}/api/v2/user`, { headers: plexHeaders(token) });
  if (!res.ok) throw new Error(`plex.tv rejected token (HTTP ${res.status})`);
  const json = await res.json();
  const user = {
    id: String(json.id),
    uuid: json.uuid || null,
    name: json.friendlyName || json.username || json.title || 'Plex user',
    thumb: json.thumb || null,
  };
  userCache.set(token, { user, at: Date.now() });
  return user;
}

// ─── Sessions ──────────────────────────────────────────────────────────────

/** userId -> session */
const sessions = new Map();

function sessionFor(userId) {
  let s = sessions.get(userId);
  if (!s) {
    s = {
      userId,
      devices: new Map(), // deviceId -> { info, ws, volume }
      activeDeviceId: null,
      queue: [],
      index: 0,
      positionMs: 0,
      positionAt: Date.now(),
      durationMs: 0,
      state: 'stopped',
      queueVersion: 0,
      lastPositionBroadcast: 0,
    };
    sessions.set(userId, s);
  }
  return s;
}

function snapshot(s) {
  return {
    devices: [...s.devices.values()].map((d) => ({
      ...d.info,
      volume: d.volume,
      isActive: d.info.id === s.activeDeviceId,
    })),
    activeDeviceId: s.activeDeviceId,
    queue: s.queue,
    queueVersion: s.queueVersion,
    index: s.index,
    positionMs: s.positionMs,
    positionAt: s.positionAt,
    durationMs: s.durationMs,
    state: s.state,
  };
}

function send(ws, msg) {
  if (ws && ws.readyState === WebSocket.OPEN) {
    try {
      ws.send(JSON.stringify(msg));
    } catch {
      /* peer raced a disconnect */
    }
  }
}

function broadcast(s) {
  const msg = { type: 'session', session: snapshot(s) };
  for (const d of s.devices.values()) send(d.ws, msg);
}

function sendToDevice(s, deviceId, msg) {
  const d = s.devices.get(deviceId);
  if (d) send(d.ws, msg);
}

function extrapolatedPosition(s) {
  if (s.state !== 'playing') return s.positionMs;
  return s.positionMs + (Date.now() - s.positionAt);
}

function setActive(s, deviceId) {
  const previous = s.activeDeviceId;
  if (previous && previous !== deviceId) {
    sendToDevice(s, previous, { type: 'command', action: 'stop' });
  }
  s.activeDeviceId = deviceId;
}

// ─── WebSocket message handling ───────────────────────────────────────────

function handleClaimQueue(s, deviceId, msg) {
  s.queue = Array.isArray(msg.tracks) ? msg.tracks : [];
  s.queueVersion += 1;
  s.index = Number.isInteger(msg.index) ? msg.index : 0;
  s.positionMs = Number(msg.positionMs) || 0;
  s.positionAt = Date.now();
  s.state = msg.state || 'playing';
  s.durationMs = Number(msg.durationMs) || (s.queue[s.index]?.durationMs ?? 0);
  setActive(s, deviceId);
  broadcast(s);
}

function handleState(s, deviceId, msg) {
  const isActive = s.activeDeviceId === deviceId;
  // Last-play-wins: a non-active device that starts playing takes the session.
  if (!isActive && msg.state !== 'playing') return;
  if (!isActive) setActive(s, deviceId);

  const stateChanged =
    s.state !== msg.state ||
    s.index !== msg.index ||
    (msg.ratingKey && s.queue[s.index]?.ratingKey !== msg.ratingKey);

  s.state = msg.state || 'stopped';
  if (Number.isInteger(msg.index)) s.index = msg.index;
  s.positionMs = Number(msg.positionMs) || 0;
  s.positionAt = Date.now();
  if (Number(msg.durationMs) > 0) s.durationMs = Number(msg.durationMs);

  const now = Date.now();
  if (stateChanged || now - s.lastPositionBroadcast > POSITION_BROADCAST_MIN_MS) {
    s.lastPositionBroadcast = now;
    broadcast(s);
  }
}

function handleCommand(s, senderId, msg) {
  const action = msg.action;
  switch (action) {
    case 'transfer': {
      const target = s.devices.get(msg.targetDeviceId);
      if (!target || !target.info.capabilities?.includes('player')) return;
      const autoplay = s.state === 'playing';
      s.positionMs = extrapolatedPosition(s);
      s.positionAt = Date.now();
      setActive(s, msg.targetDeviceId);
      send(target.ws, {
        type: 'adopt',
        queue: s.queue,
        index: s.index,
        positionMs: s.positionMs,
        autoplay,
      });
      broadcast(s);
      return;
    }
    case 'playQueue': {
      // A controller starts fresh content on the active (or given) device.
      s.queue = Array.isArray(msg.tracks) ? msg.tracks : [];
      s.queueVersion += 1;
      s.index = Number.isInteger(msg.index) ? msg.index : 0;
      s.positionMs = Number(msg.positionMs) || 0;
      s.positionAt = Date.now();
      s.durationMs = s.queue[s.index]?.durationMs ?? 0;
      const targetId =
        msg.targetDeviceId ||
        s.activeDeviceId ||
        (s.devices.get(senderId)?.info.capabilities?.includes('player') ? senderId : null);
      if (!targetId || !s.devices.has(targetId)) return;
      setActive(s, targetId);
      s.state = 'playing';
      sendToDevice(s, targetId, {
        type: 'adopt',
        queue: s.queue,
        index: s.index,
        positionMs: s.positionMs,
        autoplay: true,
      });
      broadcast(s);
      return;
    }
    case 'setVolume': {
      const targetId = msg.targetDeviceId || s.activeDeviceId;
      const device = targetId && s.devices.get(targetId);
      if (!device) return;
      device.volume = Math.max(0, Math.min(100, Number(msg.volume) || 0));
      send(device.ws, { type: 'command', action: 'setVolume', volume: device.volume });
      broadcast(s);
      return;
    }
    case 'play':
    case 'pause':
    case 'next':
    case 'previous':
    case 'stop':
    case 'seekTo':
    case 'playIndex': {
      if (!s.activeDeviceId) return;
      sendToDevice(s, s.activeDeviceId, {
        type: 'command',
        action,
        positionMs: msg.positionMs,
        index: msg.index,
      });
      return;
    }
    default:
      send(s.devices.get(senderId)?.ws, {
        type: 'error',
        message: `Unknown action: ${action}`,
      });
  }
}

// ─── WebSocket lifecycle ───────────────────────────────────────────────────

const wss = new WebSocketServer({ noServer: true });

wss.on('connection', (ws) => {
  let session = null;
  let deviceId = null;
  ws.isAlive = true;
  ws.on('pong', () => (ws.isAlive = true));

  ws.on('message', async (raw) => {
    let msg;
    try {
      msg = JSON.parse(raw.toString());
    } catch {
      return send(ws, { type: 'error', message: 'Invalid JSON' });
    }

    if (msg.type === 'hello') {
      try {
        const user = await resolveUser(String(msg.token || ''));
        const info = {
          id: String(msg.device?.id || crypto.randomUUID()),
          name: String(msg.device?.name || 'Unknown device'),
          platform: String(msg.device?.platform || 'unknown'),
          product: String(msg.device?.product || 'PixelPlayer'),
          capabilities: Array.isArray(msg.device?.capabilities)
            ? msg.device.capabilities.filter((c) => c === 'player' || c === 'controller')
            : ['controller'],
        };
        session = sessionFor(user.id);
        deviceId = info.id;
        // A reconnect replaces the previous socket for this device.
        const existing = session.devices.get(deviceId);
        if (existing && existing.ws !== ws) {
          try {
            existing.ws.close();
          } catch {}
        }
        session.devices.set(deviceId, { info, ws, volume: existing?.volume ?? null });
        send(ws, {
          type: 'welcome',
          user,
          serverUrl: PLEX_SERVER_URL,
          deviceId,
          session: snapshot(session),
        });
        broadcast(session);
        console.log(`[connect] ${info.name} (${info.platform}) joined as ${user.name}`);
      } catch (e) {
        send(ws, { type: 'error', message: `Auth failed: ${e.message}` });
        ws.close();
      }
      return;
    }

    if (!session || !deviceId) {
      return send(ws, { type: 'error', message: 'Say hello first' });
    }

    switch (msg.type) {
      case 'queue':
        handleClaimQueue(session, deviceId, msg);
        break;
      case 'state':
        handleState(session, deviceId, msg);
        break;
      case 'command':
        handleCommand(session, deviceId, msg);
        break;
      case 'ping':
        send(ws, { type: 'pong' });
        break;
      default:
        send(ws, { type: 'error', message: `Unknown type: ${msg.type}` });
    }
  });

  ws.on('close', () => {
    if (!session || !deviceId) return;
    const current = session.devices.get(deviceId);
    if (current && current.ws === ws) {
      session.devices.delete(deviceId);
      if (session.activeDeviceId === deviceId) {
        // Session survives for pickup on another device.
        session.activeDeviceId = null;
        session.positionMs = extrapolatedPosition(session);
        session.positionAt = Date.now();
        session.state = 'stopped';
      }
      broadcast(session);
      console.log(`[connect] device left: ${deviceId}`);
    }
  });
});

setInterval(() => {
  for (const ws of wss.clients) {
    if (!ws.isAlive) {
      ws.terminate();
      continue;
    }
    ws.isAlive = false;
    try {
      ws.ping();
    } catch {}
  }
}, 30_000);

// ─── Plex auth proxy (PIN flow + home users) for the web app ─────────────

async function plexTv(pathname, { method = 'GET', token, params } = {}) {
  const url = new URL(PLEX_TV + pathname);
  if (params) for (const [k, v] of Object.entries(params)) url.searchParams.set(k, v);
  const res = await fetch(url, { method, headers: plexHeaders(token) });
  const text = await res.text();
  if (!res.ok) throw new Error(`plex.tv HTTP ${res.status}: ${text.slice(0, 200)}`);
  return text ? JSON.parse(text) : {};
}

async function handleAuthRoute(req, res, url) {
  const json = (code, body) => {
    res.writeHead(code, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(body));
  };
  try {
    if (req.method === 'POST' && url.pathname === '/auth/pin') {
      const pin = await plexTv('/api/v2/pins', { method: 'POST', params: { strong: 'true' } });
      const authUrl =
        'https://app.plex.tv/auth#?clientID=' +
        encodeURIComponent(BROKER_CID) +
        '&code=' +
        encodeURIComponent(pin.code) +
        '&context%5Bdevice%5D%5Bproduct%5D=' +
        encodeURIComponent(PRODUCT);
      return json(200, { id: pin.id, code: pin.code, authUrl });
    }
    if (req.method === 'GET' && /^\/auth\/pin\/\d+$/.test(url.pathname)) {
      const id = url.pathname.split('/').pop();
      const pin = await plexTv(`/api/v2/pins/${id}`);
      return json(200, { token: pin.authToken || null });
    }
    if (req.method === 'GET' && url.pathname === '/auth/home-users') {
      const token = url.searchParams.get('token');
      const home = await plexTv('/api/v2/home/users', { token });
      const users = (home.users || []).map((u) => ({
        id: u.id,
        uuid: u.uuid,
        title: u.title || u.username,
        protected: !!u.protected,
        thumb: u.thumb || null,
      }));
      return json(200, { users });
    }
    if (req.method === 'POST' && url.pathname === '/auth/switch') {
      const body = await readBody(req);
      const { token, uuid, pin } = JSON.parse(body || '{}');
      const params = pin ? { pin } : undefined;
      const switched = await plexTv(`/api/v2/home/users/${uuid}/switch`, {
        method: 'POST',
        token,
        params,
      });
      return json(200, { token: switched.authToken });
    }
    json(404, { error: 'Not found' });
  } catch (e) {
    json(502, { error: e.message });
  }
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let data = '';
    req.on('data', (chunk) => {
      data += chunk;
      if (data.length > 64 * 1024) req.destroy();
    });
    req.on('end', () => resolve(data));
    req.on('error', reject);
  });
}

// ─── Static web app + HTTP server ─────────────────────────────────────────

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
  '.json': 'application/json',
  '.webmanifest': 'application/manifest+json',
};

function serveStatic(req, res, url) {
  let file = path.normalize(path.join(WEB_ROOT, url.pathname));
  if (!file.startsWith(WEB_ROOT)) {
    res.writeHead(403);
    return res.end();
  }
  if (url.pathname === '/' || !fs.existsSync(file) || fs.statSync(file).isDirectory()) {
    file = path.join(WEB_ROOT, 'index.html');
  }
  const ext = path.extname(file);
  fs.readFile(file, (err, data) => {
    if (err) {
      res.writeHead(404);
      return res.end('Not found');
    }
    res.writeHead(200, { 'Content-Type': MIME[ext] || 'application/octet-stream' });
    res.end(data);
  });
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://${req.headers.host}`);
  if (url.pathname === '/health') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    return res.end(
      JSON.stringify({
        ok: true,
        sessions: sessions.size,
        devices: [...sessions.values()].reduce((n, s) => n + s.devices.size, 0),
      })
    );
  }
  if (url.pathname === '/config') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    return res.end(JSON.stringify({ serverUrl: PLEX_SERVER_URL }));
  }
  if (url.pathname.startsWith('/auth/')) return handleAuthRoute(req, res, url);
  if (req.method === 'GET' || req.method === 'HEAD') return serveStatic(req, res, url);
  res.writeHead(405);
  res.end();
});

server.on('upgrade', (req, socket, head) => {
  const url = new URL(req.url, `http://${req.headers.host}`);
  if (url.pathname !== '/ws') {
    socket.destroy();
    return;
  }
  wss.handleUpgrade(req, socket, head, (ws) => wss.emit('connection', ws, req));
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`[connect] PixelPlayer Connect broker on :${PORT}`);
  console.log(`[connect] PMS advertised to clients: ${PLEX_SERVER_URL || '(none — set PLEX_SERVER_URL)'}`);
});
