[CmdletBinding()]
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$MavenArgs
)
& "$PSScriptRoot\mvnw.cmd" @MavenArgs
