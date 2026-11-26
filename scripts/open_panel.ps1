# open_panel.ps1
# Pure ASCII only (PowerShell 5.1 reads no-BOM files as ANSI/GBK).
# Goal: locate the TV box running CastList and open http://IP:18090 in browser.
# No admin rights, no hosts edit, no Python. Just .NET UdpClient/TcpClient.
# Strategy: 1) -Ip parameter  2) SSDP broadcast  3) scan local /24 for port 18090
# Log: <script dir>\open_panel.log

param(
    [string]$Ip = ''
)

$ErrorActionPreference = 'Stop'
$LOG = Join-Path $PSScriptRoot 'open_panel.log'

function Log([string]$m) {
    $line = (Get-Date).ToString('HH:mm:ss') + '  ' + $m
    [System.IO.File]::AppendAllText($LOG, $line + [Environment]::NewLine, [System.Text.UTF8Encoding]($false))
}

function Test-Port([string]$targetHost, [int]$port, [int]$timeout = 1200) {
    try {
        $c = New-Object System.Net.Sockets.TcpClient
        $iar = $c.BeginConnect($targetHost, $port, $null, $null)
        $ok = $iar.AsyncWaitHandle.WaitOne($timeout)
        if ($ok) { $c.EndConnect($iar) }
        $c.Close()
        return $ok
    } catch { return $false }
}

function Find-BySsdp {
    $udp = New-Object System.Net.Sockets.UdpClient
    try {
        $udp.Client.SetSocketOption([System.Net.Sockets.SocketOptionLevel]::Socket,
            [System.Net.Sockets.SocketOptionName]::ReuseAddress, $true)
        $any = New-Object System.Net.IPEndPoint -ArgumentList ([System.Net.IPAddress]::Any), 0
        $udp.Client.Bind($any)
        $udp.Client.ReceiveTimeout = 1000
        $req = "M-SEARCH * HTTP/1.1`r`nHOST: 239.255.255.250:1900`r`nMAN: `"ssdp:discover`"`r`nMX: 3`r`nST: ssdp:all`r`n`r`n"
        $b = [System.Text.Encoding]::ASCII.GetBytes($req)
        $mc = [System.Net.IPAddress]::Parse('239.255.255.250')
        $ep = New-Object System.Net.IPEndPoint -ArgumentList $mc, 1900
        for ($i = 0; $i -lt 4; $i++) {
            [void]$udp.Send($b, $b.Length, $ep)
            Start-Sleep -Milliseconds 250
        }
        $deadline = (Get-Date).AddSeconds(7)
        $found = @()
        while ((Get-Date) -lt $deadline) {
            try {
                $remote = New-Object System.Net.IPEndPoint -ArgumentList ([System.Net.IPAddress]::Any), 0
                $data = $udp.Receive([ref]$remote)
                $txt = [System.Text.Encoding]::UTF8.GetString($data)
                if ($txt -notmatch 'HTTP/1\.1 200') { continue }
                $candidate = $null
                if ($txt -match '(?i)LOCATION:\s*http://([0-9]+\.[0-9]+\.[0-9]+\.[0-9]+)') {
                    $candidate = $Matches[1]
                }
                if (-not $candidate) { $candidate = $remote.Address.ToString() }
                if (-not $candidate -or $candidate -eq '0.0.0.0') { continue }
                # Prefer DLNA/UPnP devices, but accept any LAN device answering SSDP.
                if ($found -notcontains $candidate) {
                    Log ('  SSDP candidate ' + $candidate)
                    $found += $candidate
                }
            } catch { }
        }
        return @($found | Sort-Object -Unique)
    } finally {
        $udp.Close()
    }
}

function Get-LocalPrefix {
    try {
        $ips = [System.Net.Dns]::GetHostAddresses([System.Net.Dns]::GetHostName()) |
            Where-Object { $_.AddressFamily -eq [System.Net.Sockets.AddressFamily]::InterNetwork -and $_.ToString() -ne '127.0.0.1' }
        if ($ips.Count -gt 0) {
            $p = $ips[0].ToString().Split('.')
            return ($p[0] + '.' + $p[1] + '.' + $p[2] + '.')
        }
    } catch { }
    return $null
}

function Scan-LocalSubnet([string]$prefix) {
    Log ('  scanning ' + $prefix + '1-254:18090 ...')
    $found = New-Object System.Collections.ArrayList
    for ($batchStart = 1; $batchStart -le 254; $batchStart += 64) {
        $items = @()
        $handles = New-Object System.Collections.ArrayList
        for ($i = $batchStart; $i -lt ($batchStart + 64) -and $i -le 254; $i++) {
            $ip = $prefix + $i
            $c = New-Object System.Net.Sockets.TcpClient
            try {
                $iar = $c.BeginConnect($ip, 18090, $null, $null)
                [void]$handles.Add($iar.AsyncWaitHandle)
                $items += [pscustomobject]@{ Ip = $ip; Client = $c; Async = $iar }
            } catch {
                try { $c.Close() } catch { }
            }
        }
        if ($handles.Count -gt 0) {
            [void][System.Threading.WaitHandle]::WaitAll($handles.ToArray(), 1500)
        }
        foreach ($it in $items) {
            try {
                # EndConnect throws when the connect failed/timed out; success means port is open.
                $it.Client.EndConnect($it.Async)
                [void]$found.Add($it.Ip)
            } catch { }
            try { $it.Client.Close() } catch { }
        }
    }
    return @($found)
}

[System.IO.File]::WriteAllText($LOG, '', [System.Text.UTF8Encoding]($false))
Log '=== open panel START ==='

$tvIp = ''

if ($Ip -ne '') {
    Log ('using -Ip parameter: ' + $Ip)
    $tvIp = $Ip
} else {
    Log 'SSDP broadcast ...'
    $candidates = @(Find-BySsdp)
    foreach ($c in $candidates) {
        if (Test-Port $c 18090 1000) { $tvIp = $c; break }
    }
}

if ($tvIp -eq '') {
    Log 'SSDP did not find a box with port 18090, scanning local subnet ...'
    $prefix = Get-LocalPrefix
    if ($prefix) {
        $scan = @(Scan-LocalSubnet $prefix)
        if ($scan.Count -gt 0) { $tvIp = $scan[0] }
    } else {
        Log 'WARN: cannot determine local subnet'
    }
}

if ($tvIp -eq '') {
    Log 'FAILED to locate TV box. Run: open_panel.ps1 -Ip 192.168.x.x'
    Write-Host 'TV box not found. Try: powershell -File open_panel.ps1 -Ip <BOX_IP>'
    return
}

Log ('TV box IP = ' + $tvIp)
if (-not (Test-Port $tvIp 18090)) {
    Log 'WARN: port 18090 unreachable (service may be stopped)'
} else {
    Log 'port 18090 reachable'
}

$url = 'http://' + $tvIp + ':18090/'
Log ('opening ' + $url)
try {
    Start-Process $url
    Log 'browser launched'
} catch {
    Log ('launch failed: ' + $_.Exception.Message)
}
Log '=== open panel DONE ==='
