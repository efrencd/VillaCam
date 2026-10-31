# Copyright 2026 Efren C.D.
# SPDX-License-Identifier: Apache-2.0
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$outputDirectory = Join-Path $projectRoot 'app/build/policy-tests'
New-Item -ItemType Directory -Force $outputDirectory | Out-Null
$policies = @('RecoveryMonitor', 'AdaptiveBitrate', 'AudioInputPolicy', 'VdoLinks', 'UiLanguage')
$sources = foreach ($policy in $policies) {
    Join-Path $projectRoot "app/src/main/java/es/colegio/camara/$policy.java"
    Join-Path $projectRoot "tests/es/colegio/camara/${policy}Test.java"
}
& javac -encoding UTF-8 -d $outputDirectory @sources
if ($LASTEXITCODE -ne 0) { throw 'Policy test compilation failed.' }
foreach ($policy in $policies) {
    & java -cp $outputDirectory "es.colegio.camara.${policy}Test"
    if ($LASTEXITCODE -ne 0) { throw "$policy tests failed." }
}
