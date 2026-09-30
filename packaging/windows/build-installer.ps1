<#
.SYNOPSIS
  Construit l'installateur Windows de FinanceApp avec jpackage (JDK 21+).

.DESCRIPTION
  1. Compile le projet (mvn package, tests ignores : lancez "mvn test" avant).
  2. Assemble un runtime Java minimal avec jlink : l'utilisateur n'a PAS besoin
     d'installer Java.
  3. Produit l'installateur (.msi par defaut, ou .exe) dans
     financeapp-desktop\target\installer.

  Prerequis : JDK 21 (jpackage, jlink dans le PATH ou JAVA_HOME), Maven,
  WiX Toolset 3.x (candle.exe / light.exe dans le PATH) pour msi et exe.
  L'installation se fait par utilisateur (aucun droit administrateur requis) ;
  les donnees restent dans %APPDATA%\financeapp et ne sont jamais supprimees
  par une mise a jour ou une desinstallation.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File packaging\windows\build-installer.ps1
  powershell -ExecutionPolicy Bypass -File packaging\windows\build-installer.ps1 -Type exe -SkipBuild
#>
param(
    [ValidateSet("msi", "exe", "app-image")]
    [string]$Type = "msi",
    [switch]$SkipBuild
)
$ErrorActionPreference = "Stop"

$root = (Resolve-Path "$PSScriptRoot\..\..").Path
$desktop = Join-Path $root "financeapp-desktop"
$target = Join-Path $desktop "target"

function Tool([string]$name) {
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\$name.exe"))) {
        return (Join-Path $env:JAVA_HOME "bin\$name.exe")
    }
    $cmd = Get-Command $name -ErrorAction SilentlyContinue
    if (-not $cmd) { throw "$name introuvable : installez un JDK 21+ et definissez JAVA_HOME" }
    return $cmd.Source
}

# Nom (centralise dans application.properties) et version (pom parent, sans -SNAPSHOT)
$props = Get-Content (Join-Path $desktop "src\main\resources\application.properties") -Encoding UTF8
$appName = (($props | Where-Object { $_ -match '^app\.name=' }) -replace '^app\.name=', '').Trim()
[xml]$pom = Get-Content (Join-Path $root "pom.xml")
$version = ($pom.project.version -replace '-SNAPSHOT', '')
Write-Host "==> $appName $version ($Type)"

if (-not $SkipBuild) {
    Push-Location $root
    try {
        mvn -B -q package -DskipTests
        if ($LASTEXITCODE -ne 0) { throw "Echec de la compilation Maven" }
    } finally { Pop-Location }
}

$jar = Join-Path $target "financeapp-desktop.jar"
if (-not (Test-Path $jar)) { throw "$jar introuvable : lancez la compilation" }

# Entree de jpackage : le jar principal et ses dependances (lib\)
$inputDir = Join-Path $target "jpackage-input"
$runtime = Join-Path $target "jpackage-runtime"
$dest = Join-Path $target "installer"
foreach ($d in @($inputDir, $runtime)) { if (Test-Path $d) { Remove-Item -Recurse -Force $d } }
New-Item -ItemType Directory -Force -Path $inputDir, $dest | Out-Null
Copy-Item $jar $inputDir
Copy-Item -Recurse (Join-Path $target "lib") $inputDir

# Runtime Java minimal (modules calcules avec jdeps, plus locales, jeux de caracteres et TLS)
$modules = (Get-Content (Join-Path $root "packaging\jlink-modules.txt") -Raw).Trim()
& (Tool "jlink") --add-modules $modules --strip-debug --no-header-files --no-man-pages --compress=zip-6 --output $runtime
if ($LASTEXITCODE -ne 0) { throw "Echec de jlink" }

$jpArgs = @(
    "--type", $Type,
    "--name", $appName,
    "--app-version", $version,
    "--vendor", $appName,
    "--description", "Gestion financiere personnelle locale",
    "--copyright", "FinanceApp",
    "--input", $inputDir,
    "--main-jar", "financeapp-desktop.jar",
    "--main-class", "com.financeapp.desktop.Launcher",
    "--runtime-image", $runtime,
    "--icon", (Join-Path $root "packaging\windows\financeapp.ico"),
    "--java-options", "-Dfile.encoding=UTF-8",
    "--java-options", "-Xmx1g",
    "--dest", $dest
)
if ($Type -ne "app-image") {
    $jpArgs += @(
        "--win-menu", "--win-menu-group", $appName,
        "--win-shortcut", "--win-shortcut-prompt",
        "--win-dir-chooser",
        "--win-per-user-install",
        # Identifiant FIXE : une nouvelle version remplace l'ancienne au lieu de s'installer a cote.
        "--win-upgrade-uuid", "3ec3ce67-a1e4-4ac3-818b-a82957fb512d",
        "--about-url", "https://github.com/SkyWace/Finance-APP"
    )
}
& (Tool "jpackage") @jpArgs
if ($LASTEXITCODE -ne 0) { throw "Echec de jpackage" }

Get-ChildItem $dest | ForEach-Object { Write-Host "==> $($_.FullName)" }
