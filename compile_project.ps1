$projectRoot = "C:\Users\RK Maurya\Documents\Roster Management System"
$m2 = "C:\Users\RK Maurya\.m2\repository"
$targetDir = Join-Path $projectRoot "target"
$targetClasses = Join-Path $targetDir "classes"
$targetTestClasses = Join-Path $targetDir "test-classes"

if (-not (Test-Path $targetClasses)) { New-Item -ItemType Directory -Path $targetClasses -Force | Out-Null }
if (-not (Test-Path $targetTestClasses)) { New-Item -ItemType Directory -Path $targetTestClasses -Force | Out-Null }

Write-Host "Filtering exact Spring Boot 3.3.5 / Java 17 classpath..."

$allJars = Get-ChildItem -Path $m2 -Filter "*.jar" -Recurse | Where-Object {
    $path = $_.FullName.Replace('\', '/')
    $name = $_.Name

    if ($name -like "*-sources.jar" -or $name -like "*-javadoc.jar") { return $false }
    if ($path -like "*/javax/*" -or $path -like "*/javax.*") { return $false }
    if ($path -like "*/com/example/*" -or $path -like "*/com/VegetablesSell/*") { return $false }

    # Exclude commons-logging
    if ($path -like "*/commons-logging/*") { return $false }

    # Strict Spring Boot 3.3.5 & Spring Framework 6.1.14
    if ($path -like "*/org/springframework/boot/*") {
        if ($path -notlike "*/3.3.5/*") { return $false }
    }
    if ($path -like "*/org/springframework/data/*") {
        if ($path -notlike "*/3.3.5/*" -and $path -notlike "*/2023.0.11/*") { return $false }
    }
    if ($path -like "*/org/springframework/security/*") {
        if ($path -notlike "*/6.3.4/*") { return $false }
    }
    if ($path -like "*/org/springframework/spring-*") {
        if ($path -notlike "*/6.1.14/*") { return $false }
    }

    # Strict Jakarta EE 10 APIs
    if ($path -like "*/jakarta/persistence/jakarta.persistence-api/*") {
        if ($path -notlike "*/3.1.0/*") { return $false }
    }
    if ($path -like "*/jakarta/transaction/jakarta.transaction-api/*") {
        if ($path -notlike "*/2.0.1/*") { return $false }
    }
    if ($path -like "*/jakarta/validation/jakarta.validation-api/*") {
        if ($path -notlike "*/3.0.2/*") { return $false }
    }
    if ($path -like "*/jakarta/annotation/jakarta.annotation-api/*") {
        if ($path -notlike "*/2.1.1/*") { return $false }
    }
    if ($path -like "*/jakarta/xml/bind/jakarta.xml.bind-api/*") {
        if ($path -notlike "*/4.0.2/*") { return $false }
    }
    if ($path -like "*/jakarta/activation/jakarta.activation-api/*") {
        if ($path -notlike "*/2.1.3/*") { return $false }
    }

    # Strict Jackson 2.17.2 (native Java 17 record deserialization support)
    if ($path -like "*/com/fasterxml/jackson/*") {
        if ($path -notlike "*/2.17.2/*") { return $false }
    }

    # Strict Hibernate 6.5.3.Final, hibernate-commons-annotations 6.0.6.Final, hibernate-validator 8.0.1.Final
    if ($path -like "*/org/hibernate/hibernate-*") { return $false }
    if ($path -like "*/org/hibernate/orm/*") {
        if ($path -notlike "*/6.5.3.Final/*") { return $false }
    }
    if ($path -like "*/org/hibernate/common/hibernate-commons-annotations/*") {
        if ($path -notlike "*/6.0.6.Final/*") { return $false }
    }
    if ($path -like "*/org/hibernate/validator/hibernate-validator/*") {
        if ($path -notlike "*/8.0.1.Final/*") { return $false }
    }

    # Strict Logback 1.5.18 (has ReentrantLock return type across classic and core)
    if ($path -like "*/ch/qos/logback/*") {
        if ($path -notlike "*/1.5.18/*") { return $false }
    }

    # Strict SLF4J 2.0.16
    if ($path -like "*/org/slf4j/*") {
        if ($path -notlike "*/2.0.16/*") { return $false }
    }

    # Strict Log4j 2.23.1
    if ($path -like "*/org/apache/logging/log4j/*") {
        if ($path -notlike "*/2.23.1/*") { return $false }
    }

    # JJWT 0.12.6
    if ($path -like "*/io/jsonwebtoken/*") {
        if ($path -notlike "*/0.12.6/*") { return $false }
    }

    # Tomcat 10.1.31
    if ($path -like "*/org/apache/tomcat/embed/*") {
        if ($path -notlike "*/10.1.31/*") { return $false }
    }

    # ByteBuddy 1.14.19
    if ($path -like "*/net/bytebuddy/*") {
        if ($path -notlike "*/1.14.19/*") { return $false }
    }

    return $true
}

$jarPaths = $allJars | Select-Object -ExpandProperty FullName
$cp = ($jarPaths | ForEach-Object { $_.Replace('\', '/') }) -join ";"
$cpFile = Join-Path $targetDir "cached_cp.txt"
[System.IO.File]::WriteAllText($cpFile, $cp)
Write-Host "Wrote $($jarPaths.Count) curated JARs to $cpFile"

# Ensure mail jars are present
& "C:\Users\RK Maurya\.gemini\antigravity\brain\ea08ed10-bf6a-4c3a-83ef-449387836709\scratch\download_mail_jars.ps1"

# Copy resources to target/classes
Copy-Item -Path "$projectRoot\src\main\resources\*" -Destination $targetClasses -Recurse -Force

$mainSources = Get-ChildItem -Path (Join-Path $projectRoot "src\main\java") -Filter "*.java" -Recurse | Select-Object -ExpandProperty FullName
$testSources = Get-ChildItem -Path (Join-Path $projectRoot "src\test\java") -Filter "*.java" -Recurse | Select-Object -ExpandProperty FullName

$cp = (Get-Content $cpFile -Raw).Trim()

$mainArgs = [System.Collections.Generic.List[string]]::new()
$mainArgs.Add("--release")
$mainArgs.Add("17")
$mainArgs.Add("-parameters")
$mainArgs.Add("-d")
$mainArgs.Add('"' + $targetClasses.Replace('\', '/') + '"')
$mainArgs.Add("-cp")
$mainArgs.Add('"' + $cp + '"')
foreach ($s in $mainSources) { $mainArgs.Add('"' + $s.Replace('\', '/') + '"') }

$mainArgsFile = Join-Path $targetDir "javac_main_args.txt"
[System.IO.File]::WriteAllLines($mainArgsFile, $mainArgs)

Write-Host "Compiling $($mainSources.Count) Main sources..."
$p1 = Start-Process -FilePath "javac" -ArgumentList "@`"$mainArgsFile`"" -NoNewWindow -Wait -PassThru
Write-Host "Main Compilation Exit Code: $($p1.ExitCode)"

if ($p1.ExitCode -ne 0) {
    exit $p1.ExitCode
}

$testCp = $targetClasses.Replace('\', '/') + ";" + $cp
$testArgs = [System.Collections.Generic.List[string]]::new()
$testArgs.Add("--release")
$testArgs.Add("17")
$testArgs.Add("-parameters")
$testArgs.Add("-d")
$testArgs.Add('"' + $targetTestClasses.Replace('\', '/') + '"')
$testArgs.Add("-cp")
$testArgs.Add('"' + $testCp + '"')
foreach ($s in $testSources) { $testArgs.Add('"' + $s.Replace('\', '/') + '"') }

$testArgsFile = Join-Path $targetDir "javac_test_args.txt"
[System.IO.File]::WriteAllLines($testArgsFile, $testArgs)

Write-Host "Compiling $($testSources.Count) Test sources..."
$p2 = Start-Process -FilePath "javac" -ArgumentList "@`"$testArgsFile`"" -NoNewWindow -Wait -PassThru
Write-Host "Test Compilation Exit Code: $($p2.ExitCode)"

if ($p2.ExitCode -ne 0) {
    exit $p2.ExitCode
}

Write-Host "Compilation completely SUCCESSFUL!"
