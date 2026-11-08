import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVAC = shutil.which("javac")
JAVA = shutil.which("java")

PICTURE = "com.android.tv.settings/.partnercustomizer.picture.PictureActivity"
SOUND = "com.android.tv.settings/.device.sound.SoundActivity"

CHECK = r"""
public class GearCheck {
    static int failures = 0;

    static void check(String name, boolean condition) {
        System.out.println((condition ? "PASS " : "FAIL ") + name);
        if (!condition) failures++;
    }

    /* Little-endian input_event record: timeval, u16 type, u16 code, u32 value. */
    static byte[] record(int size, int type, int code, int value) {
        byte[] r = new byte[size];
        int base = size - 8;
        r[base] = (byte) (type & 255);
        r[base + 1] = (byte) ((type >> 8) & 255);
        r[base + 2] = (byte) (code & 255);
        r[base + 3] = (byte) ((code >> 8) & 255);
        r[base + 4] = (byte) (value & 255);
        r[base + 5] = (byte) ((value >> 8) & 255);
        r[base + 6] = (byte) ((value >> 16) & 255);
        r[base + 7] = (byte) ((value >> 24) & 255);
        return r;
    }

    public static void main(String[] args) {
        String picture = "%PICTURE%";
        String sound = "%SOUND%";

        for (int size : new int[] {16, 24}) {
            byte[] press = record(size, GearDecision.EV_KEY, GearDecision.KEY_UNKNOWN, 1);
            byte[] release = record(size, GearDecision.EV_KEY, GearDecision.KEY_UNKNOWN, 0);
            byte[] backRelease = record(size, GearDecision.EV_KEY, GearDecision.KEY_BACK, 0);
            byte[] scan = record(size, GearDecision.EV_MSC, GearDecision.MSC_SCAN, GearDecision.GEAR_SCAN);

            check("record layout size " + size + " type", GearDecision.type(press, size) == GearDecision.EV_KEY);
            check("record layout size " + size + " code", GearDecision.code(press, size) == GearDecision.KEY_UNKNOWN);
            check("record layout size " + size + " press value", GearDecision.value(press, size) == 1);
            check("record layout size " + size + " release value", GearDecision.value(release, size) == 0);
            check("record layout size " + size + " back key", GearDecision.code(backRelease, size) == GearDecision.KEY_BACK);
            check("gear scan code size " + size, GearDecision.code(scan, size) == GearDecision.MSC_SCAN);
            check("gear scan value size " + size, GearDecision.value(scan, size) == GearDecision.GEAR_SCAN);
        }

        check("default long press threshold is 700 ms", GearDecision.DEFAULT_LONG_PRESS_MS == 700);
        check("single press opens picture settings",
                GearDecision.targetFor(120, GearDecision.DEFAULT_LONG_PRESS_MS, true, picture, sound).equals(picture));
        check("held press opens sound settings",
                GearDecision.targetFor(1200, GearDecision.DEFAULT_LONG_PRESS_MS, true, picture, sound).equals(sound));
        check("threshold boundary is inclusive",
                GearDecision.targetFor(700, GearDecision.DEFAULT_LONG_PRESS_MS, true, picture, sound).equals(sound)
                        && GearDecision.targetFor(699, GearDecision.DEFAULT_LONG_PRESS_MS, true, picture, sound).equals(picture));
        check("custom threshold is respected",
                GearDecision.targetFor(800, 1000, true, picture, sound).equals(picture)
                        && GearDecision.targetFor(1100, 1000, true, picture, sound).equals(sound));
        check("missing sound activity falls back to picture",
                GearDecision.targetFor(1500, GearDecision.DEFAULT_LONG_PRESS_MS, false, picture, sound).equals(picture));
        check("debounce keeps presses separated", GearDecision.DEBOUNCE_MS > GearDecision.DEFAULT_LONG_PRESS_MS);

        System.out.println(failures == 0 ? "ALL PASS" : "FAILURES " + failures);
        System.exit(failures == 0 ? 0 : 1);
    }
}
"""


class GearDecisionTest(unittest.TestCase):
    def test_bridge_observes_events_without_grabbing_or_filtering(self):
        source = (ROOT / "tools" / "PqRemoteProof.java").read_text(encoding="utf-8")
        for forbidden in ("EVIOCGRAB", "FLAG_REQUEST_FILTER_KEY_EVENTS", "InputManager",
                          "injectEvent", "dispatchKeyEvent", "AccessibilityService", "onKeyEvent"):
            self.assertNotIn(forbidden, source)
        self.assertIn(PICTURE, source)
        self.assertIn(SOUND, source)
        self.assertIn("GearDecision.KEY_BACK", source)
        self.assertIn("PQ_REMOTE_READY", source)

    @unittest.skipUnless(JAVAC and JAVA, "Java unavailable")
    def test_decision_and_record_layout(self):
        with tempfile.TemporaryDirectory(prefix="gear-check-", dir=ROOT / "tests") as temp:
            work = Path(temp).resolve()
            assert work.is_relative_to(ROOT)
            check = work / "GearCheck.java"
            check.write_text(CHECK.replace("%PICTURE%", PICTURE).replace("%SOUND%", SOUND), encoding="utf-8")
            compiled = subprocess.run(
                [JAVAC, "-encoding", "utf-8", "-d", str(work), str(ROOT / "tools" / "GearDecision.java"), str(check)],
                capture_output=True, text=True, timeout=120)
            self.assertEqual(0, compiled.returncode, compiled.stdout + compiled.stderr)
            run = subprocess.run([JAVA, "-cp", str(work), "GearCheck"], capture_output=True, text=True, timeout=60)
            self.assertEqual(0, run.returncode, run.stdout + run.stderr)
            self.assertIn("ALL PASS", run.stdout)

    @unittest.skipUnless(JAVAC and JAVA, "Java unavailable")
    def test_arguments_accept_the_long_press_threshold(self):
        source = (ROOT / "tools" / "PqRemoteProof.java").read_text(encoding="utf-8")
        self.assertIn("device eventSize seconds [longPressMs]", source)
        self.assertIn("longPressMs < 100 || longPressMs > 5000", source)


if __name__ == "__main__":
    unittest.main()
