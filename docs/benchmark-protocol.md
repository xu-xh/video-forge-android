# Video Forge benchmark protocol

This protocol separates client correctness from provider quality. A successful HTTP submission alone is not a quality result.

## Fixed test cases

Use the same prompt and settings for every provider that supports the capability:

1. T2V short: a red kite flying above a calm ocean at sunset, cinematic camera movement
2. T2V motion: a close-up of a glass marble rolling through rain drops, macro photography
3. I2V: one fixed 1024x576 reference image plus the camera slowly pushes in while the subject moves naturally
4. Failure: an intentionally invalid model or workflow field
5. Cancel: submit one job and cancel it while queued or processing

Record provider, adapter, base URL host (never the key), model, workflow revision, app revision, and timestamp.

## Metrics

For every task record:

- submit_latency_ms
- time_to_first_status_ms
- time_to_terminal_ms
- terminal_status
- cancel_requested
- cancel_effective
- output_url_present
- http_error_class
- client_error_class
- retry_count

For generated media, evaluate separately:

- playable file and duration
- requested width and height
- prompt adherence
- temporal consistency
- artifact rate
- human quality score

Do not compare quality across different prompts, workflow revisions, resolutions, or model versions.

## Minimum acceptance gates

- Submit succeeds with the configured base URL and auth settings.
- The app survives a lost polling request and can resume or report a bounded failure.
- Terminal success has a usable output URL or an explicit provider-specific output record.
- Failure and cancellation are represented as distinct job states.
- The API key never appears in logs or persisted plaintext preferences.
- A benchmark record identifies the exact provider contract and workflow revision.

## Evidence format

Store one JSONL record per task plus a human-readable summary. Keep raw provider responses separately from the redacted report. Do not upload local media or credentials to the remote workspace unless explicitly approved.
