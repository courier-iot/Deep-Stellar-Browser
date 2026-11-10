# Deep Stellar link protocol, version 1

How **Deep Stellar Station** (desktop) and **Deep Stellar Browsing** (Android) pair and share links.
Both apps implement exactly this. Change it only by bumping the version.

## Goals

- No server, no relay, no account. Version 1 works on the same local network only.
- Only the two paired devices can read or create messages.
- Built only from primitives both platforms ship: ECDH P-256, HKDF-SHA256, HMAC-SHA256, AES-256-GCM
  (Java/Android built-ins; WebCrypto in the desktop app). No extra crypto libraries.

## Encodings

- **b64**: base64url, no padding.
- **Public keys**: uncompressed P-256 point, 65 bytes (`0x04 || X || Y`), as b64.
- **Fingerprint** `fp(pub)`: first 16 characters of b64(SHA-256(65-byte key)).

## Transport

- The desktop listens on TCP (port 47321, or a random port if that's taken) on all local interfaces.
- It advertises mDNS/DNS-SD service type `_dsstation._tcp`, instance name `Station-<first 8 of fp>`,
  TXT record `fp=<fingerprint>`.
- The phone always dials the desktop.
- **Frame**: 4-byte big-endian length, then that many bytes of UTF-8 JSON. Maximum 4 MiB. Anything larger closes the connection.

## Pairing

1. The desktop shows a QR code:
   ```
   dsstation://pair?v=1&k=<desktop pub>&t=<token>&a=<ip:port>[,<ip:port>…]&n=<url-encoded name>
   ```
   `token` is 16 random bytes (b64), single use, valid for 10 minutes.
2. The phone scans it, connects to one of the addresses (or finds the desktop by mDNS) and sends:
   ```json
   {"t":"pair","v":1,"pub":"<phone pub>","name":"<phone name>","proof":"<b64 HMAC>"}
   ```
   `proof = HMAC-SHA256(key = token bytes, "dsl-pair-1|" + phonePub + "|" + desktopPub)`
3. The desktop checks the token is the one on screen and unexpired, checks the proof, stores the phone and answers:
   ```json
   {"t":"paired","pub":"<desktop pub>","name":"<desktop name>","proof":"<b64 HMAC>"}
   ```
   `proof = HMAC-SHA256(token, "dsl-paired-1|" + desktopPub + "|" + phonePub)`.
   On failure it sends `{"t":"pair-fail","why":"…"}` and closes.
4. The phone checks the proof (so a different device can't pose as the desktop) and stores the desktop.

The QR code is the trust anchor: whoever can see it on your screen can pair, so it disappears after one use or 10 minutes.

## Shared key

```
Z    = ECDH(own private key, other public key)        # 32-byte X coordinate
salt = "deep-stellar-link-v1"
info = min(pubA, pubB) + "|" + max(pubA, pubB)        # b64 strings, ordinary string order
K    = HKDF-SHA256(ikm = Z, salt, info, length = 32)
```

## Messages

After connecting, the phone sends `{"t":"hello","fp":"<phone fp>","name":"<phone name>"}`.
The desktop answers `{"t":"hello","fp":"<desktop fp>","name":"…"}` if it knows the phone, or
`{"t":"unknown"}` and closes if it doesn't. It then sends any messages it queued for that phone.

Every message, in both directions:

```json
{"t":"msg","fp":"<sender fp>","n":"<b64 12-byte nonce>","c":"<b64 ciphertext + 16-byte tag>"}
```

`c = AES-256-GCM(K, nonce, plaintext, aad = "dsl-msg-1|" + senderFp)`. The plaintext is JSON:

```json
{"id":"<random b64>","ts":1794300000000,"kind":"link","url":"https://…","title":"…","from":"<sender name>"}
{"id":"…","ts":…,"kind":"text","text":"…","from":"…"}
{"id":"…","ts":…,"kind":"ack","ack":"<id being acknowledged>"}
```

- A frame that fails to decrypt is dropped silently.
- Receivers ignore an `id` they've already seen (stops replays) and answer every link or text with an `ack`.
- Senders keep a message queued until it's acknowledged.

## What this does and doesn't protect

- **Protected:** contents of every message; who can pair (needs the QR code); impersonation after pairing (needs a private key).
- **Not yet:** forward secrecy (a stolen device key decrypts past captured traffic); metadata on the local network (that two devices talk, and message sizes).
- **Planned for version 2:** a per-connection ephemeral key exchange for forward secrecy, and reaching the desktop through Tor onion services when you're not on the same network.
