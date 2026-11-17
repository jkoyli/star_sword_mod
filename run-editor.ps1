# 星辉剑机制编辑器启动器（PowerShell 版，负责挑对 JDK）
#
# 为什么需要它：Windows 上 .jar 的文件关联经常指向一个很老的 Java（本机就是 JRE 1.8），
# 双击 jar 会用 Java 8 去跑编译自 Java 17 的类，直接弹 "A Java Exception has occurred."
# 这个脚本先按优先级找一个 >= 17 的 JDK，再用它的 javaw 启动编辑器。

# 注意用 Continue 而不是 Stop：原生命令（java -version / cmd）会把版本号写到 stderr，
# 在 Stop 偏好下会被当成终止性错误，导致「明明装了 17 却报找不到」。
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$jar = Join-Path $root 'mechanic-editor\build\libs\star_sword_mechanic_editor-java17.jar'
$gradlew = Join-Path $root 'gradlew.bat'

function Get-JavaMajor([string]$exe) {
    # 注意：这里不要用 `| Select-Object -First 1` 去截断原生命令的 stderr 流。
    # PowerShell 5.1 下提前掐断管道会抛 PipelineStoppedException，被 catch 吞掉后
    # 就会把「明明是 17」误判成「找不到 JDK」——本脚本第一版正是踩了这个坑。
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $raw = & $exe -version 2>&1
        foreach ($line in $raw) {
            $text = [string]$line
            if ($text -match 'version "(?<v>\d+)') { return [int]$Matches['v'] }
        }
    } catch { } finally { $ErrorActionPreference = $previous }
    return 0
}

function Find-Jdk {
    $candidates = New-Object System.Collections.Generic.List[string]
    if ($env:JAVA_HOME) { $candidates.Add($env:JAVA_HOME) }

    $roots = @(
        'C:\Program Files\Java',
        'C:\Program Files\Eclipse Adoptium',
        'C:\Program Files\Microsoft',
        'C:\Program Files\Zulu',
        'C:\Program Files\BellSoft',
        "$env:LOCALAPPDATA\Programs\Eclipse Adoptium"
    )
    foreach ($r in $roots) {
        if (Test-Path $r) {
            Get-ChildItem $r -Directory -ErrorAction SilentlyContinue |
                Where-Object { $_.Name -match 'jdk|jre' } |
                Sort-Object Name -Descending |
                ForEach-Object { $candidates.Add($_.FullName) }
        }
    }
    $onPath = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($onPath) { $candidates.Add((Split-Path -Parent (Split-Path -Parent $onPath.Source))) }

    $valid = @()
    foreach ($jdkHome in $candidates) {
        $javaExe = Join-Path $jdkHome 'bin\java.exe'
        if (-not (Test-Path $javaExe)) { continue }
        $major = Get-JavaMajor $javaExe
        if ($major -lt 17) { continue }
        $exe = if (Test-Path (Join-Path $jdkHome 'bin\javaw.exe')) {
            Join-Path $jdkHome 'bin\javaw.exe'
        } else { $javaExe }
        $valid += [pscustomobject]@{ JavaHome = $jdkHome; Exe = $exe; Major = $major }
    }
    if ($valid.Count -eq 0) { return $null }

    # 优先 17..24（Gradle 8.14 支持的上限是 24）；没有就退回任意 >= 17 的最低版本。
    $preferred = $valid | Where-Object { $_.Major -le 24 } | Sort-Object Major | Select-Object -First 1
    if (-not $preferred) { $preferred = $valid | Sort-Object Major | Select-Object -First 1 }
    return $preferred
}

$jdk = Find-Jdk
if (-not $jdk) {
    Write-Host ''
    Write-Host '找不到 Java 17 或更高版本。' -ForegroundColor Red
    Write-Host '编辑器与游戏都需要 JDK 17（本目录开发用 C:\Program Files\Microsoft\jdk-17.0.11.9-hotspot）。'
    Write-Host '安装一个 JDK 17 后重试，或设置 JAVA_HOME 指向它。'
    Write-Host ''
    Read-Host '按回车退出'
    exit 1
}

Write-Host ("使用 JDK {0}：{1}" -f $jdk.Major, $jdk.JavaHome) -ForegroundColor DarkGray

if ($env:STAR_SWORD_EDITOR_DRYRUN -eq '1') {
    # 仅用于自检：只打印挑中的 JDK，不启动界面。
    Write-Host ("jar: {0} ({1})" -f $jar, (Test-Path $jar))
    exit 0
}

if (Test-Path $jar) {
    # 快路径：直接跑已构建好的 jar，不经过 Gradle（也绕开 .jar 的文件关联）。
    Start-Process -FilePath $jdk.Exe -ArgumentList @('-jar', "`"$jar`"") -WorkingDirectory $root
    exit 0
}

# 慢路径：还没有构建产物，用这个 JDK 让 Gradle 构建并运行。
if (-not (Test-Path $gradlew)) {
    Write-Host "找不到 $jar，也找不到 gradlew.bat。" -ForegroundColor Red
    Read-Host '按回车退出'
    exit 1
}

Write-Host '还没有构建编辑器，正在用 Gradle 构建并运行（首次会下载依赖）…'
& $jdk.Exe -version | Out-Null
$env:JAVA_HOME = $jdk.JavaHome
& cmd /c "`"$gradlew`" :mechanic-editor:run -Dorg.gradle.java.home=`"$($jdk.JavaHome)`" --console=plain"
if ($LASTEXITCODE -ne 0) {
    Write-Host ''
    Write-Host "Gradle 运行失败（退出码 $LASTEXITCODE）。" -ForegroundColor Red
    Read-Host '按回车退出'
}
