# The Microsoft Store's package, tried on the release workflow's Windows runner (docs/DEPLOYMENT.md → Releasing the
# desktop app): a copy signed with a certificate made and trusted on this runner only, installed, Kadans started from
# it, then removed. The package uploaded to Partner Center stays unsigned: the Store signs what it publishes.
param([Parameter(Mandatory = $true)][string]$Package)
$ErrorActionPreference = 'Stop'

$publisher = 'CN=0F6500C3-0FC2-4B9B-9C79-28A0D1543941' # the manifest's: a package installs only under a matching signature
$copy = Join-Path $env:RUNNER_TEMP 'kadans-test.msix'
Copy-Item $Package $copy -Force

$cert = New-SelfSignedCertificate -Type Custom -Subject $publisher -KeyUsage DigitalSignature `
    -FriendlyName 'Kadans package test' -CertStoreLocation 'Cert:\CurrentUser\My' `
    -TextExtension @('2.5.29.37={text}1.3.6.1.5.5.7.3.3', '2.5.29.19={text}')
$pfx = Join-Path $env:RUNNER_TEMP 'kadans-test.pfx'
$secret = 'kadans-package-test'
Export-PfxCertificate -Cert $cert -FilePath $pfx -Password (ConvertTo-SecureString $secret -Force -AsPlainText) | Out-Null
$cer = Export-Certificate -Cert $cert -FilePath (Join-Path $env:RUNNER_TEMP 'kadans-test.cer')
Import-Certificate -FilePath $cer.FullName -CertStoreLocation 'Cert:\LocalMachine\TrustedPeople' | Out-Null

$signtool = Get-ChildItem 'C:\Program Files (x86)\Windows Kits\10\bin\10.*\x64\signtool.exe' |
    Sort-Object { [version]$_.Directory.Parent.Name } | Select-Object -Last 1
& $signtool.FullName sign /fd SHA256 /f $pfx /p $secret $copy
if ($LASTEXITCODE -ne 0) { throw "signtool failed ($LASTEXITCODE)" }

Add-AppxPackage -Path $copy
$installed = Get-AppxPackage -Name 'AndersonRuban.Kadans'
if (-not $installed) { throw 'The package did not install' }
"Installed $($installed.PackageFullName)"

foreach ($file in 'Kadans.exe', 'KadansBackground.exe', 'app\Kadans.cfg', 'app\KadansBackground.cfg', 'resources.pri') {
    if (-not (Test-Path (Join-Path $installed.InstallLocation $file))) { throw "The installed package has no $file" }
}
if (-not (Select-String -Path (Join-Path $installed.InstallLocation 'app\Kadans.cfg') -SimpleMatch 'kadans.distribution=msstore' -Quiet)) {
    throw 'The launcher does not carry the Store mark'
}

try {
    # As the Start menu opens it: through the package, so with its identity.
    Start-Process "shell:AppsFolder\$($installed.PackageFamilyName)!Kadans"
    $running = $null
    foreach ($second in 1..40) {
        Start-Sleep -Seconds 1
        $running = Get-Process -Name 'Kadans' -ErrorAction SilentlyContinue
        if ($running -and $second -ge 15) { break } # still there after 15 s: it did not fail at start
    }
    if (-not $running) { throw 'Kadans did not start from the package' }
    "Kadans runs from the package (process $($running.Id -join ', '))"
} finally {
    Get-Process -Name 'Kadans' -ErrorAction SilentlyContinue | Stop-Process -Force
    Remove-AppxPackage -Package $installed.PackageFullName
}
