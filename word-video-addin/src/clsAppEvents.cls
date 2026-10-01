VERSION 1.0 CLASS
BEGIN
  MultiUse = -1  'True
END
Attribute VB_Name = "clsAppEvents"
Attribute VB_GlobalNameSpace = False
Attribute VB_Creatable = False
Attribute VB_PredeclaredId = False
Attribute VB_Exposed = False
Option Explicit

Public WithEvents App As Word.Application

' Point the players at the video files whenever a document is opened.
Private Sub App_DocumentOpen(ByVal Doc As Document)
    RefreshPlayers Doc, False
End Sub
