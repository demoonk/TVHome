"""Refresh the embedded guard and ensure-script blobs inside Setup-TVHome.ps1.

Run after editing tools/assistant-package-guard.sh or tools/tvhome-bridge-ensure.sh,
before building the release manifest: the installer carries its own copy of both scripts.
The file is written with a UTF-8 BOM so Windows PowerShell 5.1 reads the Russian text.
"""

import base64
import pathlib
import re

root = pathlib.Path(__file__).resolve().parent.parent
installer = root / "Setup-TVHome.ps1"
text = installer.read_text(encoding="utf-8-sig")


def replace(name, blob):
    global text
    b64 = base64.b64encode(blob).decode("ascii")
    pattern = re.compile(r"\$" + name + r" = '[A-Za-z0-9+/=]*'")
    text, count = pattern.subn("$" + name + " = '" + b64 + "'", text)
    print(name, "replacements:", count)


tools = root / "tools"
replace("guardBase64", (tools / "assistant-package-guard.sh").read_bytes())
replace("ensureBase64", (tools / "tvhome-bridge-ensure.sh").read_bytes())
installer.write_bytes(b"\xef\xbb\xbf" + text.encode("utf-8"))
print("written bytes:", len(installer.read_bytes()),
      "starts with BOM:", installer.read_bytes()[:3] == b"\xef\xbb\xbf")
