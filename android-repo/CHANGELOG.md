# Volumind releases

## Android 0.4.0 / Fusion add-in 3.2.0

- The Fusion pairing code is persisted across restarts.
- The Windows bridge keeps a stable device secret and reconnects automatically.
- Remote commands are queued until the Fusion palette HTML is ready, so the first command is not lost.
- A live green/gray mobile connection lamp is shown in the Fusion settings.
- The Android app shows whether Fusion itself is online, not only whether the relay accepted pairing.
- Android can attach images and text/code files or paste code from the plus menu.
- Relay presence events and payload limits were updated for attachments.
- The hosted Render relay URL is preconfigured in Android and in `run_bridge.ps1`.
