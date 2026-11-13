[CmdletBinding()]
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$MavenArgs
)
$targetDir = Join-Path $PSScriptRoot "backend"
Push-Location $targetDir
try {
    & .\mvnw.cmd @MavenArgs
} finally {
    Pop-Location
}
