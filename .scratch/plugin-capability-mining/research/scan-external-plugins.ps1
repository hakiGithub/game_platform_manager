# 扫描 platform 主仓库之外的独立插件仓库
$roots = @(
    'D:\program\ai\plugin-7dtd',
    'D:\program\ai\plugin-dnf-tw',
    'D:\program\ai\plugin-dst'
)

foreach ($r in $roots) {
    if (-not (Test-Path -LiteralPath $r)) { "=== $r MISSING"; continue }
    "=========================================="
    "=== $r"
    "=========================================="

    # plugin.properties
    $pp = Get-ChildItem -Path $r -Recurse -Filter 'plugin.properties' -File -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -notmatch 'target' } | Select-Object -First 1
    if ($pp) {
        "--- plugin.properties ($($pp.FullName)) ---"
        Get-Content -LiteralPath $pp.FullName | Where-Object { $_ -match 'plugin\.|gameCode|basePackage' }
    }

    # 后端 Java 规模
    $j = Get-ChildItem -Path $r -Recurse -Filter '*.java' -File -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -notmatch 'target' }
    $loc = ($j | ForEach-Object { (Get-Content -LiteralPath $_.FullName | Measure-Object -Line).Lines } |
        Measure-Object -Sum).Sum
    "--- 后端: $($j.Count) 个 Java 文件 / $loc 行 ---"

    # Controller
    $ctrls = $j | Where-Object { $_.Name -like '*Controller.java' }
    "--- Controller ($($ctrls.Count)): $(($ctrls | ForEach-Object { $_.Name -replace 'Controller\.java','' } | Sort-Object) -join ', ') ---"

    # Extension 里的菜单
    $ext = $j | Where-Object { $_.Name -like '*Extension.java' }
    foreach ($e in $ext) {
        $m = Get-Content -LiteralPath $e.FullName | Select-String -Pattern '\.title\(|\.path\(|getMenus'
        if ($m) {
            "--- 菜单 ($($e.Name)) ---"
            $m | ForEach-Object { $_.Line.Trim() }
        }
    }

    # 前端页面
    $pages = Get-ChildItem -Path (Join-Path $r 'frontend\src') -Recurse -Include '*.vue' -File -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -notmatch 'test|node_modules' }
    if ($pages) {
        "--- 前端页面 ($($pages.Count)): $(($pages | ForEach-Object { $_.BaseName } | Sort-Object) -join ', ') ---"
    }
    ""
}
