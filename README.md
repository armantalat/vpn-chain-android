# VPN Chain for Android (Root)

An open-source Android application (Jetpack Compose + pure-Go native core) that chains a **built-in userspace PPTP client** (MS-CHAPv2 + MPPE-128) with **any second VPN client running in local Proxy mode** (SOCKS5 or HTTP), tunneling all other device traffic through the second VPN while supporting **per-app and per-site Exclude (Split Tunneling)** rules.

Works even on **Android 12, 13, 14, 15, and 16** where Android removed built-in PPTP support and kernel PPTP/MPPE modules.

```text
  [All Phone Apps]
         |
         v  Android VpnService (tun0) -> tun2socks -> Built-in SmartProxy (TCP + DNS-over-TCP)
  [2nd VPN Local Proxy (e.g. 127.0.0.1:10810)]
         |
         v  Linux ip rule (UID-based policy routing -> table 101, zero loops on server switch!)
  [Userspace PPTP Client (pptp0, MS-CHAPv2 + MPPE-128)]
         |
         v
  [PPTP Server] ---> Internet ---> [2nd VPN Remote Server]

  [Excluded Apps / Sites]
    |-- Base VPN (PPTP only)  --> pptp0 (bypasses 2nd VPN)
    +-- Direct (No VPN)       --> Physical Wi-Fi / Mobile Data (bypasses both VPNs)
```

---

## Requirements

- **Rooted Android device** (Magisk or KernelSU) — required for raw GRE (`IPPROTO_GRE = 47`), `/dev/tun` interface `pptp0`, and `ip rule` UID policy routing.
- **A second VPN client** configured in **Proxy-Only mode** (no TUN) listening on a local port (default `127.0.0.1:10810`, SOCKS5 or HTTP).

---

## Features

- **Userspace PPTP Engine (Android 12–16 Compatible):** Pure-Go RFC 2637 PPTP + PPP + LCP + **MS-CHAPv2** (plus CHAP-MD5 / PAP) + **MPPE-128** (stateless & stateful) + IPCP. Zero dependency on missing `pppd` or kernel PPTP modules.
- **Anti-Loop UID Routing:** Routes only the selected 2nd VPN app's UID through `pptp0` (`table 101`). Switching servers or countries in your 2nd VPN never causes routing loops and requires no manual IP pinning.
- **Smart 2-Stage Connect Flow:** Connects PPTP first so your 2nd VPN immediately gains internet access over the corporate intranet, waits automatically for the 2nd VPN proxy port to open, then activates the system-wide TUN tunnel.
- **SmartProxy (SOCKS5 / HTTP + DNS-over-TCP):** Automatically detects SOCKS5 or HTTP CONNECT on your 2nd VPN's local port and transparently converts UDP port 53 DNS queries into DNS-over-TCP through the 2nd VPN, ensuring DNS works reliably even on HTTP-only proxies.
- **Exclude / Split Tunneling:** Route specific installed apps (picked from a searchable app list with icons) or websites/IPs/CIDRs either via **Base VPN (PPTP only)** or **Direct (No VPN)**.

---

## How to Build the APK (No Android Studio Needed!)

1. Push this repository to GitHub (Public or Private).
2. Open the **Actions** tab on GitHub — the **`Build Android APK (Go Core + Compose UI)`** workflow runs automatically:
   - Compiles the pure-Go `core/` engine (`libchaincore.so`) for `arm64-v8a` and `armeabi-v7a`.
   - Compiles `xjasonlyu/tun2socks` (`v2.5.2`, `libtun2socks.so`) for `arm64-v8a` and `armeabi-v7a`.
   - Builds **`VPNChain-Android.apk`** using Gradle 8.7 + JDK 17.
3. Download **`VPNChain-Android-APK`** from the workflow run's **Artifacts** section and install it on your phone.
4. *(Optional)* Push a tag like `git tag v1.0.0 && git push origin v1.0.0` to automatically publish the APK under **GitHub Releases**.

---

## Usage on Phone

1. Open **VPN Chain** and grant **Root** permission when Magisk/KernelSU prompts.
2. Under **1. Base VPN (PPTP)**, enter your PPTP server IP/domain, username, password, and verify **MS-CHAPv2** + **MPPE-128**.
3. Under **2. Second VPN App**, tap to pick your 2nd VPN app and verify its local proxy port (default `10810`).
4. *(Optional)* Open the **Exclude** tab to add apps or intranet subnets (`10.0.0.0/8`, `192.168.0.0/16`) that should go via **Base VPN** or **Direct**.
5. Tap **Start VPN Chain**:
   - PPTP connects and pins your 2nd VPN app to `pptp0`.
   - If your 2nd VPN is not connected yet, tap **Open [2nd VPN App]** and connect it in **Proxy-Only** mode (TUN OFF) on port `10810`.
   - As soon as port `10810` opens, **VPN Chain** starts `tun0` automatically and shows **`CHAIN ACTIVE`**.
6. Tap **Disconnect Chain** when finished to cleanly close PPTP, TUN, and remove all `ip rule`s.

---

## License

MIT License — see [LICENSE](LICENSE).
