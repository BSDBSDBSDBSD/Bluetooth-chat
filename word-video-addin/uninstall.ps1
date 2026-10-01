# Removes WordVideo.dotm from Word's STARTUP folder.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Windows.Forms

function Show-Message([string]$text, [string]$icon = 'Information') {
    $opts = [System.Windows.Forms.MessageBoxOptions]::RtlReading -bor [System.Windows.Forms.MessageBoxOptions]::RightAlign
    [void][System.Windows.Forms.MessageBox]::Show($text, 'נגן וידאו ל-Word', 'OK', $icon, 'Button1', $opts)
}

if (Get-Process WINWORD -ErrorAction SilentlyContinue) {
    Show-Message 'סגור את כל חלונות Word ואז הרץ את ההסרה שוב.' 'Warning'
    exit 1
}

$word = New-Object -ComObject Word.Application
try { $startup = $word.StartupPath } finally {
    $word.Quit(0)
    [void][Runtime.InteropServices.Marshal]::ReleaseComObject($word)
}

$file = Join-Path $startup 'WordVideo.dotm'
if (Test-Path $file) {
    Remove-Item $file -Force
    Show-Message 'התוסף הוסר. מסמכים עם נגנים ימשיכו להיפתח, אבל הנתיבים לסרטונים לא יתעדכנו אוטומטית.'
} else {
    Show-Message 'התוסף לא מותקן.'
}
