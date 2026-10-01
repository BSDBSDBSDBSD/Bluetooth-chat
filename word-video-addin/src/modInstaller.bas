Attribute VB_Name = "modInstaller"
Option Explicit

' Installer carried inside the WordVideo-Install document.
' WordVideo.dotm is embedded as Base64 in a Custom XML part of this document
' (namespace below), not in code, so there is no oversized VBA procedure.
' Clicking "התקן" writes it to Word's STARTUP folder and loads it at once, so
' the add-in is active immediately and on every later start of Word.

Private Const ADDIN_NAME As String = "WordVideo.dotm"
Private Const PAYLOAD_NS As String = "urn:wordvideo:payload"

Public Sub RibbonInstall(control As IRibbonControl)
    InstallAddin
End Sub

Public Sub RibbonUninstall(control As IRibbonControl)
    UninstallAddin
End Sub

Public Sub InstallAddin()
    Dim startup As String, dest As String, b64 As String, bytes() As Byte, i As Integer

    b64 = ReadPayload()
    If b64 = "" Then
        Msg U("לא מצאתי את קובץ התוסף בתוך המסמך. ייתכן שהמסמך נשמר מחדש ואיבד את התוכן. " & _
              "הורד שוב את הקובץ המקורי ונסה שוב."), vbExclamation
        Exit Sub
    End If

    startup = Application.startupPath
    If startup = "" Then
        Msg U("לא מצאתי את תיקיית ההפעלה של Word."), vbExclamation
        Exit Sub
    End If
    On Error Resume Next
    If Dir(startup, vbDirectory) = "" Then MkDir startup
    On Error GoTo 0
    dest = startup & "\" & ADDIN_NAME

    On Error GoTo Failed
    bytes = DecodeBase64(b64)
    WriteBytes dest, bytes

    ' Remove a previous copy from the add-ins list, then load the new one.
    For i = Application.AddIns.Count To 1 Step -1
        If LCase$(Application.AddIns(i).Name) = LCase$(ADDIN_NAME) Then
            Application.AddIns(i).Installed = False
        End If
    Next i
    On Error Resume Next
    Application.AddIns.Add dest, True
    On Error GoTo 0

    Msg U("התוסף הותקן!" & vbCr & vbCr & _
          "למעלה, ליד הלשונית ""הוספה"", יש עכשיו לשונית בשם ""וידאו"". " & _
          "משם מוסיפים סרטון למסמך." & vbCr & vbCr & _
          "התוסף ייטען אוטומטית בכל פעם שתפתח את Word. אפשר לסגור את המסמך הזה."), vbInformation
    Exit Sub

Failed:
    Msg U("ההתקנה נכשלה:") & vbCr & vbCr & Err.Description & vbCr & vbCr & _
        U("נסה לסגור חלונות Word אחרים ולנסות שוב."), vbExclamation
End Sub

Public Sub UninstallAddin()
    Dim dest As String, i As Integer
    dest = Application.startupPath & "\" & ADDIN_NAME
    For i = Application.AddIns.Count To 1 Step -1
        If LCase$(Application.AddIns(i).Name) = LCase$(ADDIN_NAME) Then
            Application.AddIns(i).Installed = False
        End If
    Next i
    On Error Resume Next
    If Dir(dest) <> "" Then Kill dest
    On Error GoTo 0
    Msg U("התוסף הוסר."), vbInformation
End Sub

' Reads the Base64 add-in from the document's Custom XML part.
Private Function ReadPayload() As String
    Dim parts As Object
    On Error Resume Next
    Set parts = ActiveDocument.CustomXMLParts.SelectByNamespace(PAYLOAD_NS)
    If Not parts Is Nothing Then
        If parts.Count >= 1 Then ReadPayload = parts(1).DocumentElement.Text
    End If
    On Error GoTo 0
End Function

' MSXML decodes the Base64; ADODB writes the bytes to disk.
Private Function DecodeBase64(ByVal b64 As String) As Byte()
    Dim xml As Object, node As Object
    On Error Resume Next
    Set xml = CreateObject("MSXML2.DOMDocument.6.0")
    If xml Is Nothing Then Set xml = CreateObject("MSXML2.DOMDocument")
    On Error GoTo 0
    Set node = xml.createElement("b")
    node.DataType = "bin.base64"
    node.Text = b64
    DecodeBase64 = node.nodeTypedValue
End Function

Private Sub WriteBytes(ByVal path As String, ByRef bytes() As Byte)
    Dim stream As Object
    Set stream = CreateObject("ADODB.Stream")
    stream.Type = 1 ' binary
    stream.Open
    stream.Write bytes
    stream.SaveToFile path, 2 ' overwrite
    stream.Close
End Sub

Private Sub Msg(ByVal text As String, ByVal style As Long)
    MsgBox text, style Or vbMsgBoxRtlReading Or vbMsgBoxRight, U("התקנת נגן וידאו ל-Word")
End Sub

Public Function U(ByVal s As String) As String
    Dim i As Long
    i = InStr(s, "\u")
    Do While i > 0
        s = Left$(s, i - 1) & ChrW(CLng("&H" & Mid$(s, i + 2, 4))) & Mid$(s, i + 6)
        i = InStr(i + 1, s, "\u")
    Loop
    U = s
End Function
