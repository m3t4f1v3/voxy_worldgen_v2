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
        # rg: no context (to preserve consecutiveness for deduplication)
        $rgOut = rg -n -i $regex $path
        if ($LASTEXITCODE -eq 0 -and $rgOut) {
            foreach ($line in $rgOut) {
                $results.Add($line)
            }
        } else {
            $results.Add("(no matches)")
        }
    } else {
        # Select-String fallback: just matches, no context (to preserve consecutiveness)
        $matches = Select-String -Path $path -Pattern $regex -CaseSensitive:$false
        if (-not $matches) {
            $results.Add("(no matches)")
        } else {
            foreach ($m in $matches) {
                $results.Add("$($m.Path):$($m.LineNumber):$($m.Line)")
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

# Compact consecutive identical log lines (ignoring timestamps)
$compacted = New-Object System.Collections.Generic.List[string]
$previousKey = $null
$duplicateCount = 0

foreach ($line in $results) {
    # Skip section headers and empty lines
    if ($line -match "^(===|---|)") {
        if ($duplicateCount -gt 1) {
            $compacted.Add("^^^ (×$duplicateCount)")
        }
        $compacted.Add($line)
        $previousKey = $null
        $duplicateCount = 0
        continue
    }

    if ([string]::IsNullOrWhiteSpace($line)) {
        if ($duplicateCount -gt 1) {
            $compacted.Add("^^^ (×$duplicateCount)")
        }
        $compacted.Add($line)
        $previousKey = $null
        $duplicateCount = 0
        continue
    }

    # Extract message without filepath prefix and timestamp
    # Remove everything up to and including the final ]: pattern
    $key = ($line -replace ".*\]:\s*", "").Trim()
    
    if ($key -eq $previousKey) {
        $duplicateCount++
    } else {
        if ($duplicateCount -gt 1) {
            $compacted.Add("^^^ (×$duplicateCount)")
        }
        $compacted.Add($line)
        $previousKey = $key
        $duplicateCount = 1
    }
}

# Compact consecutive identical log lines by grouping and outputting counts
$final = @()
$skipGroup = $false
$groupedLines = @()

foreach ($line in $compacted) {
    # Always output headers/markers directly
    if ($line -match "^(===|---|^\^\^\^)" -or [string]::IsNullOrWhiteSpace($line)) {
        # Output any pending group first
        if ($groupedLines.Count -gt 0) {
            if ($groupedLines.Count -gt 1) {
                $final += $groupedLines[0]
                $final += "^^^ (×$($groupedLines.Count))"
            } else {
                $final += $groupedLines[0]
            }
            $groupedLines = @()
        }
        $final += $line
    } else {
        # Extract message for grouping
        $msg = $line -replace ".*]:\s*", ""
        
        # If this message differs from previous group, flush the old group
        if ($groupedLines.Count -gt 0) {
            $lastMsg = $groupedLines[0] -replace ".*]:\s*", ""
            if ($msg -ne $lastMsg) {
                # Different message, flush old group
                if ($groupedLines.Count -gt 1) {
                    $final += $groupedLines[0]
                    $final += "^^^ (×$($groupedLines.Count))"  
                } else {
                    $final += $groupedLines[0]
                }
                $groupedLines = @()
            }
        }
        
        # Add to current group
        $groupedLines += $line
    }
}

# Handle final group
if ($groupedLines.Count -gt 0) {
    if ($groupedLines.Count -gt 1) {
        $final += $groupedLines[0]
        $final += "^^^ (×$($groupedLines.Count))"
    } else {
        $final += $groupedLines[0]
    }
}

if ([string]::IsNullOrWhiteSpace($OutFile)) {
    $timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $OutFile = Join-Path $PWD "voxy-log-extract-$timestamp.txt"
}

$final | Set-Content -LiteralPath $OutFile -Encoding UTF8
Write-Output "Wrote extract: $OutFile"
