# 只读检查：所有 worktree 是否有未提交改动
git worktree list --porcelain | ForEach-Object {
    if ($_ -match '^worktree (.+)$') { $path = $Matches[1] }
    elseif ($_ -match '^branch (.+)$') { $br = $Matches[1] }
    elseif ($_ -eq '' -and $path) {
        if (Test-Path -LiteralPath $path) {
            Push-Location -LiteralPath $path
            $st = git status --porcelain 2>$null
            $n = if ($st) { ($st | Measure-Object -Line).Lines } else { 0 }
            if ($n -gt 0) { "DIRTY ($n)  $br  ->  $path" }
            Pop-Location
        } else {
            "MISSING DIR      $br  ->  $path"
        }
        $path = $null; $br = $null
    }
}
