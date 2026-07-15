# PixelPlayer Connect

Spotify-Connect-style playback sessions for PixelPlayer, scoped per Plex
(home) user. One small broker runs next to your Plex Media Server and holds
the canonical session — queue, position, and which device is the active
output. Every device (the PixelPlayer Android app, this repo's web
remote/player, anything speaking the protocol) keeps a WebSocket to the
broker, so state is pushed instantly and playback can be transferred between
devices with one tap.

Audio never flows through the broker: players stream directly from the PMS.

## Run it (Docker, on the same box as Plex)

```sh
cd connect
# edit docker-compose.yml → set PLEX_SERVER_URL to your PMS address
docker compose up -d --build
```

Or without Docker: `PLEX_SERVER_URL=http://<pms-ip>:32400 node server.js`
(Node 18+, `npm install` once).

Then on any device on your network, open:

    http://<server-ip>:32599

Sign in with Plex (PIN flow), pick your home user, and you get the web
remote — and the browser itself can be a playback target ("This browser" in
the device list).

The PixelPlayer Android app finds the broker automatically: it tries port
32599 on the same host as its configured Plex server.

## Per-user sessions

Every connection authenticates with a Plex token; the broker resolves it to
the Plex (home) user it belongs to and keys the session on that user. Each
home user gets an isolated session — their own queue, devices, and playback
state.

## Files

- `server.js` — the broker (WebSocket sessions + auth proxy + static web app)
- `web/` — the web remote/player
- `PROTOCOL.md` — the WebSocket protocol, for building more clients
