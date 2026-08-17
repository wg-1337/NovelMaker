; NovelMaker Windows 现代安装包（Inno Setup 6）
; 自动检测简体中文语言文件：存在则使用中文界面，否则使用英文
#define MyAppName "NovelMaker"
#define MyAppVersion "1.6.0"
#define MyAppPublisher "wg-1337"
#define MyAppExeName "novelmaker.exe"
#define hasChinese FileExists("C:\Program Files (x86)\Inno Setup 6\Languages\ChineseSimplified.isl")

[Setup]
AppId={{B5A9E3C0-1F2A-4B7D-9C4E-8D6F1A2B3C4D}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
DefaultDirName={autopf}\novelmaker
DefaultGroupName=novelmaker
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
OutputDir=..\..\desktopApp\build\compose\binaries\main\exe
OutputBaseFilename=novelmaker-{#MyAppVersion}-setup
SetupIconFile=..\..\desktopApp\src\main\resources\novelmaker.ico
UninstallDisplayIcon={app}\novelmaker.exe
Compression=lzma2
SolidCompression=yes
WizardStyle=modern

[Languages]
#if hasChinese
Name: "chinesesimp"; MessagesFile: "compiler:Languages\ChineseSimplified.isl"
#endif
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "创建桌面快捷方式"; GroupDescription: "附加任务："; Flags: unchecked

[Files]
Source: "..\..\desktopApp\build\compose\binaries\main\app\novelmaker\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\NovelMaker"; Filename: "{app}\novelmaker.exe"
Name: "{autodesktop}\NovelMaker"; Filename: "{app}\novelmaker.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\novelmaker.exe"; Description: "运行 NovelMaker"; Flags: nowait postinstall skipifsilent
