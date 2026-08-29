# ClipSync (Android)

Kotlin companion app for `clipsync-desktop`. Talks the exact same wire
protocol, so any desktop instance and this app can pair and sync clipboard
text over the LAN.

## Protocol compatibility (matches clipsync-desktop 1:1)

- **Key derivation:** `SHA-256(pairing_code.trim())` → 32-byte AES key.
- **Fingerprint:** first 8 bytes of `SHA-256(key || "clipsync-fingerprint")`,
  hex-encoded. Exchanged in the handshake to confirm both sides used the same
  pairing code, without revealing the code or key.
- **Discovery:** mDNS/NSD service type `_clipsync._tcp.` (Android's NSD
  omits the `.local.` suffix that mdns-sd adds; it's the same service). The
  fingerprint is published as a TXT record under key `fp`.
- **Transport:** TCP on port `53211`.
- **Handshake:** each side sends an unencrypted length-prefixed JSON `Hello
  {device_name, key_fingerprint}` frame. If the fingerprints don't match, the
  connection is dropped before any clipboard content is exchanged.
- **Framing:** 4-byte big-endian length prefix + payload, both directions.
- **Clipboard updates:** JSON `{text, from_device}`, AES-256-GCM encrypted as
  `12-byte random nonce || ciphertext+tag`, wrapped in the same length-prefixed
  frame.

Because both apps derive the key from the same pairing code and use the same
framing/encryption, they interoperate without any shared code — just matching
implementations of the same spec.

## Project layout

```
app/src/main/java/com/clipsync/android/
  Crypto.kt      key derivation, fingerprint, AES-256-GCM encrypt/decrypt
  Protocol.kt    frame read/write, Hello + ClipUpdate JSON (en/de)coding
  Discovery.kt   NsdManager wrapper: advertise + browse for a matching peer
  ClipSyncService.kt   foreground service: accept loop, dial loop, handshake,
                       clipboard watching
  MainActivity.kt      pairing code / device name entry, start/stop, live log
  Events.kt            tiny in-process pub/sub so the service can report
                       status/log lines back to the activity
```

## Build & run

Open the `clipsync-android` folder in Android Studio (Koala or newer) and let
it sync — the Gradle wrapper isn't checked in, so Android Studio will offer to
generate it on first open. Then run on a device or emulator on the same LAN
as a `clipsync-desktop` instance.

On first launch:
1. Enter the same pairing code you used on the desktop app.
2. Optionally set a device name (defaults to `this-phone`).
3. Tap **Start**. The app runs as a foreground service (persistent
   notification) so it keeps syncing while backgrounded.

Requires Android 7.0 (API 24)+.

## Known limitations

- **Background clipboard access (Android 10+):** since Android 10, apps that
  aren't the foreground app or the default input method generally can't read
  clipboard *content* — `OnPrimaryClipChangedListener` may still fire, but
  `getPrimaryClip()` can come back empty. In practice this means **outgoing**
  sync (phone → desktop) is most reliable while ClipSync (or whatever app you
  copied from) is in the foreground; **incoming** sync (desktop → phone) is
  unaffected since `setPrimaryClip()` isn't restricted. This is an OS-level
  restriction, not something this app can route around cleanly — a
  workaround (e.g. becoming a default IME or accessibility service) would
  add a lot of surface area for a one-way convenience gain, so it isn't
  implemented here.
- **2-device pair**, same as the desktop app: if more than two instances
  share a pairing code, "last connection wins" for outgoing sync.
- **Text only.** Images and files on the clipboard are skipped.
- **No manual IP entry / no Bluetooth.** LAN-only via mDNS/NSD, same as
  desktop.
- Some routers/APs throttle or drop multicast traffic; if discovery is slow
  or fails, that's usually the network, not the app (a `MulticastLock` is
  held while the service runs to reduce this on the Android side).

## Not yet built

- No persisted settings — pairing code and device name are re-entered each
  launch (nothing is written to disk, by design, since the pairing code is
  effectively a shared secret).
- No UI polish beyond a functional single screen; this mirrors the desktop
  app's plain terminal UI rather than a "finished" app.
