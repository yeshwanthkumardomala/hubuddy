# PyInstaller recipe for Hubuddy.exe:   pyinstaller --noconfirm agent/hubuddy.spec
from PyInstaller.utils.hooks import collect_submodules

block_cipher = None
agent = SPECPATH  # the folder holding this file (PyInstaller sets SPECPATH)

hidden = (collect_submodules("hubuddy_agent") + collect_submodules("winrt")
          + ["tkinter", "tkinter.ttk", "tkinter.messagebox", "segno", "cryptography.hazmat.primitives.asymmetric.ec", "zoneinfo", "tzdata"])

a = Analysis(
    [f"{agent}/hubuddy_launcher.py"],
    pathex=[agent],
    binaries=[],
    datas=[(f"{agent}/web", "web"), (f"{agent}/config.example.toml", ".")],
    hiddenimports=hidden,
    excludes=["pytest", "unittest.mock"],
    cipher=block_cipher,
)
pyz = PYZ(a.pure, a.zipped_data, cipher=block_cipher)
exe = EXE(
    pyz, a.scripts, a.binaries, a.zipfiles, a.datas, [],
    name="Hubuddy",
    console=False,   # a window, not a console; --selftest writes its result to a file
    upx=False,
    icon=None,
)
