# ClipSync

Sync your clipboard between your desktop and phone over the local network —
no cloud, no account, no server in the middle.

- **Auto-discovery** — devices find each other on the same WiFi (or a phone
  hotspot) via mDNS, no typing IP addresses.
- **Shared-secret pairing** — enter the same pairing code on both devices;
  nothing is sent over the network to negotiate it.
- **End-to-end encrypted** — AES-256-GCM, always.
- **Two implementations, one protocol** — a Rust daemon for desktop
  (Windows/Linux/macOS) and a native Kotlin app for Android, independently
  built to the same wire spec so they interoperate without sharing any code.

## Repo layout

```
clipsync/
├── clipsync-desktop/   Rust daemon (Windows/Linux/macOS), run from a terminal
└── clipsync-android/   Kotlin app, build with Android Studio
```

Each subfolder has its own README with full build/run details; this one
covers how they fit together.

## How it works

1. Both devices enter the same **pairing code** (any shared secret phrase).
   Each side independently derives a 256-bit AES key from it —
   `SHA-256(pairing_code.trim())` — so the code itself is never sent over
   the network.
2. Devices advertise themselves on the LAN via **mDNS** and find each other
   automatically. The desktop app uses `mdns-sd` (service type
   `_clipsync._tcp.local.`); the Android app uses `NsdManager` (service type
   `_clipsync._tcp.`, same service — Android's API just omits the implicit
   `.local.` domain).
3. On connecting, each side exchanges a **fingerprint** of the derived key
   (the first 8 bytes of `SHA-256(key || "clipsync-fingerprint")`, hex-encoded)
   to confirm both used the same pairing code — the code and key itself are
   never exchanged. A mismatched fingerprint gets the connection dropped
   before any clipboard content is sent.
4. Once paired, all clipboard traffic is **AES-256-GCM** encrypted: a
   12-byte random nonce followed by the ciphertext (with its 16-byte tag),
   wrapped in a 4-byte big-endian length-prefixed frame.
5. Clipboard changes are pushed to the paired peer as JSON
   `{"text": ..., "from_device": ...}`. Applying a received update updates
   the same tracked state on each side, so it doesn't bounce back and forth
   in a loop.

Because both apps implement this spec independently — same key derivation,
same framing, same encryption — a desktop instance and the Android app pair
and sync with each other out of the box.

## Quick start

**Desktop:**
```bash
cd clipsync-desktop
cargo build --release
./target/release/clipsync-desktop
```
Enter a pairing code and device name when prompted.

**Android:**
Open `clipsync-android/` in Android Studio, let it sync, run on a device on
the same WiFi network. Enter the same pairing code in the app, tap Start.

Both sides print/show a key fingerprint on startup — if they match, pairing
is working correctly.

## Known limitations

- **2-device pair.** If more than two instances share a pairing code,
  "last connection wins" for outgoing sync — it won't fan out cleanly to
  3+ devices as-is.
- **Text only.** Images and files on the clipboard are skipped, not synced.
- **LAN only, no Bluetooth.** WiFi/hotspot covers the common case; classic
  Bluetooth support is fragmented per-platform in Rust and isn't implemented.
- **Android 10+ background clipboard restrictions.** Apps that aren't in the
  foreground generally can't *read* clipboard content on modern Android, so
  outgoing sync (phone → desktop) is most reliable while the app is
  foregrounded. Incoming sync (desktop → phone) is unaffected. This is an
  OS-level restriction, not something this app routes around.
- **One instance per machine** on desktop (binds a fixed port, 53211).
- Some routers/APs throttle or drop multicast traffic, which mDNS discovery
  relies on — if two devices aren't finding each other, that's usually the
  network, not the app.

## Status

Working end to end: mDNS discovery, handshake, pairing-code verification,
and encrypted clipboard sync have all been tested between two desktop
instances and between desktop and Android. Rough edges noted above and in
each subfolder's README.
