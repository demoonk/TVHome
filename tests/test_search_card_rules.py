import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'launcher' / 'app' / 'src' / 'main' / 'java' / 'ru' / 'pda' / 'tvhome'
JAVAC = shutil.which('javac')
JAVA = shutil.which('java')

# Facts recorded on the living-room TV (192.168.1.7) on 2026-11-09 with `content query` and
# `cmd package query-activities`. Rows are real provider rows, handlers are real exported activities.
# A search row can only be opened through a target the app itself declares, so this table is the
# contract the code must keep; anything not listed here must not be invented at runtime.
MECHANISM = {
    'smarttube': 'entry+activity',
    'num': 'provider+activity',
    'kinopoisk': 'site+activity',
    'kion': 'activity',
    'vk': 'entry+site+activity',
    'rutube': 'site',
    'reshala': 'provider+entry+activity',
    'vimu': 'activity',
    'tizentube': 'entry',
    'boosty': 'entry',
    'lift': 'open-only',
    'mpv': 'open-only',
}

CHECK = r"""
package ru.pda.tvhome;

public class SearchCheck {
    static int failures = 0;

    static void check(String name, boolean condition) {
        System.out.println((condition ? "PASS " : "FAIL ") + name);
        if (!condition) failures++;
    }

    public static void main(String[] args) {
        check("NUM provider answers at the path the code uses",
                "ru.yourok.num.atvsearch".equals(SearchCardRules.authority("num"))
                        && "/search/search_suggest_query".equals(SearchCardRules.path("num")));
        check("Reshala provider answers without the /search prefix",
                "reshala.tv.app.poisk".equals(SearchCardRules.authority("reshala"))
                        && "/search_suggest_query".equals(SearchCardRules.path("reshala")));
        check("providers that reject the standard path have no rule",
                SearchCardRules.authority("kinopoisk") == null && SearchCardRules.authority("vk") == null
                        && SearchCardRules.authority("kion") == null && SearchCardRules.authority("vimu") == null);
        check("authority is never paired with a missing path",
                SearchCardRules.path("num") != null && SearchCardRules.path("reshala") != null);

        check("NUM row opens the card through its own app link",
                "https://www.themoviedb.org/movie/808".equals(SearchCardRules.cardUrl("num", null, "808", "movie")));
        check("Reshala row keeps the deeplink the provider returned",
                "reshala://open?screen=card%3Atmdb%3A%2F%2Fmovie%2F808".equals(SearchCardRules.cardUrl(
                        "reshala", "reshala://open?screen=card%3Atmdb%3A%2F%2Fmovie%2F808", "0", null)));
        check("SmartTube row with a video id opens that video",
                "https://www.youtube.com/watch?v=Swiz1XyfhcI".equals(
                        SearchCardRules.cardUrl("smarttube", null, "Swiz1XyfhcI", null)));
        check("SmartTube rows that carry only a database id are not guessed",
                SearchCardRules.cardUrl("smarttube", null, "490779098", null) == null);
        check("KinoPoisk card stays on the host the app registered",
                "https://hd.kinopoisk.ru/film/3401/".equals(SearchCardRules.cardUrl("kinopoisk", null, "3401", null)));
        check("VK card stays on vk.com",
                "https://vk.com/video-111180788_456249130".equals(
                        SearchCardRules.cardUrl("vk", null, "111180788_456249130", null)));
        check("nothing is invented for a row without a verified target",
                SearchCardRules.cardUrl("kion", null, "1234", null) == null
                        && SearchCardRules.cardUrl("vimu", null, "12", null) == null
                        && SearchCardRules.cardUrl("num", null, "808", null) == null
                        && SearchCardRules.cardUrl("num", null, "808", "person") == null
                        && SearchCardRules.cardUrl("smarttube", null, "tooshort", null) == null
                        && SearchCardRules.cardUrl("vk", null, "111180788", null) == null);

        check("entry points are only the ones the app resolves to",
                "vkvideo://vk.com/search?query=shrek".equals(AppSearchEntry.searchUrl("vk", "shrek"))
                        && "reshala://search?q=shrek".equals(AppSearchEntry.searchUrl("reshala", "shrek"))
                        && "https://www.youtube.com/results?search_query=shrek".equals(
                                AppSearchEntry.searchUrl("smarttube", "shrek"))
                        && "https://vkplay.live/search?query=shrek".equals(AppSearchEntry.searchUrl("boosty", "shrek")));
        check("button promises search only where an entry or a verified usable search activity exists",
                "Искать в VK Видео".equals(AppSearchEntry.buttonLabel(app("vk", "VK Видео", "com.vk.tv", "deeplink"), false))
                        && "Искать в РешалаТВ".equals(AppSearchEntry.buttonLabel(app("reshala", "РешалаТВ", "reshala.tv.app", "reshala"), false))
                        && "Искать в Boosty".equals(AppSearchEntry.buttonLabel(app("boosty", "Boosty", "live.vkplay.app", "manual"), false))
                        && "Искать в VK Видео".equals(AppSearchEntry.buttonLabel(app("vk", "VK Видео", "com.vk.tv", "deeplink"), true))
                        && "Открыть KION".equals(AppSearchEntry.buttonLabel(app("kion", "KION", "ru.mts.mtstv", "provider"), true))
                        && "Открыть KION".equals(AppSearchEntry.buttonLabel(app("kion", "KION", "ru.mts.mtstv", "provider"), false))
                        && "Открыть Lift".equals(AppSearchEntry.buttonLabel(app("lift", "Lift", "io.lift.app", "manual"), false))
                        && "Открыть mpv".equals(AppSearchEntry.buttonLabel(app("mpv", "mpv", "is.xyz.mpv", "manual"), false)));

        check("every row has one verified way to open it", verifiedTableOk());
        check("apps that cannot search are never promised a search",
                AppSearchEntry.base("lift") == null && AppSearchEntry.base("mpv") == null
                        && SearchCardRules.authority("lift") == null && SearchCardRules.authority("mpv") == null);

        System.out.println(failures == 0 ? "ALL PASS" : "FAILURES " + failures);
        System.exit(failures == 0 ? 0 : 1);
    }

    static boolean verifiedTableOk() {
        String[][] table = {
                {"smarttube", "entry+activity"}, {"num", "provider+activity"},
                {"kinopoisk", "site+activity"}, {"kion", "activity"},
                {"vk", "entry+site+activity"}, {"rutube", "site"},
                {"reshala", "provider+entry+activity"}, {"vimu", "activity"},
                {"tizentube", "entry"}, {"boosty", "entry"},
                {"lift", "open-only"}, {"mpv", "open-only"}
        };
        boolean ok = true;
        for (String[] row : table) {
            String id = row[0], mechanism = row[1];
            boolean provider = SearchCardRules.authority(id) != null;
            boolean entry = AppSearchEntry.base(id) != null;
            boolean site = SiteSearchRules.content(id, sample(id));
            boolean matches = provider == mechanism.contains("provider")
                    && entry == mechanism.contains("entry")
                    && site == mechanism.contains("site");
            boolean openable = provider || entry || site || mechanism.contains("activity")
                    || "open-only".equals(mechanism);
            if (!matches || !openable) {
                System.out.println("FAIL row " + id + " mechanism " + mechanism
                        + " (provider " + provider + ", entry " + entry + ", site " + site + ")");
                ok = false;
            }
        }
        return ok;
    }

    static MediaVoiceApps.App app(String id, String label, String pkg, String mode) {
        return new MediaVoiceApps.App(id, label, pkg, mode);
    }

    static String sample(String id) {
        if ("kinopoisk".equals(id)) return "https://kinopoisk.ru/film/3401/";
        if ("rutube".equals(id)) return "https://rutube.ru/video/0123456789abcdef0123456789abcdef/";
        if ("vk".equals(id)) return "https://vk.com/video-111180788_456249130";
        return "https://example.invalid/";
    }
}
"""

# MediaVoiceApps is the rule table itself; the activity it calls for alias lookup is replaced by a stub
# so the check stays on plain JVM without android.jar.
STUB = """
package ru.pda.tvhome;

public final class VoiceLaunchActivity {
    static String normalize(String spoken) { return spoken; }
}
"""


class SearchRulesTest(unittest.TestCase):
    def test_rule_classes_stay_free_of_android_apis(self):
        for name in ('SearchCardRules.java', 'AppSearchEntry.java', 'SiteSearchRules.java', 'MediaVoiceApps.java'):
            self.assertNotIn('import android', (SRC / name).read_text(encoding='utf-8'), name)

    def test_card_click_uses_the_verified_target_and_falls_back_to_the_exported_activity(self):
        media = (SRC / 'MediaSearch.java').read_text(encoding='utf-8')
        self.assertIn('SearchCardRules.cardUrl(app.id, data, dataId, extra)', media)
        self.assertIn('new Intent(Intent.ACTION_VIEW, Uri.parse(target)).setPackage(app.pkg)', media)
        # A row without a verified target can only go to an activity the app exported.
        self.assertIn('if (candidate.activityInfo.exported) return candidate.activityInfo.name;', media)
        self.assertIn('.setComponent(searchable.getSearchActivity())', media)
        # A row without a data URI still has to hand the app its row identifier.
        self.assertIn('else if (dataId != null) intent.putExtra(SearchManager.EXTRA_DATA_KEY, dataId);', media)
        self.assertIn('SearchCardRules.authority(app.id)', media)
        self.assertIn('SearchCardRules.path(app.id)', media)

    def test_button_click_launches_the_verified_entry(self):
        launch = (SRC / 'VoiceLaunchActivity.java').read_text(encoding='utf-8')
        self.assertIn('AppSearchEntry.searchUrl(app.id, query)', launch)
        self.assertIn('new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(entry)).setPackage(app.pkg)', launch)
        self.assertIn('SiteSearch.open(this, app, query)', launch)
        self.assertIn('MediaSearch.searchActivity(this, app.pkg)', launch)
        entry = (SRC / 'AppSearchEntry.java').read_text(encoding='utf-8')
        self.assertIn('base(app.id) != null || (hasSearchActivity && searchActivityIsUsable(app.id))\n                ? "Искать в " + app.label', entry)
        self.assertIn('"Открыть " + app.label', entry)

    def test_every_card_and_button_goes_through_the_search_navigation_task(self):
        view = (SRC / 'MovieSearchView.java').read_text(encoding='utf-8')
        self.assertIn('SearchNavigation.open(activity, hit.intent)', view)
        nav = (SRC / 'SearchNavigation.java').read_text(encoding='utf-8')
        self.assertIn('activity.startActivity(intent)', nav)

    @unittest.skipUnless(JAVAC and JAVA, 'Java unavailable')
    def test_verified_mappings(self):
        with tempfile.TemporaryDirectory(prefix='search-check-', dir=ROOT / 'tests') as temp:
            work = Path(temp).resolve()
            assert work.is_relative_to(ROOT)
            check = work / 'SearchCheck.java'
            check.write_text(CHECK, encoding='utf-8')
            stub = work / 'VoiceLaunchActivity.java'
            stub.write_text(STUB, encoding='utf-8')
            sources = [str(SRC / name) for name in
                       ('SearchCardRules.java', 'AppSearchEntry.java', 'SiteSearchRules.java', 'MediaVoiceApps.java')]
            compiled = subprocess.run(
                [JAVAC, '-encoding', 'utf-8', '-d', str(work)] + sources + [str(stub), str(check)],
                capture_output=True, text=True, timeout=120)
            self.assertEqual(0, compiled.returncode, compiled.stdout + compiled.stderr)
            run = subprocess.run([JAVA, '-cp', str(work), 'ru.pda.tvhome.SearchCheck'],
                                 capture_output=True, text=True, timeout=60)
            self.assertEqual(0, run.returncode, run.stdout + run.stderr)
            self.assertIn('ALL PASS', run.stdout)


if __name__ == '__main__':
    unittest.main()
