# Shared process identification helpers for the Windows backend launcher.

function Test-BackendJavaProcess {
    param(
        [Parameter(Mandatory = $true)]
        [object]$ProcessInfo,

        [string]$MainClass = "com.gameplatform.GamePlatformApplication",

        [string]$ArgFile = ""
    )

    if (-not $ProcessInfo -or $ProcessInfo.Name -notmatch '^java(\.exe)?$') {
        return $false
    }

    $commandLine = [string]$ProcessInfo.CommandLine
    if (-not $commandLine) {
        return $false
    }

    $resolvedArgFile = $ArgFile
    if (-not $resolvedArgFile) {
        # Java accepts a long classpath via @argfile, so the main class is not
        # part of CommandLine. Extract the referenced argfile and inspect it.
        $argFileMatch = [regex]::Match($commandLine, '@"([^"]+)"')
        if ($argFileMatch.Success) {
            $resolvedArgFile = $argFileMatch.Groups[1].Value
        }
    }

    # Some launchers inline the main class instead of using an argfile.
    if (-not $resolvedArgFile) {
        $hasMainClass = $commandLine.Contains($MainClass, [StringComparison]::OrdinalIgnoreCase)
        $hasSpringBootJar = $commandLine -match '(?i)(^|\s)-jar\s+"?[^"]*game-platform-manager\.jar"?'
        return $hasMainClass -or $hasSpringBootJar
    }

    if (-not $commandLine.Contains($resolvedArgFile, [StringComparison]::OrdinalIgnoreCase) -or
        -not (Test-Path -LiteralPath $resolvedArgFile -PathType Leaf)) {
        return $false
    }

    try {
        $argFileContent = Get-Content -LiteralPath $resolvedArgFile -Raw -ErrorAction Stop
        return $argFileContent.Contains($MainClass, [StringComparison]::OrdinalIgnoreCase)
    } catch {
        return $false
    }
}

function Get-BackendProcessByPid {
    param(
        [Parameter(Mandatory = $true)]
        [int]$ProcessId,

        [string]$MainClass = "com.gameplatform.GamePlatformApplication",

        [string]$ArgFile = ""
    )

    $processInfo = Get-CimInstance Win32_Process -Filter "ProcessId = $ProcessId" -ErrorAction SilentlyContinue
    if (Test-BackendJavaProcess -ProcessInfo $processInfo -MainClass $MainClass -ArgFile $ArgFile) {
        return $processInfo
    }
    return $null
}

function Find-BackendJavaProcess {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ArgFile,

        [string]$MainClass = "com.gameplatform.GamePlatformApplication"
    )

    Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" -ErrorAction SilentlyContinue |
        Where-Object {
            Test-BackendJavaProcess -ProcessInfo $_ -MainClass $MainClass -ArgFile $ArgFile
        } |
        Select-Object -First 1
}

function Get-PortListenerProcessId {
    param(
        [Parameter(Mandatory = $true)]
        [int]$Port
    )

    $listener = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($listener) {
        return [int]$listener.OwningProcess
    }
    return 0
}
