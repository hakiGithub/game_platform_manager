$helper = Join-Path (Split-Path -Parent $PSScriptRoot) "backend-process.ps1"
. $helper

Describe "Backend process identification" {
    BeforeAll {
        $script:fixtureDir = Join-Path $env:TEMP "game-platform-process-test-$PID"
        New-Item -ItemType Directory -Path $fixtureDir -Force | Out-Null

        $sourceFile = Join-Path $fixtureDir "BackendProcessFixture.java"
        @"
public class BackendProcessFixture {
    public static void main(String[] args) throws Exception { Thread.sleep(15000); }
}
"@ | Set-Content -Path $sourceFile -Encoding ASCII

        & javac $sourceFile
        if ($LASTEXITCODE -ne 0) {
            throw "javac failed"
        }

        $script:argFile = Join-Path $fixtureDir "game-platform-jvm-args-test.txt"
        @(
            "-cp",
            $fixtureDir,
            "BackendProcessFixture"
        ) | Set-Content -Path $argFile -Encoding ASCII

        $javaExe = (Get-Command java).Source
        $script:wrapperFile = Join-Path $fixtureDir "launch.bat"
        @(
            "@echo off",
            "start `"`" `"$javaExe`" @`"$argFile`"",
            "exit /b 0"
        ) | Set-Content -Path $wrapperFile -Encoding ASCII

        $script:wrapper = Start-Process -FilePath $wrapperFile `
            -WorkingDirectory $fixtureDir `
            -WindowStyle Hidden `
            -PassThru
        Wait-Process -Id $wrapper.Id -Timeout 10 -ErrorAction SilentlyContinue

        $script:javaProcess = $null
        for ($i = 0; $i -lt 20; $i++) {
            $script:javaProcess = Find-BackendJavaProcess -ArgFile $argFile -MainClass "BackendProcessFixture"
            if ($javaProcess) { break }
            Start-Sleep -Milliseconds 250
        }
    }

    AfterAll {
        if ($script:javaProcess) {
            Stop-Process -Id $javaProcess.ProcessId -Force -ErrorAction SilentlyContinue
        }
        Remove-Item $fixtureDir -Recurse -Force -ErrorAction SilentlyContinue
    }

    It "ignores a non-Java process that reuses a stale PID" {
        $currentProcess = Get-CimInstance Win32_Process -Filter "ProcessId = $PID"
        Test-BackendJavaProcess -ProcessInfo $currentProcess | Should Be $false
    }

    It "rejects an argfile that does not exist" {
        $missingArgFile = Join-Path $fixtureDir "missing-jvm-args.txt"
        $processInfo = [pscustomobject]@{
            Name = "java.exe"
            CommandLine = "java.exe @`"$missingArgFile`""
        }
        Test-BackendJavaProcess -ProcessInfo $processInfo -ArgFile $missingArgFile | Should Be $false
    }

    It "recognizes a production jar process from its command line" {
        $processInfo = [pscustomobject]@{
            Name = "java.exe"
            CommandLine = 'java.exe -Xmx384m -jar "D:\game_platform_manger\backend\target\game-platform-manager.jar" --server.port=8080'
        }
        Test-BackendJavaProcess -ProcessInfo $processInfo | Should Be $true
    }

    It "finds Java after its cmd.exe wrapper exits" {
        $javaProcess | Should Not BeNullOrEmpty
        (Get-Process -Id $wrapper.Id -ErrorAction SilentlyContinue) | Should BeNullOrEmpty
        [bool]$javaProcess | Should Be $true
        $javaProcess.CommandLine.Contains($argFile) | Should Be $true
        Test-BackendJavaProcess -ProcessInfo $javaProcess -MainClass "BackendProcessFixture" -ArgFile $argFile | Should Be $true
    }
}
