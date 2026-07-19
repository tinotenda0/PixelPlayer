# PixelPlayer Cast Receiver

A custom Google Cast **Web Receiver** that replaces Google's generic Default
Media Receiver with a PixelPlayer-designed now-playing screen on the TV:
full-bleed blurred album art, large cover, title/artist/album, a progress
bar, and PixelPlayer branding. The app already sends title, artist, and album
art in the cast metadata, so this page renders real content with no app
changes beyond pointing at your receiver id.

`index.html` is fully self-contained (only the Cast receiver SDK is loaded
from Google's CDN, as required).

## Why you have to do part of this yourself

A non-default receiver **must** be registered in the Google Cast Developer
Console — that's the only place a receiver application id is minted, and it
requires your Google account, a one-time ~$5 developer registration, and
physical access to your Chromecast to add it as a test device. I can't do
those steps for you. What I built: the receiver page itself and the code hook
(`res/values/strings.xml` → `cast_receiver_application_id`) so switching the
app to your receiver is a one-line change.

## 1. Host it over HTTPS (valid cert required)

Cast custom receivers must be served over HTTPS with a **CA-signed** cert
(self-signed is rejected). You already run `cloudflared` on your server — a
Cloudflare tunnel gives you a valid public HTTPS URL for free.

Simplest option — drop it behind the nginx you already run:

```sh
# copy index.html to the server
scp cast-receiver/index.html tino@xps-server:~/cast-receiver/index.html
```

Serve `~/cast-receiver` from any static host (the existing `iptv-nginx`
container, a tiny `nginx:alpine`, or even the Connect sidecar's static dir),
then expose it through your Cloudflare tunnel at a stable URL, e.g.
`https://cast.<your-domain>/`. Confirm the URL loads in a browser over HTTPS
with a valid padlock before continuing.

## 2. Register the receiver in the Cast console

1. Go to https://cast.google.com/publish and sign in (pay the one-time dev fee
   if prompted).
2. **Add new application → Custom Receiver.**
3. Name it "PixelPlayer", paste your HTTPS receiver URL from step 1.
4. Save. It shows an **Application ID** (e.g. `A1B2C3D4`).
5. Under **Cast Receiver → Devices**, add your Chromecast's serial number as a
   test device (Settings on the Chromecast shows the serial). Required until
   the app is published; publishing is optional for personal use.

## 3. Point the app at it

Put the Application ID into the app:

`app/src/main/res/values/strings.xml`
```xml
<string name="cast_receiver_application_id" translatable="false">A1B2C3D4</string>
```

Rebuild and install. That's it — the route discovery and everything else key
off this id automatically. Leaving the string empty falls back to Google's
Default Media Receiver.

## Notes

- Give the Chromecast ~15 minutes after adding it as a test device before it
  picks up an unpublished receiver.
- Tweak colors/layout freely in `index.html` — `--accent`, the art size, the
  background blur. Metadata fields come straight from what the app sends.
- A cheaper middle path with **no hosting**: register a *Styled* Media
  Receiver instead of Custom, theme it via the console form (background/logo/
  font/colors, images can point at your server), and use that app id the same
  way. Less design control than this custom page, but zero hosting.
