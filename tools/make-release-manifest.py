import hashlib
import json
import pathlib

folder = pathlib.Path('C:/Users/demOOn/Documents/TV/TVHome/build-output/release-2.1.10')

parts = [
    {"name": "TVHome-weather.apk", "package": "io.github.sumyabazar1.weather", "versionCode": 1},
    {"name": "bridge-helper-1.7.apk", "package": "io.github.sumyabazar1.bridgehelper", "versionCode": 8},
    {"name": "TVHome-remote-2.1.10.dex"},
    {"name": "assistant-package-guard.sh"},
    {"name": "tvhome-bridge-ensure.sh"},
]

digests = {}
for name in ["TVHome-2.1.10.apk"] + [p["name"] for p in parts] + ["install.ps1", "install.sh", "Setup-TVHome.ps1"]:
    digests[name] = hashlib.sha256((folder / name).read_bytes()).hexdigest()

update = {
    "versionCode": 154,
    "versionName": "2.1.10",
    "apkName": "TVHome-2.1.10.apk",
    "sha256": digests["TVHome-2.1.10.apk"],
    "components": [
        dict(part, sha256=digests[part["name"]]) for part in parts
    ],
}
(folder / "update.json").write_text(json.dumps(update, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

lines = "\n".join(f"{digests[name]}  {name}" for name in sorted(digests)) + "\n"
(folder / "SHA256SUMS.txt").write_text(lines, encoding="utf-8")
print(lines)
