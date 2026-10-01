# Builds WordVideo.dotm from the files in .\src and installs it in Word's
# STARTUP folder, so it loads every time Word starts.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem

$AddinName = 'WordVideo.dotm'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$src = Join-Path $here 'src'

function Show-Message([string]$text, [string]$icon = 'Information') {
    $opts = [System.Windows.Forms.MessageBoxOptions]::RtlReading -bor [System.Windows.Forms.MessageBoxOptions]::RightAlign
    [void][System.Windows.Forms.MessageBox]::Show($text, 'נגן וידאו ל-Word', 'OK', $icon, 'Button1', $opts)
}

# VBA imports modules in the system ANSI code page, so write non-ASCII
# characters as \uXXXX (decoded at run time by U() in modVideo) and use CRLF.
function Convert-ToVbaAscii([string]$path) {
    $text = [IO.File]::ReadAllText($path, [Text.Encoding]::UTF8)
    $sb = New-Object Text.StringBuilder
    foreach ($ch in $text.ToCharArray()) {
        if ([int]$ch -gt 127) { [void]$sb.AppendFormat('\u{0:X4}', [int]$ch) } else { [void]$sb.Append($ch) }
    }
    $out = $sb.ToString() -replace "`r?`n", "`r`n"
    $dest = Join-Path $env:TEMP ([IO.Path]::GetFileName($path))
    [IO.File]::WriteAllText($dest, $out, [Text.Encoding]::ASCII)
    return $dest
}

function Read-ZipEntry($zip, [string]$name) {
    $entry = $zip.GetEntry($name)
    $reader = New-Object IO.StreamReader($entry.Open())
    try { return $reader.ReadToEnd() } finally { $reader.Close() }
}

function Write-ZipEntry($zip, [string]$name, [string]$text) {
    $old = $zip.GetEntry($name)
    if ($old) { $old.Delete() }
    $writer = New-Object IO.StreamWriter($zip.CreateEntry($name).Open(), (New-Object Text.UTF8Encoding($false)))
    try { $writer.Write($text) } finally { $writer.Close() }
}

if (Get-Process WINWORD -ErrorAction SilentlyContinue) {
    Show-Message 'סגור את כל חלונות Word ואז הרץ את ההתקנה שוב.' 'Warning'
    exit 1
}

# Word version, e.g. "16.0" for Word 2016/2019/365.
try {
    $curVer = (Get-ItemProperty 'Registry::HKEY_CLASSES_ROOT\Word.Application\CurVer').'(default)'
} catch {
    Show-Message 'לא מצאתי את Word במחשב הזה.' 'Error'
    exit 1
}
$ver = ($curVer -replace '^.*\.(\d+)$', '$1') + '.0'

# Importing VBA code needs "Trust access to the VBA project object model".
# Turn it on for the build only and restore the previous value afterwards.
$secKey = "HKCU:\Software\Microsoft\Office\$ver\Word\Security"
if (-not (Test-Path $secKey)) { New-Item -Path $secKey -Force | Out-Null }
$oldVbom = (Get-ItemProperty -Path $secKey -Name AccessVBOM -ErrorAction SilentlyContinue).AccessVBOM
Set-ItemProperty -Path $secKey -Name AccessVBOM -Value 1 -Type DWord

$tmpDotm = Join-Path $env:TEMP $AddinName
$word = $null
try {
    if (Test-Path $tmpDotm) { Remove-Item $tmpDotm -Force }

    $word = New-Object -ComObject Word.Application
    $word.Visible = $false
    $word.DisplayAlerts = 0
    $startup = $word.StartupPath

    $doc = $word.Documents.Add()
    try {
        $proj = $doc.VBProject
    } catch {
        throw 'אין גישה לקוד VBA. ייתכן שמדיניות של הארגון חוסמת את זה.'
    }

    # Microsoft Office Object Library, for IRibbonUI / IRibbonControl.
    $officeGuid = '{2DF8D04C-5BFA-101B-BDE5-00AA0044DE52}'
    $hasOffice = $false
    foreach ($r in $proj.References) { if ($r.Guid -eq $officeGuid) { $hasOffice = $true } }
    if (-not $hasOffice) { [void]$proj.References.AddFromGuid($officeGuid, 2, 0) }

    foreach ($f in 'modVideo.bas', 'clsAppEvents.cls') {
        $tmp = Convert-ToVbaAscii (Join-Path $src $f)
        [void]$proj.VBComponents.Import($tmp)
        Remove-Item $tmp -Force
    }

    $doc.SaveAs2($tmpDotm, 15)  # wdFormatXMLTemplateMacroEnabled
    $doc.Close(0)
} catch {
    Show-Message ("ההתקנה נכשלה:`n`n" + $_.Exception.Message) 'Error'
    exit 1
} finally {
    if ($word) {
        $word.Quit(0)
        [void][Runtime.InteropServices.Marshal]::ReleaseComObject($word)
    }
    if ($null -eq $oldVbom) {
        Remove-ItemProperty -Path $secKey -Name AccessVBOM -ErrorAction SilentlyContinue
    } else {
        Set-ItemProperty -Path $secKey -Name AccessVBOM -Value $oldVbom -Type DWord
    }
}

# Add the ribbon tab (customUI part) to the template package.
$zip = [IO.Compression.ZipFile]::Open($tmpDotm, 'Update')
try {
    Write-ZipEntry $zip 'customUI/customUI.xml' ([IO.File]::ReadAllText((Join-Path $src 'customUI.xml'), [Text.Encoding]::UTF8))

    $rels = Read-ZipEntry $zip '_rels/.rels'
    if ($rels -notmatch 'customUI/customUI\.xml') {
        $rel = '<Relationship Id="rWordVideoUI" Type="http://schemas.microsoft.com/office/2006/relationships/ui/extensibility" Target="customUI/customUI.xml"/>'
        Write-ZipEntry $zip '_rels/.rels' ($rels -replace '</Relationships>', "$rel</Relationships>")
    }

    $types = Read-ZipEntry $zip '[Content_Types].xml'
    if ($types -notmatch 'Extension="xml"') {
        $def = '<Default Extension="xml" ContentType="application/xml"/>'
        Write-ZipEntry $zip '[Content_Types].xml' ($types -replace '(<Types[^>]*>)', "`$1$def")
    }
} finally {
    $zip.Dispose()
}

if (-not (Test-Path $startup)) { New-Item -ItemType Directory -Path $startup -Force | Out-Null }
Copy-Item $tmpDotm (Join-Path $startup $AddinName) -Force
Remove-Item $tmpDotm -Force

Show-Message "התוסף הותקן.`n`nפתח את Word, ותמצא לשונית חדשה בשם ""וידאו"" ליד הלשונית ""הוספה""."
