# PixelPlayer Connect protocol

JSON messages over a WebSocket to `ws://<broker>:32599/ws`. One session per
Plex (home) user; a device belongs to exactly one session, determined by the
token it presents.

## Client → broker

### hello (required first)
```json
{ "type": "hello", "token": "<plex token>",
  "device": { "id": "uuid", "name": "Pixel 8", "platform": "android",
              "product": "PixelPlayer", "capabilities": ["controller", "player"] } }
```

### queue — claim the session with local playback
Sent by a player when the user starts playback *on that device*. Makes the
sender the active output (last-play-wins) and replaces the session queue.
```json
{ "type": "queue", "tracks": [Track], "index": 0, "positionMs": 0,
  "state": "playing", "durationMs": 215000 }
```

### state — active player's heartbeat (~1-2s and on every change)
```json
{ "type": "state", "state": "playing|paused|stopped|buffering",
  "positionMs": 4200, "durationMs": 215000, "index": 3, "ratingKey": "12345" }
```
A `state: "playing"` from a non-active device also claims the session.

### command — controller actions
```json
{ "type": "command", "action": "play|pause|next|previous|stop|seekTo|playIndex|setVolume|transfer|playQueue",
  "positionMs": 61000, "index": 2, "volume": 60,
  "targetDeviceId": "uuid", "tracks": [Track] }
```
- `transfer` — move the running session to `targetDeviceId`.
- `playQueue` — start fresh `tracks` on the active (or `targetDeviceId`) device.
- `setVolume` — volume 0-100 on `targetDeviceId` (default: active device).

## Broker → client

- `welcome` — `{ user, serverUrl, deviceId, session }` after a valid hello.
- `session` — full session snapshot, pushed on every meaningful change:
  `{ devices: [{id,name,platform,product,capabilities,volume,isActive}],
     activeDeviceId, queue, queueVersion, index, positionMs, positionAt,
     durationMs, state }`.
  Clients extrapolate position: `positionMs + (now - positionAt)` while playing.
- `adopt` — makes the receiving device the active output:
  `{ queue, index, positionMs, autoplay }`. The device starts playing from
  the PMS and begins sending `state`.
- `command` — relayed action for the active player (`play`, `pause`, `stop`,
  `seekTo`, `setVolume`, `next`, `previous`, `playIndex`).
- `error` — `{ message }`.

## Track object

```json
{ "ratingKey": "12345", "title": "…", "artist": "…", "album": "…",
  "durationMs": 215000, "thumb": "/library/metadata/12340/thumb/17..." }
```
Players resolve audio/artwork themselves against the PMS with their own
token (`/library/metadata/{ratingKey}` → `Media.Part.key`).
