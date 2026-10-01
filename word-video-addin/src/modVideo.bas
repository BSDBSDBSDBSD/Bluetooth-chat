Attribute VB_Name = "modVideo"
Option Explicit

' Video player add-in for Word 2019 (global template).
' A video is copied to a "<document>_media" folder next to the document and is
' played by a Windows Media Player control placed in the document. The relative
' path is kept in the player's alt text, so when the document and its folder
' are moved together the player is pointed at the new location on open.
'
' Note: the installer converts every non-ASCII character in this file to \uXXXX,
' so all Hebrew text must be wrapped in U("...").

Private Const WMP_PROGID As String = "WMPlayer.OCX"
Private Const TAG_PREFIX As String = "wordvideo:"
Private Const CONTROLS_HEIGHT As Single = 48
Private Const VIDEO_FILTER As String = "*.mp4;*.m4v;*.wmv;*.avi;*.mov;*.mpg;*.mpeg;*.mkv;*.3gp;*.mp3;*.wav;*.wma;*.m4a"

Private Const LOAD_OK As Long = 0
Private Const LOAD_NO_FILE As Long = 1
Private Const LOAD_NO_PLAYER As Long = 2

Public gEvents As clsAppEvents

' ---------- Startup ----------

Public Sub AutoExec()
    InitEvents
    Dim d As Document
    For Each d In Documents
        RefreshPlayers d, False
    Next d
End Sub

Public Sub InitEvents()
    If gEvents Is Nothing Then
        Set gEvents = New clsAppEvents
        Set gEvents.App = Application
    End If
End Sub

' ---------- Ribbon callbacks ----------

Public Sub RibbonOnLoad(ribbon As IRibbonUI)
    InitEvents
End Sub

Public Sub RibbonInsertVideo(control As IRibbonControl)
    InitEvents
    InsertVideo
End Sub

Public Sub RibbonReplaceVideo(control As IRibbonControl)
    InitEvents
    ReplaceVideo
End Sub

Public Sub RibbonSize(control As IRibbonControl)
    Select Case control.ID
        Case "btnSizeSmall": ResizePlayer 320
        Case "btnSizeMedium": ResizePlayer 480
        Case Else: ResizePlayer 0
    End Select
End Sub

Public Sub RibbonRefresh(control As IRibbonControl)
    InitEvents
    If Documents.Count > 0 Then RefreshPlayers ActiveDocument, True
End Sub

Public Sub RibbonHelp(control As IRibbonControl)
    ShowHelp
End Sub

' ---------- Commands ----------

Public Sub InsertVideo()
    If Documents.Count = 0 Then Documents.Add
    Dim doc As Document
    Set doc = ActiveDocument
    If Not EnsureSavedLocally(doc) Then Exit Sub

    Dim src As String, rel As String
    src = PickVideoFile()
    If src = "" Then Exit Sub
    rel = CopyToMediaFolder(doc, src)
    If rel = "" Then Exit Sub

    Dim rng As Range
    Set rng = Selection.Range
    rng.Collapse wdCollapseEnd

    Dim player As InlineShape
    On Error Resume Next
    Set player = doc.InlineShapes.AddOLEControl(ClassType:=WMP_PROGID & ".7", Range:=rng)
    On Error GoTo 0
    If player Is Nothing Then
        Msg U("לא הצלחתי ליצור נגן וידאו." & vbCr & vbCr & _
              "ייתכן ש-Windows Media Player לא מותקן במחשב (למשל ב-Windows מהדורת N), " & _
              "או שפקדי ActiveX חסומים בהגדרות מרכז האמון של Word."), vbExclamation
        Exit Sub
    End If

    player.AlternativeText = TAG_PREFIX & rel
    SetPlayerWidth player, 480
    ReportLoad LoadPlayer(doc, player), rel
End Sub

Public Sub ReplaceVideo()
    If Documents.Count = 0 Then Exit Sub
    Dim doc As Document, player As Object
    Set doc = ActiveDocument
    Set player = PlayerAtCursor()
    If player Is Nothing Then
        Msg U("שים את הסמן בשורה של הנגן ונסה שוב."), vbInformation
        Exit Sub
    End If
    If Not EnsureSavedLocally(doc) Then Exit Sub

    Dim src As String, rel As String
    src = PickVideoFile()
    If src = "" Then Exit Sub
    rel = CopyToMediaFolder(doc, src)
    If rel = "" Then Exit Sub

    player.AlternativeText = TAG_PREFIX & rel
    ReportLoad LoadPlayer(doc, player), rel
End Sub

Public Sub ResizePlayer(ByVal widthPt As Single)
    If Documents.Count = 0 Then Exit Sub
    Dim player As Object
    Set player = PlayerAtCursor()
    If player Is Nothing Then
        Msg U("שים את הסמן בשורה של הנגן ונסה שוב."), vbInformation
        Exit Sub
    End If
    SetPlayerWidth player, widthPt
End Sub

Public Sub RefreshPlayers(doc As Document, ByVal verbose As Boolean)
    Dim shp As Object, found As Long, missing As String, blocked As Boolean
    For Each shp In doc.InlineShapes
        CheckPlayer doc, shp, found, missing, blocked
    Next shp
    For Each shp In doc.Shapes
        CheckPlayer doc, shp, found, missing, blocked
    Next shp

    If missing <> "" Then
        Msg U("לא מצאתי את קובצי הווידאו האלה:") & vbCr & missing & vbCr & _
            U("ודא שהתיקייה ") & MediaFolderName(doc) & U(" נמצאת באותה תיקייה של המסמך."), vbExclamation
    ElseIf blocked Then
        Msg U("הנגן לא נטען. אם מופיע פס צהוב של אזהרת אבטחה, לחץ ""הפוך תוכן לזמין"" ואחר כך ""רענן נגנים""."), vbExclamation
    ElseIf verbose Then
        If found = 0 Then
            Msg U("אין נגני וידאו במסמך הזה."), vbInformation
        Else
            Msg U("הנגנים עודכנו: ") & found, vbInformation
        End If
    End If
End Sub

Public Sub ShowHelp()
    Msg U("איך משתמשים:" & vbCr & vbCr & _
          "1. שמור את המסמך במחשב." & vbCr & _
          "2. שים את הסמן במקום הרצוי ולחץ ""הוסף וידאו""." & vbCr & _
          "   הסרטון מועתק לתיקייה בשם <שם המסמך>_media ליד המסמך." & vbCr & _
          "3. מנגנים בלחיצה על כפתור ההפעלה של הנגן." & vbCr & vbCr & _
          "החלפת סרטון או שינוי גודל: שים את הסמן בשורה של הנגן ולחץ על הכפתור." & vbCr & vbCr & _
          "העברה למחשב אחר: העתק את המסמך יחד עם תיקיית ה-_media, והתקן את התוסף גם שם."), vbInformation
End Sub

' ---------- Helpers ----------

Private Function EnsureSavedLocally(doc As Document) As Boolean
    If doc.Path = "" Then
        Msg U("כדי להוסיף וידאו צריך קודם לשמור את המסמך. הסרטון יישמר בתיקייה ליד המסמך."), vbInformation
        If Dialogs(wdDialogFileSaveAs).Show <> -1 Then Exit Function
        If doc.Path = "" Then Exit Function
    End If
    If LCase$(Left$(doc.Path, 4)) = "http" Then
        Msg U("המסמך שמור בענן (OneDrive או SharePoint). שמור אותו בתיקייה במחשב ונסה שוב."), vbExclamation
        Exit Function
    End If
    EnsureSavedLocally = True
End Function

Private Function PickVideoFile() As String
    With Application.FileDialog(3) ' msoFileDialogFilePicker
        .Title = U("בחר סרטון")
        .AllowMultiSelect = False
        .Filters.Clear
        .Filters.Add U("קובצי וידאו ושמע"), VIDEO_FILTER
        .Filters.Add U("כל הקבצים"), "*.*"
        If .Show = -1 Then PickVideoFile = .SelectedItems(1)
    End With
End Function

Private Function MediaFolderName(doc As Document) As String
    Dim n As String, p As Long
    n = doc.Name
    p = InStrRev(n, ".")
    If p > 1 Then n = Left$(n, p - 1)
    MediaFolderName = n & "_media"
End Function

' Copies src into the document's media folder and returns the path relative to
' the document folder, or "" on failure.
Private Function CopyToMediaFolder(doc As Document, ByVal src As String) As String
    Dim fso As Object, folder As String, fname As String, dest As String
    Dim base As String, ext As String, n As Long
    Set fso = CreateObject("Scripting.FileSystemObject")
    folder = fso.BuildPath(doc.Path, MediaFolderName(doc))
    fname = fso.GetFileName(src)
    dest = fso.BuildPath(folder, fname)

    On Error GoTo Failed
    If Not fso.FolderExists(folder) Then fso.CreateFolder folder
    If StrComp(fso.GetAbsolutePathName(src), fso.GetAbsolutePathName(dest), vbTextCompare) <> 0 Then
        Application.StatusBar = U("מעתיק את הסרטון...")
        If fso.FileExists(dest) Then
            ' Same name but a different file: pick a free name.
            If fso.GetFile(dest).Size <> fso.GetFile(src).Size Then
                base = fso.GetBaseName(src)
                ext = fso.GetExtensionName(src)
                If ext <> "" Then ext = "." & ext
                n = 2
                Do
                    fname = base & " (" & n & ")" & ext
                    dest = fso.BuildPath(folder, fname)
                    n = n + 1
                Loop While fso.FileExists(dest)
                fso.CopyFile src, dest
            End If
        Else
            fso.CopyFile src, dest
        End If
        Application.StatusBar = ""
    End If
    CopyToMediaFolder = MediaFolderName(doc) & "\" & fname
    Exit Function

Failed:
    Application.StatusBar = ""
    Msg U("לא הצלחתי להעתיק את הסרטון לתיקייה:") & vbCr & folder & vbCr & vbCr & Err.Description, vbExclamation
End Function

Private Function ResolveVideoPath(doc As Document, ByVal rel As String) As String
    Dim fso As Object, fname As String, i As Long
    Dim cands(0 To 2) As String
    Set fso = CreateObject("Scripting.FileSystemObject")
    fname = fso.GetFileName(rel)
    If Mid$(rel, 2, 1) = ":" Or Left$(rel, 2) = "\\" Then
        cands(0) = rel
    ElseIf doc.Path <> "" Then
        cands(0) = fso.BuildPath(doc.Path, rel)
    End If
    If doc.Path <> "" Then
        ' The document may have been renamed, or the file put right next to it.
        cands(1) = fso.BuildPath(fso.BuildPath(doc.Path, MediaFolderName(doc)), fname)
        cands(2) = fso.BuildPath(doc.Path, fname)
    End If
    For i = 0 To 2
        If cands(i) <> "" Then
            If fso.FileExists(cands(i)) Then
                ResolveVideoPath = cands(i)
                Exit Function
            End If
        End If
    Next i
End Function

Private Function VideoTag(shp As Object) As String
    Dim alt As String
    On Error Resume Next
    alt = shp.AlternativeText
    On Error GoTo 0
    If Left$(alt, Len(TAG_PREFIX)) = TAG_PREFIX Then VideoTag = Mid$(alt, Len(TAG_PREFIX) + 1)
End Function

Private Function IsPlayer(shp As Object) As Boolean
    Dim progId As String
    On Error Resume Next
    progId = shp.OLEFormat.progId
    On Error GoTo 0
    IsPlayer = (LCase$(Left$(progId, Len(WMP_PROGID))) = LCase$(WMP_PROGID))
End Function

Private Function LoadPlayer(doc As Document, shp As Object) As Long
    Dim full As String, wmp As Object
    full = ResolveVideoPath(doc, VideoTag(shp))
    If full = "" Then
        LoadPlayer = LOAD_NO_FILE
        Exit Function
    End If

    LoadPlayer = LOAD_NO_PLAYER
    On Error GoTo Done
    Set wmp = shp.OLEFormat.Object
    wmp.settings.autoStart = False
    wmp.uiMode = "full"
    wmp.stretchToFit = True
    wmp.enableContextMenu = True
    If StrComp(wmp.URL, full, vbTextCompare) <> 0 Then wmp.URL = full
    LoadPlayer = LOAD_OK
Done:
End Function

Private Sub CheckPlayer(doc As Document, shp As Object, found As Long, missing As String, blocked As Boolean)
    Dim rel As String
    rel = VideoTag(shp)
    If rel = "" Then Exit Sub
    Select Case LoadPlayer(doc, shp)
        Case LOAD_OK: found = found + 1
        Case LOAD_NO_FILE: missing = missing & "   " & rel & vbCr
        Case Else: blocked = True
    End Select
End Sub

Private Sub ReportLoad(ByVal result As Long, ByVal rel As String)
    Select Case result
        Case LOAD_NO_FILE
            Msg U("לא מצאתי את הקובץ ") & rel, vbExclamation
        Case LOAD_NO_PLAYER
            Msg U("הנגן נוסף אבל לא נטען. אם מופיע פס צהוב של אזהרת אבטחה, לחץ ""הפוך תוכן לזמין"" ואחר כך ""רענן נגנים""."), vbExclamation
    End Select
End Sub

' Finds a player in the selection or in the paragraph of the cursor.
Private Function PlayerAtCursor() As Object
    Dim shp As Object, sr As Object
    For Each shp In Selection.InlineShapes
        If IsPlayer(shp) Then Set PlayerAtCursor = shp: Exit Function
    Next shp
    For Each shp In Selection.Paragraphs(1).Range.InlineShapes
        If IsPlayer(shp) Then Set PlayerAtCursor = shp: Exit Function
    Next shp
    On Error Resume Next
    Set sr = Selection.ShapeRange
    On Error GoTo 0
    If Not sr Is Nothing Then
        For Each shp In sr
            If IsPlayer(shp) Then Set PlayerAtCursor = shp: Exit Function
        Next shp
    End If
End Function

' widthPt <= 0 means the full width between the margins.
Private Sub SetPlayerWidth(shp As Object, ByVal widthPt As Single)
    Dim usable As Single
    With Selection.Sections(1).PageSetup
        usable = .PageWidth - .LeftMargin - .RightMargin - .Gutter
    End With
    If widthPt <= 0 Or widthPt > usable Then widthPt = usable
    On Error Resume Next
    shp.LockAspectRatio = 0 ' msoFalse
    shp.Width = widthPt
    shp.Height = widthPt * 9 / 16 + CONTROLS_HEIGHT
End Sub

Private Sub Msg(ByVal text As String, ByVal style As Long)
    MsgBox text, style Or vbMsgBoxRtlReading Or vbMsgBoxRight, U("נגן וידאו ל-Word")
End Sub

' Decodes \uXXXX escapes (the installer writes Hebrew this way).
Public Function U(ByVal s As String) As String
    Dim i As Long
    i = InStr(s, "\u")
    Do While i > 0
        s = Left$(s, i - 1) & ChrW(CLng("&H" & Mid$(s, i + 2, 4))) & Mid$(s, i + 6)
        i = InStr(i + 1, s, "\u")
    Loop
    U = s
End Function
