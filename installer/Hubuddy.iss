; Inno Setup recipe for HubuddySetup.exe.   iscc installer\Hubuddy.iss /DAppVersion=3.0.0 /DSourceExe=dist\Hubuddy.exe
#ifndef AppVersion
  #define AppVersion "3.5.0"
#endif
#ifndef SourceExe
  #define SourceExe "..\dist\Hubuddy.exe"
#endif

[Setup]
AppId={{8B3D6C1E-5A47-4E0B-9C32-A1B2C3D4E5F6}
AppName=Hubuddy
AppVersion={#AppVersion}
AppPublisher=Hubuddy
DefaultDirName={autopf}\Hubuddy
DefaultGroupName=Hubuddy
OutputBaseFilename=HubuddySetup
OutputDir=..\dist
Compression=lzma2
SolidCompression=yes
PrivilegesRequired=admin
ArchitecturesInstallIn64BitMode=x64compatible
UninstallDisplayIcon={app}\Hubuddy.exe
WizardStyle=modern
SetupIconFile=..\agent\hubuddy.ico
DisableProgramGroupPage=yes

[Tasks]
Name: "startup"; Description: "Start Hubuddy when I sign in to Windows"; GroupDescription: "Options:"
Name: "firewall"; Description: "Allow phones on private networks (adds a Windows Firewall rule)"; GroupDescription: "Options:"; Flags: checkedonce

[Files]
Source: "{#SourceExe}"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{group}\Hubuddy"; Filename: "{app}\Hubuddy.exe"

[Registry]
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "Hubuddy"; ValueData: """{app}\Hubuddy.exe"" --minimized"; Flags: uninsdeletevalue; Tasks: startup

[Run]
; Private networks only, for the program itself, so the port it picks and its discovery socket are both covered.
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall add rule name=""Hubuddy"" dir=in action=allow program=""{app}\Hubuddy.exe"" profile=private enable=yes"; Flags: runhidden; Tasks: firewall
Filename: "{app}\Hubuddy.exe"; Description: "Open Hubuddy"; Flags: nowait postinstall skipifsilent

[UninstallRun]
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""Hubuddy"""; Flags: runhidden; RunOnceId: "RemoveFirewall"
