/* PixelPlayer Connect — web remote & player. */
'use strict';

// ─── State ─────────────────────────────────────────────────────────────────

const store = {
  token: localStorage.getItem('ppc_token'),
  accountToken: null, // pre-home-switch token, kept during user picking
  deviceId: localStorage.getItem('ppc_device') || crypto.randomUUID(),
  serverUrl: null,
  user: null,
  session: null,
  ws: null,
  wsRetry: 0,
  // Local player state (when this browser is the active output)
  playerQueue: [],
  playerIndex: -1,
  pendingAutoplay: false,
};
localStorage.setItem('ppc_device', store.deviceId);

const $ = (id) => document.getElementById(id);
const audio = $('audio');

const deviceName = (() => {
  const ua = navigator.userAgent;
  const browser = /firefox/i.test(ua) ? 'Firefox' : /edg/i.test(ua) ? 'Edge' : /chrome/i.test(ua) ? 'Chrome' : /safari/i.test(ua) ? 'Safari' : 'Browser';
  const os = /mac/i.test(ua) ? 'Mac' : /windows/i.test(ua) ? 'Windows' : /iphone|ipad/i.test(ua) ? 'iOS' : /android/i.test(ua) ? 'Android' : /linux/i.test(ua) ? 'Linux' : '';
  return `${browser}${os ? ' on ' + os : ''}`;
})();

// ─── Auth flow ─────────────────────────────────────────────────────────────

async function api(path, opts) {
  const res = await fetch(path, opts);
  const json = await res.json();
  if (!res.ok) throw new Error(json.error || `HTTP ${res.status}`);
  return json;
}

async function startSignIn() {
  const status = $('auth-status');
  try {
    $('btn-signin').disabled = true;
    status.textContent = 'Opening plex.tv…';
    const pin = await api('/auth/pin', { method: 'POST' });
    window.open(pin.authUrl, '_blank', 'noopener');
    status.textContent = 'Approve the sign-in in the Plex tab…';
    const token = await pollPin(pin.id);
    status.textContent = '';
    await chooseUser(token);
  } catch (e) {
    status.textContent = e.message;
    $('btn-signin').disabled = false;
  }
}

function pollPin(id) {
  return new Promise((resolve, reject) => {
    const started = Date.now();
    const timer = setInterval(async () => {
      try {
        if (Date.now() - started > 5 * 60 * 1000) {
          clearInterval(timer);
          return reject(new Error('Sign-in timed out — try again.'));
        }
        const { token } = await api(`/auth/pin/${id}`);
        if (token) {
          clearInterval(timer);
          resolve(token);
        }
      } catch (e) {
        clearInterval(timer);
        reject(e);
      }
    }, 2000);
  });
}

async function chooseUser(accountToken) {
  store.accountToken = accountToken;
  let users = [];
  try {
    ({ users } = await api(`/auth/home-users?token=${encodeURIComponent(accountToken)}`));
  } catch {
    /* not a Plex Home account */
  }
  if (users.length <= 1) return finishAuth(accountToken);

  $('user-picker').hidden = false;
  $('btn-signin').hidden = true;
  const list = $('user-list');
  list.innerHTML = '';
  users.forEach((u) => {
    const btn = document.createElement('button');
    btn.innerHTML = u.thumb
      ? `<img src="${u.thumb}" alt="">`
      : `<span class="avatar-fallback">👤</span>`;
    btn.innerHTML += `<span>${escapeHtml(u.title)}</span>`;
    btn.onclick = () => pickUser(u);
    list.appendChild(btn);
  });
}

async function pickUser(user) {
  const pinRow = $('pin-row');
  const doSwitch = async (pin) => {
    try {
      const { token } = await api('/auth/switch', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ token: store.accountToken, uuid: user.uuid, pin: pin || undefined }),
      });
      finishAuth(token);
    } catch (e) {
      $('auth-status').textContent = `Could not switch user: ${e.message}`;
    }
  };
  if (user.protected) {
    pinRow.hidden = false;
    $('pin-input').focus();
    $('btn-pin-ok').onclick = () => doSwitch($('pin-input').value);
  } else {
    doSwitch();
  }
}

function finishAuth(token) {
  store.token = token;
  localStorage.setItem('ppc_token', token);
  showMain();
}

function signOut() {
  localStorage.removeItem('ppc_token');
  location.reload();
}

// ─── WebSocket ─────────────────────────────────────────────────────────────

function connectWs() {
  const proto = location.protocol === 'https:' ? 'wss' : 'ws';
  const ws = new WebSocket(`${proto}://${location.host}/ws`);
  store.ws = ws;

  ws.onopen = () => {
    store.wsRetry = 0;
    ws.send(
      JSON.stringify({
        type: 'hello',
        token: store.token,
        device: {
          id: store.deviceId,
          name: deviceName,
          platform: 'web',
          product: 'PixelPlayer Web',
          capabilities: ['controller', 'player'],
        },
      })
    );
  };

  ws.onmessage = (ev) => {
    let msg;
    try {
      msg = JSON.parse(ev.data);
    } catch {
      return;
    }
    if (msg.type === 'welcome') {
      store.user = msg.user;
      store.serverUrl = msg.serverUrl;
      store.session = msg.session;
      $('user-name').textContent = msg.user.name;
      render();
    } else if (msg.type === 'session') {
      store.session = msg.session;
      render();
    } else if (msg.type === 'adopt') {
      adopt(msg);
    } else if (msg.type === 'command') {
      onPlayerCommand(msg);
    } else if (msg.type === 'error') {
      console.warn('[connect]', msg.message);
      if (/auth failed/i.test(msg.message || '')) signOut();
    }
  };

  ws.onclose = () => {
    const wait = Math.min(15000, 1000 * 2 ** store.wsRetry++);
    setTimeout(connectWs, wait);
  };
}

function send(msg) {
  if (store.ws?.readyState === WebSocket.OPEN) store.ws.send(JSON.stringify(msg));
}

function command(action, extra = {}) {
  send({ type: 'command', action, ...extra });
}

// ─── Browser as player ─────────────────────────────────────────────────────

function isSelfActive() {
  return store.session?.activeDeviceId === store.deviceId;
}

async function adopt(msg) {
  store.playerQueue = msg.queue || [];
  store.playerIndex = msg.index ?? 0;
  await playCurrent(msg.positionMs || 0, msg.autoplay !== false);
}

async function playCurrent(positionMs, autoplay) {
  const track = store.playerQueue[store.playerIndex];
  if (!track || !store.serverUrl) return;
  try {
    const meta = await fetch(
      `${store.serverUrl}/library/metadata/${track.ratingKey}?X-Plex-Token=${encodeURIComponent(store.token)}`,
      { headers: { Accept: 'application/json' } }
    ).then((r) => r.json());
    const part = meta?.MediaContainer?.Metadata?.[0]?.Media?.[0]?.Part?.[0]?.key;
    if (!part) throw new Error('No playable part');
    audio.src = `${store.serverUrl}${part}?X-Plex-Token=${encodeURIComponent(store.token)}`;
    audio.currentTime = (positionMs || 0) / 1000;
    if (autoplay) {
      try {
        await audio.play();
      } catch {
        // Autoplay blocked — needs one user gesture.
        store.pendingAutoplay = true;
        $('tap-to-play').hidden = false;
      }
    }
  } catch (e) {
    console.warn('[player] failed to start track', e);
    reportState('stopped');
  }
}

function onPlayerCommand(msg) {
  switch (msg.action) {
    case 'play':
      audio.play().catch(() => {
        store.pendingAutoplay = true;
        $('tap-to-play').hidden = false;
      });
      break;
    case 'pause':
      audio.pause();
      break;
    case 'stop':
      audio.pause();
      audio.removeAttribute('src');
      store.playerIndex = -1;
      break;
    case 'seekTo':
      audio.currentTime = (msg.positionMs || 0) / 1000;
      break;
    case 'setVolume':
      audio.volume = Math.max(0, Math.min(1, (msg.volume ?? 100) / 100));
      break;
    case 'next':
      stepTrack(1);
      break;
    case 'previous':
      stepTrack(-1);
      break;
    case 'playIndex':
      if (Number.isInteger(msg.index)) {
        store.playerIndex = msg.index;
        playCurrent(0, true);
      }
      break;
  }
}

function stepTrack(delta) {
  const next = store.playerIndex + delta;
  if (next < 0 || next >= store.playerQueue.length) return;
  store.playerIndex = next;
  playCurrent(0, true);
}

function reportState(state) {
  const track = store.playerQueue[store.playerIndex];
  send({
    type: 'state',
    state,
    positionMs: Math.round(audio.currentTime * 1000),
    durationMs: Math.round((audio.duration || (track?.durationMs ?? 0) / 1000) * 1000) || track?.durationMs || 0,
    index: store.playerIndex,
    ratingKey: track?.ratingKey || null,
  });
}

audio.addEventListener('ended', () => {
  if (store.playerIndex < store.playerQueue.length - 1) stepTrack(1);
  else reportState('stopped');
});
audio.addEventListener('play', () => isSelfActive() && reportState('playing'));
audio.addEventListener('pause', () => {
  if (isSelfActive() && !audio.ended) reportState('paused');
});
setInterval(() => {
  if (isSelfActive() && !audio.paused) reportState('playing');
}, 1000);

$('tap-to-play').addEventListener('click', () => {
  $('tap-to-play').hidden = true;
  if (store.pendingAutoplay) {
    store.pendingAutoplay = false;
    audio.play().catch(() => {});
  }
});

// ─── Rendering ─────────────────────────────────────────────────────────────

function fmtTime(ms) {
  if (!ms || ms < 0) ms = 0;
  const s = Math.round(ms / 1000);
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
}

function escapeHtml(str) {
  return String(str ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

function currentTrack() {
  const s = store.session;
  return s ? s.queue[s.index] : null;
}

function livePosition() {
  const s = store.session;
  if (!s) return 0;
  if (s.state !== 'playing') return s.positionMs;
  return s.positionMs + (Date.now() - s.positionAt);
}

let seekDragging = false;

function render() {
  const s = store.session;
  if (!s) return;

  const track = currentTrack();
  $('track-title').textContent = track ? track.title : 'Nothing playing';
  $('track-artist').textContent = track ? track.artist : '';

  const art = $('artwork');
  if (track?.thumb && store.serverUrl) {
    const url = `${store.serverUrl}/photo/:/transcode?width=800&height=800&minSize=1&url=${encodeURIComponent(track.thumb)}&X-Plex-Token=${encodeURIComponent(store.token)}`;
    art.style.backgroundImage = `url("${url}")`;
    art.classList.add('has-art');
  } else {
    art.style.backgroundImage = '';
    art.classList.remove('has-art');
  }

  $('btn-play').textContent = s.state === 'playing' ? '⏸' : '▶';

  const active = s.devices.find((d) => d.id === s.activeDeviceId);
  const label = $('active-device-label');
  label.textContent = active ? (active.id === store.deviceId ? 'This browser' : active.name) : 'No device';
  $('btn-devices').classList.toggle('active', !!active);

  // Queue
  const queueEl = $('queue');
  queueEl.innerHTML = '';
  s.queue.forEach((t, i) => {
    const li = document.createElement('li');
    li.className = i === s.index ? 'current' : '';
    li.innerHTML = `<span class="q-title">${escapeHtml(t.title)}</span><span class="q-artist">${escapeHtml(t.artist)}</span>`;
    li.onclick = () => command('playIndex', { index: i });
    queueEl.appendChild(li);
  });

  renderDeviceSheet();
  updateSeekUi();
}

function renderDeviceSheet() {
  const s = store.session;
  const list = $('device-list');
  list.innerHTML = '';
  const players = s.devices.filter((d) => d.capabilities.includes('player'));
  if (!players.length) {
    list.innerHTML = '<p class="muted small">No players online. Open PixelPlayer on your phone.</p>';
    return;
  }
  players.forEach((d) => {
    const isSelf = d.id === store.deviceId;
    const btn = document.createElement('button');
    btn.className = d.isActive ? 'active' : '';
    const icon = d.platform === 'android' ? '📱' : d.platform === 'web' ? '💻' : '🔊';
    btn.innerHTML =
      `<span class="d-icon">${icon}</span>` +
      `<span><div class="d-name">${escapeHtml(isSelf ? 'This browser' : d.name)}</div>` +
      `<div class="d-sub">${escapeHtml(d.product)}${d.isActive ? ' • playing' : ''}</div></span>`;
    btn.onclick = () => {
      if (!d.isActive) command('transfer', { targetDeviceId: d.id });
      closeSheet();
    };
    list.appendChild(btn);
  });
}

function updateSeekUi() {
  const s = store.session;
  if (!s || seekDragging) return;
  const pos = Math.min(livePosition(), s.durationMs || 0);
  $('time-now').textContent = fmtTime(pos);
  $('time-total').textContent = fmtTime(s.durationMs);
  $('seek').value = s.durationMs ? Math.round((pos / s.durationMs) * 1000) : 0;
}
setInterval(updateSeekUi, 500);

// ─── UI wiring ─────────────────────────────────────────────────────────────

function openSheet() {
  $('sheet-backdrop').hidden = false;
  $('device-sheet').hidden = false;
}
function closeSheet() {
  $('sheet-backdrop').hidden = true;
  $('device-sheet').hidden = true;
}

function showMain() {
  $('view-auth').hidden = true;
  $('view-main').hidden = false;
  connectWs();
}

$('btn-signin').onclick = startSignIn;
$('btn-devices').onclick = openSheet;
$('sheet-backdrop').onclick = closeSheet;
$('btn-play').onclick = () => command(store.session?.state === 'playing' ? 'pause' : 'play');
$('btn-prev').onclick = () => command('previous');
$('btn-next').onclick = () => command('next');
$('volume').oninput = (e) => command('setVolume', { volume: Number(e.target.value) });

const seek = $('seek');
seek.addEventListener('pointerdown', () => (seekDragging = true));
seek.addEventListener('change', () => {
  seekDragging = false;
  const s = store.session;
  if (!s?.durationMs) return;
  command('seekTo', { positionMs: Math.round((seek.value / 1000) * s.durationMs) });
});

// ─── Boot ──────────────────────────────────────────────────────────────────

if (store.token) showMain();
else $('view-auth').hidden = false;
