"""Write update.json and SHA256SUMS.txt for a TVHome release folder.

Usage: python tools/make-release-manifest.py 2.1.11 155
"""

import hashlib
import json
import pathlib
import sys

version = sys.argv[1] if len(sys.argv) > 1 else "2.1.10"
version_code = int(sys.argv[2]) if len(sys.argv) > 2 else 154

folder = pathlib.Path(f"C:/Users/demOOn/Documents/TV/TVHome/build-output/release-{version}")

parts = [
    {"name": "TVHome-weather.apk", "package": "io.github.sumyabazar1.weather", "versionCode": 1},
    {"name": "bridge-helper-1.7.apk", "package": "io.github.sumyabazar1.bridgehelper", "versionCode": 8},
    {"name": f"TVHome-remote-{version}.dex"},
    {"name": "assistant-package-guard.sh"},
    {"name": "tvhome-bridge-ensure.sh"},
]

launcher = f"TVHome-{version}.apk"
digests = {}
for name in [launcher] + [part["name"] for part in parts] + ["install.ps1", "install.sh", "Setup-TVHome.ps1"]:
    digests[name] = hashlib.sha256((folder / name).read_bytes()).hexdigest()

update = {
    "versionCode": version_code,
    "versionName": version,
    "apkName": launcher,
    "sha256": digests[launcher],
    "components": [dict(part, sha256=digests[part["name"]]) for part in parts],
}
(folder / "update.json").write_text(json.dumps(update, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

lines = "\n".join(f"{digests[name]}  {name}" for name in sorted(digests)) + "\n"
(folder / "SHA256SUMS.txt").write_text(lines, encoding="utf-8")
print(lines)
