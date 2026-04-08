# AGENTS

## Log Extraction Helper

Use [scripts/extract-voxy-logs.ps1](scripts/extract-voxy-logs.ps1) to extract only relevant Voxy World Gen diagnostics from server or client logs.

### Why this exists

The full logs are very noisy. This script focuses on lines related to:
- manager startup/shutdown
- cache load/persistence
- worker loop state (idle, paused, dispatch)
- LOD ingest/send errors

### Usage

From the repository root:

```powershell
./scripts/extract-voxy-logs.ps1 -LogDir "C:\path\to\logs" -IncludeDebug
```

Optional output path:

```powershell
./scripts/extract-voxy-logs.ps1 -LogDir "C:\path\to\logs" -IncludeDebug -OutFile "C:\temp\voxy-extract.txt"
```

Notes:
- If `rg` is installed, extraction is faster and cleaner.
- If `rg` is not installed, the script falls back to `Select-String`.
- `*.log.gz` files are not parsed by default.

## Recommended Debug Config

For diagnostics, set this in your `config/voxyworldgenv2.json`:

```json
{
  "enabled": true,
  "showF3MenuStats": true,
  "enableFlowLogs": true,
  "generationRadius": 128,
  "update_interval": 20,
  "maxQueueSize": 20000,
  "maxActiveTasks": 8
}
```

Why `maxActiveTasks` is `8` during diagnostics:
- keeps server load more stable while still proving whether generation dispatch is happening
- makes log interpretation easier than with very high concurrency

After diagnostics, you can set:
- `enableFlowLogs` back to `false`
- `maxActiveTasks` back to your preferred runtime value

## Related Docs

- [FLOW_NOTES.md](FLOW_NOTES.md)
