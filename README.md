<p align="center">
  <img src="https://img.shields.io/badge/Version-2.0.0-FF6B35?style=for-the-badge&labelColor=0D0D1A"/>
  <img src="https://img.shields.io/badge/Android-7.0%2B-4CAF50?style=for-the-badge&logo=android&logoColor=white"/>
  <img src="https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white"/>
  <img src="https://img.shields.io/badge/Transfer-Wi--Fi%20Direct-42A5F5?style=for-the-badge"/>
  <img src="https://img.shields.io/badge/Ads-None-9C27B0?style=for-the-badge"/>
</p>

<h1 align="center">⚡ Sharing Plus</h1>
<p align="center"><strong>Blazing-fast peer-to-peer file sharing over Wi-Fi Direct</strong></p>
<p align="center">
  No internet · No cloud · No account — scan a code, confirm it matches, and send
</p>

---

## 📱 Overview

**Sharing Plus** is a fully offline Android file-transfer app built on **Wi-Fi Direct** (`WifiP2pManager`) and **QR pairing**. Two devices connect directly — no router, no internet, no account — and files move over a raw NIO socket at full Wi-Fi speeds, with an optional forced-5GHz fast path. Every transfer is logged locally in a Room database.

Built with **Jetpack Compose**, a custom Material 3 `SleekPalette` design system (aurora gradients, glassmorphism cards, holographic borders), and a single-`ViewModel` architecture.

| | |
|---|---|
| **Package** | `com.willyshare.willykez` |
| **Min SDK** | 24 (Android 7.0) |
| **Language** | Kotlin, Jetpack Compose |
| **Storage** | Room (transfer history), SAF (custom receive folder) |

---

## ✨ Features

### 🔗 Connectivity
- **Wi-Fi Direct peer discovery** — real `WifiP2pManager` broadcasts, connect from a live nearby-devices list
- **QR pairing with a pull model** — the device with files ready to send shows a QR ("I'm discoverable"); the receiving device scans it and *pulls* the cart. This mirrors how people actually think about sharing: the person offering files is the one who gets found.
- **High-speed mode** — forces the Wi-Fi Direct group onto 5GHz (`GROUP_OWNER_BAND_5GHZ`) when the chipset supports it, with a clean fallback to auto-band on chipsets/OEMs that reject a forced band. Toggling it mid-session correctly tears down and re-forms the group instead of silently failing.
- **Match-code confirmation** — after connecting, before a single byte of file data moves, both devices show the same 4-digit code and both people must tap Confirm. Closes the "auto-trust whoever you land on" gap that QR/peer-discovery flows otherwise have. Auto-declines after 30 seconds of silence.

### 📤 Sending
- Pick files from device storage — Photos, Videos, Documents, APKs, Audio, Archives
- Grouped file browser with category tabs, multi-select, and thumbnail previews (including a play-badge overlay on video thumbnails)
- Cart is decoupled from connection: pick files first or connect first, in either order — the cart survives navigating around the app
- Files shared in from other apps ("Share to Sharing Plus") land straight in the cart
- Per-file progress rows with live speed (MB/s) and byte count; parallel multi-stream sending for large batches

### 📥 Receiving
- Always-on background listener — no need to sit on the Receive screen for another device to find you
- Choose a custom save folder via Storage Access Framework, or fall back to the app's own directory
- Foreground service + notification keep receiving alive even when the app is backgrounded
- Every finished transfer (sent or received) is written to a local, offline history log

### 🔒 Trust & safety
- Every socket connection opens with an explicit intent byte (push vs. pull) — the wire protocol never guesses
- Nothing is written to disk, and nothing is sent, until both devices' users have confirmed the same match-code
- No analytics, no ads, no network calls beyond the direct peer-to-peer link

---

## 🧠 How a transfer actually happens

Sharing Plus uses a small mode-byte protocol on top of raw TCP, plus a separate one-shot handshake connection for the match-code confirm. This is what makes the QR sender/receiver swap *and* the trust gate possible without the two flows stepping on each other:

```
1. HANDSHAKE  (its own short-lived connection, always first)
   dialer → [MODE_HANDSHAKE][device name][4-digit code][push/pull]
   acceptor's user sees the code, taps Confirm/Decline
   acceptor → [1 = confirmed | 0 = declined]
   dialer's own user must ALSO confirm locally before this counts as a yes

2. TRANSFER  (only opened if step 1 returned true)
   MODE_PUSH: dialer writes  [fileCount][name, size, bytes]...  → acceptor reads & saves
   MODE_PULL: dialer requests → acceptor (who has the cart) switches roles for this
              connection and writes its files down the same socket instead
```

Two things fall out of this design:

- **The always-on receive listener never has to guess who's connecting or why** — the first byte always tells it.
- **Parallel multi-stream sends never trigger duplicate confirm dialogs** — the handshake is a single connection, separate from the (possibly several) parallel data connections that follow it.

---

## 🗺️ Where things stand

| Stage | What it covers | Status |
|---|---|---|
| 1 | Core Wi-Fi Direct transfer, notifications, history | ✅ Done |
| 2 | Cart/role decoupling, unified connection state machine, panic-button reset | ✅ Done |
| 2.5 | Notification crash fix + guardrails | ✅ Done |
| 3a | Grid thumbnails, video play-badge | ✅ Done |
| 3b | QR sender/receiver role swap (pull model) + high-speed mode band-switch fix | ✅ Done |
| 4 | Match-code confirmation before any transfer begins | ✅ Done |
| — | Unified Nearby/Connect screen (merging Send/Receive into one state-driven screen) | ⏳ Scoped, not started — Send and Receive are intentionally still separate screens for now |
| — | Cancel support for a pull-triggered push | ⏳ Known gap — see below |

### Known limitations worth knowing about
- **Cancelling a pull-triggered send isn't wired up yet.** If someone scans your QR and pulls from you, that push runs on its own thread rather than through the cancellable job the rest of the app uses — the in-progress screen is honest about this (its button says "Hide," not "Cancel") rather than pretending to stop something it can't.
- **The peer-list (non-QR) connect flow is push-only**, unchanged from earlier versions — you pick a nearby device, you send to them. Only the QR flow uses the pull model.

---

## 🏗️ Project structure

```
app/src/main/java/com/willyshare/willykez/
├── MainActivity.kt          — Compose navigation host, global PIN overlay, back-stack
├── net/                     — Wi-Fi Direct, raw socket transfer protocol, QR payload codec
│   ├── WifiDirectManager.kt
│   ├── FileTransfer.kt      — FileSenderClient / FileReceiveServer, the mode-byte protocol
│   └── QrPairing.kt
├── ui/
│   ├── PulseViewModel.kt    — single source of truth: cart, connection state, transfer state
│   └── screens/             — one file per screen (Send, Receive, MyQr, ScanQr, Transferring, …)
├── data/                    — Room database (transfer history)
├── service/                 — foreground service keeping receive-listening alive
└── util/                    — notifications, share-intent handling
```

---

## 🛠️ Building

Standard Android Studio / Gradle project — open the root folder, let Gradle sync, run on a device (Wi-Fi Direct doesn't work in the emulator). Two physical devices are needed to test any transfer.

```bash
./gradlew assembleDebug
```

---

<p align="center"><sub>Built independently. No ads, no tracking, no cloud.</sub></p>
