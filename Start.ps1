param(
    [switch]$Demo,
    [switch]$Build,
    [ValidateRange(1, 65535)][int]$Port = 8094
)

$ErrorActionPreference = 'Stop'
$toolsDir = Join-Path $env:LOCALAPPDATA 'HodToolchain'
$jdk = Get-ChildItem -LiteralPath $toolsDir -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue | Select-Object -First 1
if ($jdk) { $env:JAVA_HOME = $jdk.FullName }
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { 'java.exe' }

Push-Location -LiteralPath $PSScriptRoot
try {
    if ($Build) {
        $maven = Get-ChildItem -LiteralPath $toolsDir -Directory -Filter 'apache-maven-*' -ErrorAction SilentlyContinue | Select-Object -First 1
        $mavenCommand = if ($maven) { Join-Path $maven.FullName 'bin/mvn.cmd' } else { 'mvn.cmd' }
        & $mavenCommand clean package
        if ($LASTEXITCODE -ne 0) { throw 'Сборка завершилась ошибкой.' }
    }
    if (!(Test-Path -LiteralPath 'target/sverka.jar' -PathType Leaf)) { throw 'Сначала выполните .\Start.ps1 -Build.' }
    $options = @("--server.port=$Port", "--sverka.demo=$(([bool]$Demo).ToString().ToLowerInvariant())")
    if ($Demo) {
        $options += '--server.address=127.0.0.1', '--server.servlet.session.cookie.secure=false',
            '--spring.datasource.url=jdbc:h2:file:./data/sverka-demo;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE',
            '--spring.datasource.username=sa', '--spring.datasource.password='
    }
    $runtime = New-Item -ItemType Directory -Path '.run' -Force
    $jar = Join-Path $runtime.FullName "sverka-$([Guid]::NewGuid().ToString('N')).jar"
    Copy-Item -LiteralPath 'target/sverka.jar' -Destination $jar
    & $java -jar $jar @options
    if ($LASTEXITCODE -ne 0) { throw 'Приложение завершилось с ошибкой.' }
} finally {
    Pop-Location
}
