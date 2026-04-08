param(
    [Parameter(Mandatory = $true)]
    [string]$LogDir,

    [string]$OutFile = "",

    [int]$Context = 2,

    [switch]$IncludeDebug,

    [switch]$IncludeGz
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if (-not (Test-Path -LiteralPath $LogDir)) {
    throw "Log directory does not exist: $LogDir"
}

$patterns = @(
    "\[voxyworldgenv2/\]",
    "voxy world gen v2 initializing",
    "voxy networking initialized",
    "server started, initializing manager",
    "voxy world gen initialized",
    "server stopping, shutting down manager",
    "loaded .* chunks from voxy generation cache",
    "failed to load chunk generation cache",
    "tellus world detected",
    "tellus integration hub initialized",
    "error in worker loop",
    "worker idle:",
    "worker paused:",
    "worker generation dispatch:",
    "worker sync dispatch:",
    "failed to handle LOD data",
    "failed to ingest chunk",
    "failed to raw ingest"
)

$regex = ($patterns -join "|")

$files = @("latest.log")
if ($IncludeDebug) {
    $files += "debug.log"
}

$results = New-Object System.Collections.Generic.List[string]

foreach ($name in $files) {
    $path = Join-Path $LogDir $name
    if (-not (Test-Path -LiteralPath $path)) {
        $results.Add("--- missing file: $path")
        continue
    }

    $results.Add("=== file: $path")

    if (Get-Command rg -ErrorAction SilentlyContinue) {
        $rgOut = rg -n -i -C $Context $regex $path
        if ($LASTEXITCODE -eq 0 -and $rgOut) {
            foreach ($line in $rgOut) {
                $results.Add($line)
            }
        } else {
            $results.Add("(no matches)")
        }
    } else {
        $matches = Select-String -Path $path -Pattern $regex -CaseSensitive:$false -Context $Context, $Context
        if (-not $matches) {
            $results.Add("(no matches)")
        } else {
            foreach ($m in $matches) {
                foreach ($pre in $m.Context.PreContext) {
                    $results.Add($pre)
                }
                $results.Add("$($m.Path):$($m.LineNumber):$($m.Line)")
                foreach ($post in $m.Context.PostContext) {
                    $results.Add($post)
                }
            }
        }
    }

    $results.Add("")
}

if ($IncludeGz) {
    $gzFiles = Get-ChildItem -Path $LogDir -Filter "*.log.gz" -File -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 3

    foreach ($gz in $gzFiles) {
        $results.Add("=== skipped compressed file (set up manual extraction if needed): $($gz.FullName)")
    }
}

if ([string]::IsNullOrWhiteSpace($OutFile)) {
    $timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $OutFile = Join-Path $PWD "voxy-log-extract-$timestamp.txt"
}

$results | Set-Content -LiteralPath $OutFile -Encoding UTF8
Write-Output "Wrote extract: $OutFile"
