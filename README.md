# Android Capture

Private, offline-first Android app that captures voice notes, links, text and files into an Obsidian vault inbox.

- Records audio and saves it first, then transcribes it on the phone (ONNX Runtime, Whisper-small int8).
- Writes Markdown notes and attachments directly into the vault through Android's Storage Access Framework.
- No INTERNET permission, no accounts, no telemetry.

Status: phase 1 (proof of concept). See [docs/feasibility-report.md](docs/feasibility-report.md).

This repository contains app code only. It must never contain vault notes or real captures.

Licence: private, all rights reserved (for now).
