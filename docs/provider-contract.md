# Provider contract

Video Forge is a client for remote video-generation services. It does not assume that every provider has the same API. Select the adapter that matches the remote service.

## Generic async REST

Configure:

- Base URL: the service root, for example https://example.invalid/v1
- API key: stored locally through Android Keystore
- Auth header: default Authorization
- Auth prefix: default Bearer
- Model: optional unless the provider requires it

The adapter sends:

POST {baseUrl}/videos

    {
      "prompt": "a slow cinematic shot of a red kite over the sea",
      "model": "provider-model",
      "width": 1024,
      "height": 576,
      "duration": 5,
      "size": "1024x576"
    }

The submit response must expose one of id, request_id, job_id, or prediction_id.

The adapter polls:

GET {baseUrl}/videos/{id}

The status response should expose status or state. The client maps queued, pending, submitted, processing, running, in_progress, completed, succeeded, failed, and cancelled. The output response should expose one of output_url, video_url, or url.

This contract is intentionally close to asynchronous video APIs used by Sora-style clients, but it is not a claim that every provider accepts the same request fields. Providers with a different endpoint or schema need a dedicated adapter.

## ComfyUI

Configure:

- Base URL: the ComfyUI HTTP root, for example http://10.0.0.20:8188
- API key: optional; sent using the selected auth header and prefix
- Workflow JSON: the API-format workflow exported with File -> Export (API)

The adapter sends:

POST {baseUrl}/prompt

    {
      "prompt": { "node-id": { "class_type": "...", "inputs": {} } },
      "client_id": "generated-client-id"
    }

It polls:

GET {baseUrl}/history/{prompt_id}

Completed video outputs are converted into ComfyUI /view URLs. Cancellation uses POST {baseUrl}/interrupt.

For a reusable workflow, expose prompt, seed, size, duration, and reference-media inputs in the exported workflow before using it from Android.

## Security

Use HTTPS for release builds. Debug builds permit HTTP for private-network ComfyUI testing. Never put a provider key in source code, logs, screenshots, or Git history. Direct BYOK is intended for personal/developer use; a multi-user product should keep provider credentials on a backend.
