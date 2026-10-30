#!/usr/bin/env python3
"""Builds dist/Glass-TV-Setup-Mac.zip and dist/Glass-TV-Setup-Windows.zip from src/ and debloat-list.txt."""
import os, zipfile
H = os.path.dirname(os.path.abspath(__file__)); S = os.path.join(H, "src"); D = os.path.join(H, "dist")
os.makedirs(D, exist_ok=True)
LIST = open(os.path.join(H, 'debloat-list.txt')).read().strip()
rd = lambda f: open(os.path.join(S, f)).read()
def mac(name, body):
    t = "#!/bin/bash\n# %s.command - double-click on a Mac. Glass TV, MIT License (see README.txt).\n" % name + rd('common.sh') + rd(body)
    return t.replace('@@LIST@@', LIST)
def win(name, body):
    ps = "#<PS>\n" + rd('common.ps1') + rd(body).replace('@@LIST@@', LIST)
    bat = ('@echo off\r\nsetlocal\r\ntitle Glass TV - %s\r\nset "GLASS_BAT=%%~f0"\r\n'
           'powershell -NoProfile -ExecutionPolicy Bypass -Command "$t=[IO.File]::ReadAllText($env:GLASS_BAT); $i=$t.IndexOf(\'#\'+\'<PS>\'); '
           '$d=Join-Path (Split-Path $env:GLASS_BAT) \'tools\'; New-Item -ItemType Directory -Force $d | Out-Null; '
           '[IO.File]::WriteAllText((Join-Path $d \'%s.ps1\'), $t.Substring($i), (New-Object Text.UTF8Encoding $true))"\r\n'
           'powershell -NoProfile -ExecutionPolicy Bypass -File "%%~dp0tools\\%s.ps1" -Root "%%~dp0."\r\n'
           'echo.\r\npause\r\nexit /b\r\n') % (name, name, name)
    return bat + ps.replace('\r\n', '\n').replace('\n', '\r\n'), ps
README_MAC = """Glass TV setup for Mac
======================
Glass-Setup.command   sets up Glass on your TV (also the repair after a TV update)
Glass-Restore.command undoes everything Glass-Setup did

1. On the TV: Settings > System > About > press OK on "Android TV OS build" 7 times,
   then Settings > System > Developer options > turn on USB debugging.
2. Find the TV's IP address: Settings > Network & Internet > your Wi-Fi.
3. The first time only: right-click Glass-Setup.command > Open > Open (macOS Gatekeeper).
   After that, double-click works.
4. Type the IP when it asks, and press Allow on the TV (tick Always allow).
Keep this folder: it holds your TV's backup (backups/). Glass-Restore needs it.
Nothing is ever uninstalled: ad and tracking apps are only switched off, and come back with Glass-Restore.
Glass TV is MIT licensed; the ad/tracking list is from CleanYourTV (MIT, Copyright (c) 2026 Gathin).
"""
README_WIN = README_MAC.replace("for Mac\n======================", "for Windows\n==========================").replace(".command", ".bat").replace(
    "3. The first time only: right-click Glass-Setup.command > Open > Open (macOS Gatekeeper).\n   After that, double-click works.",
    "3. Double-click Glass-Setup.bat. If SmartScreen appears, click More info > Run anyway.")
for n, b in (('Glass-Setup', 'setup.sh'), ('Glass-Restore', 'restore.sh')):
    open(os.path.join(D, n + '.command'), 'w').write(mac(n, b)); os.chmod(os.path.join(D, n + '.command'), 0o755)
for n, b in (('Glass-Setup', 'setup.ps1'), ('Glass-Restore', 'restore.ps1')):
    bat, ps = win(n, b); open(os.path.join(D, n + '.bat'), 'w', newline='').write(bat); open(os.path.join(D, n + '.ps1'), 'w').write(ps)
def z(name, files, readme):
    with zipfile.ZipFile(os.path.join(D, name), 'w', zipfile.ZIP_DEFLATED) as f:
        for fn in files:
            i = zipfile.ZipInfo(fn); i.external_attr = (0o100755 if fn.endswith('.command') else 0o100644) << 16; i.compress_type = zipfile.ZIP_DEFLATED
            f.writestr(i, open(os.path.join(D, fn), 'rb').read())
        i = zipfile.ZipInfo('README.txt'); i.external_attr = 0o100644 << 16; f.writestr(i, readme.replace('\n', '\r\n') if 'Windows' in name else readme)
z('Glass-TV-Setup-Mac.zip', ['Glass-Setup.command', 'Glass-Restore.command'], README_MAC)
z('Glass-TV-Setup-Windows.zip', ['Glass-Setup.bat', 'Glass-Restore.bat'], README_WIN)
print('built', sorted(os.listdir(D)))
