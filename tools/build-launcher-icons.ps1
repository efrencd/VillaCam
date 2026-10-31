$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$sourceIcon = Join-Path $projectRoot 'assets/branding/villacam-source.png'
$resources = Join-Path $projectRoot 'app/src/main/res'
$branding = Join-Path $projectRoot 'assets/branding'
$artBounds = & magick $sourceIcon -alpha extract -threshold '10%' -format '%@' info:
if ($LASTEXITCODE -ne 0 -or $artBounds -notmatch '^\d+x\d+\+\d+\+\d+$') { throw 'Could not measure the icon artwork bounds.' }

function Invoke-Magick([string[]]$Arguments) {
    & magick @Arguments
    if ($LASTEXITCODE -ne 0) { throw 'Could not generate a launcher icon resource.' }
}

# Package the ImageGen artwork without repainting it. Foreground padding keeps
# the mark inside the adaptive icon's circular safe region on different launchers.
New-Item -ItemType Directory -Force (Join-Path $resources 'drawable-nodpi') | Out-Null
Invoke-Magick @($sourceIcon, '-crop', $artBounds, '+repage', '-resize', '208x208', '-gravity', 'center', '-background', 'none', '-extent', '432x432', (Join-Path $resources 'drawable-nodpi/ic_launcher_foreground.png'))

$densities = @{ 'mdpi' = 48; 'hdpi' = 72; 'xhdpi' = 96; 'xxhdpi' = 144; 'xxxhdpi' = 192 }
foreach ($density in $densities.Keys) {
    $size = $densities[$density]
    $markSize = [int][Math]::Round($size * 0.70)
    $directory = Join-Path $resources "mipmap-$density"
    New-Item -ItemType Directory -Force $directory | Out-Null
    foreach ($round in @($false, $true)) {
        $radius = [Math]::Round($size * 0.22, 2)
        $edge = $size - 1
        $center = ($size - 1) / 2
        $shape = if ($round) { "circle $center,$center $center,0" } else { "roundrectangle 0,0 $edge,$edge $radius,$radius" }
        $filename = if ($round) { 'ic_launcher_round.png' } else { 'ic_launcher.png' }
        Invoke-Magick @('-size', "${size}x${size}", 'xc:none', '-fill', '#111827', '-draw', $shape, '(', $sourceIcon, '-crop', $artBounds, '+repage', '-resize', "${markSize}x${markSize}", ')', '-gravity', 'center', '-compose', 'over', '-composite', (Join-Path $directory $filename))
    }
}

# Review artifact: same safe-area composition as the adaptive foreground/background.
Invoke-Magick @('-size', '432x432', 'xc:#111827', (Join-Path $resources 'drawable-nodpi/ic_launcher_foreground.png'), '-compose', 'over', '-composite', '-gravity', 'center', '-crop', '288x288+0+0', '+repage', '-resize', '512x512', (Join-Path $branding 'villacam-icon-preview.png'))
