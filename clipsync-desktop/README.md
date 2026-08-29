# ClipSync (desktop)

ND final year project. Kenechkwu Ozobial Opeolu, Reg No: 2022514154.

Rust daemon that syncs your clipboard with a paired Android phone over
the local network (WiFi or a phone hotspot both work, since a hotspot
is WiFi from the software's point of view). Cross-platform: Windows,
Linux, macOS.

Companion Android app is a separate project (`clipsync-android`).

## How it works

- Both devices enter the same **pairing code** (any shared secret
  phrase). This derives an AES-256 key on each side; nothing is sent
  over the network to negotiate it.
- Devices advertise themselves on the LAN via mDNS and find each
  other automatically, no IP addresses to type in.
- On connecting, they exchange a fingerprint of the derived key (not
  the code itself) to confirm both sides used the same pairing code.
  Mismatched pairing codes are rejected before any clipboard content
  is exchanged.
- Once paired, all clipboard traffic is AES-256-GCM encrypted.
- Clipboard is polled locally every ~600ms for changes; when it
  changes, the new text is pushed to the paired peer. Applying a
  received update updates the same tracked state, so it doesn't
  bounce back and forth in a loop.

## Build & run

```
cargo build --release
./target/release/clipsync-desktop
```

You'll be prompted for a pairing code and a device name. Enter the
same pairing code on the phone app. Once both are running on the same
network, they find each other automatically — no manual IP entry.

## Known limitations

- Built for a **2-device pair**. If more than two instances share a
  pairing code, the "last connection wins" for outgoing sync — it
  won't fan out cleanly to 3+ devices as-is.
- Only one instance can run per machine (it binds a fixed port,
  53211).
- Clipboard is polled, not event-driven (no cross-platform "clipboard
  changed" OS hook exists that works identically on Windows/Linux/
  macOS), so there's up to ~600ms latency on a change.
- If the clipboard holds a non-text item (an image, a file), it's
  skipped, not synced — this project handles text only.
- Bluetooth is not implemented. WiFi/hotspot covers the LAN case
  cleanly across all three target OSes; classic Bluetooth (RFCOMM)
  support in Rust is fragmented per-platform and would need separate,
  OS-specific code as a follow-up phase.

## Tested

- Compiles clean with zero warnings on Rust 1.75+.
- Two instances on the same host: mDNS discovery, handshake, and
  pairing verified working end to end.
- Self-connection correctly avoided via exact mDNS fullname matching.
- Pairing-code mismatch correctly rejected before any clipboard data
  is exchanged (verified via fingerprint comparison).
- Port conflict (second instance on same machine) fails with a clear
  message instead of a crash/panic.
