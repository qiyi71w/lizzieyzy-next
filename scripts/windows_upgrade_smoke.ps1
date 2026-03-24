param(
    [Parameter(Mandatory = $true)]
    [string]$InputDir,

    [Parameter(Mandatory = $true)]
    [string]$MainJar,

    [Parameter(Mandatory = $true)]
    [string]$IconPath,

    [Parameter(Mandatory = $true)]
    [string]$UpgradeUuid,

    [string]$AppName = "LizzieYzy Next",

    [string]$MainClass = "featurecat.lizzie.Lizzie",

    [string]$Description = "LizzieYzy maintained fork with restored Fox nickname sync",

    [string]$Vendor = "wimi321",

    [string]$VersionOld = "2.6.8301",

    [string]$VersionNew = "2.6.8302",

    [string]$SmokeInstallDir = "LizzieYzyNextSmoke",

    [string]$SmokeConfigDir = "$env:USERPROFILE\.lizzieyzy-next",

    [string]$RequiredLogDir = "gtp_logs"
)

$ErrorActionPreference = "Stop"

function Invoke-JPackageMsiBuild {
    param(
        [string]$Version,
        [string]$DestDir
    )

    New-Item -ItemType Directory -Force -Path $DestDir | Out-Null

    $arguments = @(
        "--type", "msi",
        "--name", $AppName,
        "--input", $InputDir,
        "--main-jar", $MainJar,
        "--main-class", $MainClass,
        "--dest", $DestDir,
        "--app-version", $Version,
        "--vendor", $Vendor,
        "--description", $Description,
        "--icon", $IconPath,
        "--win-menu",
        "--win-shortcut",
        "--win-per-user-install",
        "--win-upgrade-uuid", $UpgradeUuid,
        "--install-dir", $SmokeInstallDir,
        "--java-options", "-Xmx4096m"
    )

    & jpackage @arguments
    if ($LASTEXITCODE -ne 0) {
        throw "jpackage failed for version $Version"
    }

    $msi = Get-ChildItem -LiteralPath $DestDir -Filter *.msi | Select-Object -First 1
    if (-not $msi) {
        throw "No MSI was generated in $DestDir"
    }
    return $msi.FullName
}

function Invoke-MsiInstall {
    param(
        [string]$MsiPath,
        [string]$LogPath
    )

    $arguments = "/i `"$MsiPath`" /qn /norestart /l*v `"$LogPath`""
    $process = Start-Process -FilePath "msiexec.exe" -ArgumentList $arguments -Wait -PassThru
    if ($process.ExitCode -ne 0) {
        if (Test-Path -LiteralPath $LogPath) {
            Write-Host "MSI log ($LogPath):"
            Get-Content -LiteralPath $LogPath -Tail 200 -ErrorAction SilentlyContinue
        }
        throw "msiexec failed for $MsiPath with exit code $($process.ExitCode)"
    }
}

function Find-InstalledAppExe {
    $roots = @(
        (Join-Path $env:LOCALAPPDATA "Programs"),
        $env:LOCALAPPDATA,
        $env:ProgramFiles,
        ${env:ProgramFiles(x86)}
    ) | Where-Object { $_ -and (Test-Path -LiteralPath $_) }

    foreach ($root in $roots) {
        $match = Get-ChildItem -LiteralPath $root -Filter "$AppName.exe" -Recurse -ErrorAction SilentlyContinue |
            Where-Object { $_.FullName -like "*$SmokeInstallDir*" } |
            Select-Object -First 1
        if ($match) {
            return $match.FullName
        }
    }

    throw "Installed application executable was not found after MSI upgrade test."
}

$tempRoot = Join-Path $env:RUNNER_TEMP "lizzieyzy-next-msi-smoke"
$oldDest = Join-Path $tempRoot "old"
$newDest = Join-Path $tempRoot "new"
$logsDir = Join-Path $tempRoot "logs"

Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $logsDir | Out-Null

$msiOld = Invoke-JPackageMsiBuild -Version $VersionOld -DestDir $oldDest
$msiNew = Invoke-JPackageMsiBuild -Version $VersionNew -DestDir $newDest

Invoke-MsiInstall -MsiPath $msiOld -LogPath (Join-Path $logsDir "install-old.log")
Invoke-MsiInstall -MsiPath $msiNew -LogPath (Join-Path $logsDir "install-new.log")

$appExe = Find-InstalledAppExe
Write-Host "Installed exe: $appExe"

& (Join-Path $PSScriptRoot "windows_smoke_test.ps1") `
    -AppExe $appExe `
    -ConfigDir $SmokeConfigDir `
    -RequiredLogDir $RequiredLogDir `
    -WaitSeconds 60

if ($LASTEXITCODE -ne 0) {
    throw "Installed app smoke test failed after MSI upgrade."
}
