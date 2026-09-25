<#
  Runs the Gatling simulation at several fixed arrival rates, one after the other, and prints
  a Markdown table (throughput, latency percentiles, errors, CPU of each container).

  Usage (from the repository root, with `docker compose up -d --wait` running):
    .\load-test\run-steps.ps1
    .\load-test\run-steps.ps1 -Rates 100,500,1000 -HoldSeconds 60
#>
param(
    [int[]] $Rates = @(100, 250, 500, 750, 1000, 1500),
    [int] $RampSeconds = 10,
    [int] $HoldSeconds = 30,
    [string] $BaseUrl = "http://localhost:8082"
)

# "Continue": Maven and Gatling write to stderr, which Windows PowerShell 5.1 would otherwise treat as errors
$ErrorActionPreference = "Continue"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

function Get-GatlingStats([string[]] $lines) {
    # lines look like: "> response time 99th percentile (ms)     |        20 |        20 |         -"
    $stats = @{}
    foreach ($line in $lines) {
        if ($line -match '^> (.+?)\s+\|\s+(\S+)\s+\|\s+(\S+)\s+\|\s+(\S+)') {
            $stats[$Matches[1].Trim()] = @{ Total = $Matches[2]; OK = $Matches[3]; KO = $Matches[4] }
        }
    }
    return $stats
}

$rows = @()
foreach ($rate in $Rates) {
    Write-Host "=== $rate req/s: ramp $RampSeconds s + hold $HoldSeconds s" -ForegroundColor Cyan

    # sample CPU every few seconds for the whole run and keep the peak of each container
    $sampleSeconds = 25 + $RampSeconds + $HoldSeconds   # ~25 s for Maven + Gatling to start
    $cpuJob = Start-Job -ArgumentList $sampleSeconds -ScriptBlock {
        param($duration)
        $end = (Get-Date).AddSeconds($duration)
        $peak = @{}
        $hostPeak = 0
        while ((Get-Date) -lt $end) {
            $h = (Get-Counter '\Processor(_Total)\% Processor Time').CounterSamples[0].CookedValue
            if ($h -gt $hostPeak) { $hostPeak = $h }
            foreach ($line in (docker stats --no-stream --format "{{.Name}}={{.CPUPerc}}")) {
                $name, $value = $line -split '='
                $v = [double]($value -replace '%', '')
                if (-not $peak.ContainsKey($name) -or $v -gt $peak[$name]) { $peak[$name] = $v }
            }
        }
        [pscustomobject]@{ Host = [math]::Round($hostPeak); Containers = $peak }
    }

    $output = & .\mvnw.cmd -B -q -Pload-test -pl load-test gatling:test `
        "-Drate=$rate" "-DrampSeconds=$RampSeconds" "-DholdSeconds=$HoldSeconds" "-DbaseUrl=$BaseUrl" 2>&1 |
        ForEach-Object { "$_" }
    $cpu = Receive-Job $cpuJob -Wait -AutoRemoveJob
    # container CPU is in % of ONE core: 200% = two cores busy
    $s = Get-GatlingStats $output

    $ko = if ($s['request count'].KO -eq '-') { 0 } else { [int]$s['request count'].KO }
    $total = [int]$s['request count'].Total
    $cpuByName = @{}
    foreach ($k in $cpu.Containers.Keys) { $cpuByName[$k] = "$([math]::Round($cpu.Containers[$k]))%" }

    $row = [pscustomobject]@{
        Target     = $rate
        Achieved   = $s['mean throughput (rps)'].Total
        Requests   = $total
        ErrorsPct  = if ($total) { [math]::Round(100.0 * $ko / $total, 2) } else { 0 }
        P50        = $s['response time 50th percentile (ms)'].Total
        P95        = $s['response time 95th percentile (ms)'].Total
        P99        = $s['response time 99th percentile (ms)'].Total
        Max        = $s['max response time (ms)'].Total
        HostCpu    = "$($cpu.Host)%"
        Decision   = $cpuByName['decision-api']
        Scoring    = $cpuByName['scoring-service']
        Feature    = $cpuByName['feature-service']
        Kafka      = $cpuByName['kafka']
    }
    $rows += $row
    $row | Format-List | Out-String | Write-Host
}

Write-Host "`n| target req/s | achieved req/s* | requests | errors | p50 ms | p95 ms | p99 ms | max ms | peak host CPU | peak decision-api | peak scoring | peak feature | peak kafka |"
Write-Host "|---|---|---|---|---|---|---|---|---|---|---|---|---|"
foreach ($r in $rows) {
    Write-Host "| $($r.Target) | $($r.Achieved) | $($r.Requests) | $($r.ErrorsPct)% | $($r.P50) | $($r.P95) | $($r.P99) | $($r.Max) | $($r.HostCpu) | $($r.Decision) | $($r.Scoring) | $($r.Feature) | $($r.Kafka) |"
}
Write-Host "`n* average over the whole run, including the $RampSeconds s ramp-up. Container CPU: 100% = one core."
