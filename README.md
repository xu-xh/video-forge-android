# Video Forge Android

Remote-first MVP for API-driven video generation.

## Remote source of truth

This project is developed on the ECS machine under:

/root/workspace/video-forge-android

The local workspace is not used as an upload source. Debug APK output is created remotely at:

/root/workspace/video-forge-android/app/build/outputs/apk/debug/app-debug.apk

## Supported adapters

- Generic async REST: POST /videos, then GET /videos/{id}
- ComfyUI: POST /prompt, then GET /history/{prompt_id}; output uses /view

## Security boundary

- API keys are encrypted with Android Keystore AES-GCM.
- Release builds disallow cleartext HTTP; debug permits HTTP for private LAN testing.
- This MVP does not persist generated media locally and does not upload local files.

## Known next steps

- Room-backed durable jobs and WorkManager retry
- ComfyUI WebSocket progress with polling fallback
- Pluggable request schemas per provider
- Download/share flow with explicit user action

## Build and use

The remote source of truth is this directory. With Gradle 8.9 and the Android SDK configured, build with:

/root/gradle-8.9/bin/gradle :app:assembleDebug --no-daemon --console=plain

The debug APK is written to app/build/outputs/apk/debug/app-debug.apk. Open it, select an adapter, enter the base URL and API key, save the profile, then submit a prompt. Generic REST providers must implement the contract in docs/provider-contract.md; ComfyUI additionally requires an API-format workflow JSON.
