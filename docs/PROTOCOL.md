# Volumind Remote protocol

All internet traffic uses JSON over `wss://`. The relay accepts at most 5 MiB per message.

## Pairing

Desktop authenticates first with `role=desktop`, a random 32-character device secret and a six-digit pairing code. Mobile uses the same one-time code within ten minutes. The code is deleted immediately after pairing.

## Mobile to Fusion

- `chat.command` — a natural-language modeling request.
- `questionnaire.answer` — selected answers keyed by question id.
- `build.stop` — cancel the current generation/build session.

## Fusion to mobile

- `chat.message` — assistant status or result.
- `questionnaire` — 1–3 questions and their options.
- `build.plan` — ordered plan titles.
- `build.step` — `waiting`, `running`, `done`, or `error`.
- `fusion.screenshot` — data URL or HTTPS URL plus a caption.
- `error` — a safe user-facing failure message.

The public relay does not call Fusion or Ollama. The Windows bridge connects outward to WSS and talks to the Fusion add-in only at `127.0.0.1:8765`.

