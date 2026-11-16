package ru.pda.tvhome;

import android.app.Activity;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.*;
import android.content.pm.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import org.json.*;
import java.io.*;
import java.lang.ref.WeakReference;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Public GitHub releases only. APKs remain subject to the system install confirmation. */
final class AppUpdates {
    static final String PREFS = "app_updates";
    /** The parts of a release the launcher keeps current on the TV besides its own APK. */
    private static final String PARTS_KEY = "release_parts", STAGED_KEY = "staged_";
    private static final int JOB_ID = 0x545655;
    private static final long DAY = 24L * 60 * 60 * 1000, MAX_APK = 100L * 1024 * 1024;
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean CHECKING = new AtomicBoolean(), INSTALLING = new AtomicBoolean(), STARTED = new AtomicBoolean();
    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, 0); }

    /** One part of a release besides the launcher APK: an APK the TV installs through the system installer, or
     *  a shell file the launcher stages in its own folder for the guard to copy. */
    static final class Part {
        final String name, url, sha256, packageName;
        final long versionCode;
        final boolean apk;
        Part(String name, String url, String sha256, String packageName, long versionCode, boolean apk) {
            this.name = name; this.url = url; this.sha256 = sha256;
            this.packageName = packageName; this.versionCode = versionCode; this.apk = apk;
        }
        /** The name the shell side of the TV expects, whatever the release calls the asset. */
        String stageName() {
            if (UpdateRules.shellName(name)) return "assistant-package-guard.sh";
            if (UpdateRules.ensureName(name)) return "tvhome-bridge-ensure.sh";
            return "tvhome-remote-2.dex";
        }
        String label() {
            if (apk) {
                if ("io.github.sumyabazar1.weather".equals(packageName)) return "погода";
                if (BridgeTrigger.HELPER_PACKAGE.equals(packageName)) return "помощник моста";
                return name;
            }
            if (UpdateRules.shellName(name)) return "guard моста";
            return UpdateRules.ensureName(name) ? "запуск моста" : "мост шестерёнки";
        }
    }
    static String repository(Context c) {
        return UpdateRules.repository(c.getString(R.string.update_repository));
    }
    @SuppressWarnings("deprecation")
    private static long code(PackageInfo p) { return Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode; }
    private static long installed(Context c) {
        try { return code(c.getPackageManager().getPackageInfo(c.getPackageName(), 0)); }
        catch (PackageManager.NameNotFoundException impossible) { return Long.MAX_VALUE; }
    }
    static String availableVersion(Context c) {
        SharedPreferences p = prefs(c);
        if (repository(c).isEmpty() || !repository(c).equals(p.getString("repository", ""))
                || p.getLong("version_code", 0) <= installed(c)) return "";
        return p.getString("version_name", "");
    }
    static void start(Context c, boolean force) { check(c, force && STARTED.compareAndSet(false,true), null); }
    static CancellationSignal check(Context context, boolean force, Runnable completed) {
        Context c = context.getApplicationContext();
        if (repository(c).isEmpty() || (!force && System.currentTimeMillis()-prefs(c).getLong("last_attempt",0)<DAY)
                || !CHECKING.compareAndSet(false,true)) { if(completed!=null) MAIN.post(completed); return null; }
        CancellationSignal cancellation=new CancellationSignal();
        prefs(c).edit().putLong("last_attempt",System.currentTimeMillis()).apply();
        WORK.execute(() -> {
            try { fetch(c,cancellation); }
            catch (Exception failure) { android.util.Log.d("AppUpdates", "Release check unavailable: " + failure.getClass().getSimpleName()); }
            finally { CHECKING.set(false); if(completed!=null) MAIN.post(completed); }
        });
        return cancellation;
    }
    /** Explicit user request: queue a fresh check even if a scheduled check is already running.
     * It bypasses both STARTED and the daily cache; the shared worker serializes network operations. */
    static void checkManually(Context context, java.util.function.Consumer<String> completed) {
        Context c = context.getApplicationContext();
        if (repository(c).isEmpty()) { MAIN.post(() -> completed.accept("GitHub ещё не настроен")); return; }
        WORK.execute(() -> {
            String message;
            prefs(c).edit().putLong("last_attempt", System.currentTimeMillis()).apply();
            try {
                if (!fetch(c, new CancellationSignal())) throw new IOException("No stable release");
                String version = availableVersion(c);
                List<Part> parts = pendingParts(c);
                if (parts.isEmpty()) {
                    message = version.isEmpty() ? "Установлена последняя версия" : "Вышла новая версия " + version;
                } else {
                    StringBuilder names = new StringBuilder();
                    for (int i = 0; i < parts.size(); i++) {
                        if (i > 0) names.append(", ");
                        names.append(parts.get(i).label());
                    }
                    message = version.isEmpty() ? "Доступны обновления компонентов: " + names
                            : "Вышла новая версия " + version + " и компоненты: " + names;
                }
            } catch (Exception failure) {
                message = "Не удалось проверить обновления. Проверьте сеть и повторите попытку";
            }
            final String result = message;
            MAIN.post(() -> completed.accept(result));
        });
    }

    private static JSONObject json(String url,CancellationSignal cancellation) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        transfer(url, bytes, 1024 * 1024,cancellation);
        return new JSONObject(bytes.toString("UTF-8"));
    }
    private static boolean fetch(Context c,CancellationSignal cancellation) throws Exception {
        String repo = repository(c), slug = repo.substring("https://github.com/".length());
        JSONObject release = json("https://api.github.com/repos/"+slug+"/releases/latest",cancellation);
        if(release.optBoolean("draft") || release.optBoolean("prerelease")) return false;
        JSONArray assets = release.getJSONArray("assets");
        Map<String,String> links = new HashMap<>();
        for(int i=0;i<assets.length();i++) {
            JSONObject asset=assets.getJSONObject(i);
            String name=asset.getString("name"), url=asset.getString("browser_download_url");
            if(!url.startsWith(repo+"/releases/download/")) throw new IOException("Unexpected asset origin");
            links.put(name,url);
        }
        String metadata=links.get("update.json");
        if(metadata==null) throw new IOException("Missing update.json");
        JSONObject info=json(metadata,cancellation);
        long version=info.getLong("versionCode");
        String name=info.getString("versionName"), apk=info.getString("apkName"), hash=info.getString("sha256");
        if(name.trim().isEmpty() || !UpdateRules.apkName(apk)
                || !links.containsKey(apk) || !UpdateRules.sha256(hash)) throw new IOException("Invalid update metadata");
        // The release carries the launcher's own parts beside the APK. Their download links are taken from the
        // asset list, not from the metadata text, so a wrong url in update.json cannot point somewhere else.
        JSONArray components = info.optJSONArray("components");
        StringBuilder parts = new StringBuilder();
        if (components != null) for (int i = 0; i < components.length(); i++) {
            JSONObject part = components.getJSONObject(i);
            String partName = part.optString("name", ""), partHash = part.optString("sha256", "");
            if (partName.equals(apk) || !UpdateRules.addOnName(partName) || !UpdateRules.sha256(partHash)
                    || !links.containsKey(partName)) continue;
            part.put("url", links.get(partName));
            parts.append(part.toString()).append('\n');
        }
        SharedPreferences.Editor edit=prefs(c).edit().putLong("last_check",System.currentTimeMillis())
                .putString(PARTS_KEY, parts.toString());
        if(version>installed(c)) {
            edit.putString("repository",repo).putString("version_name",name).putLong("version_code",version)
                    .putString("apk_url",links.get(apk)).putString("release_url",release.getString("html_url"))
                    .putString("apk_sha256",hash.toLowerCase(Locale.ROOT));
        } else edit.remove("version_name").remove("version_code").remove("apk_url").remove("release_url").remove("apk_sha256");
        edit.apply();
        return true;
    }
    /** Parts of the newest release that this TV does not have yet. */
    static List<Part> pendingParts(Context context) {
        Context c = context.getApplicationContext();
        String stored = prefs(c).getString(PARTS_KEY, "");
        if (stored.isEmpty()) return Collections.emptyList();
        PackageManager pm = c.getPackageManager();
        List<Part> result = new ArrayList<>();
        for (String line : stored.split("\n")) {
            if (line.isEmpty()) continue;
            try {
                JSONObject part = new JSONObject(line);
                String name = part.getString("name"), url = part.getString("url"), hash = part.getString("sha256");
                if (!UpdateRules.downloadUrl(url) || !UpdateRules.sha256(hash)) continue;
                hash = hash.toLowerCase(Locale.ROOT);
                if (name.endsWith(".apk")) {
                    String pkg = part.optString("package", "");
                    if (!pkg.matches("[A-Za-z0-9._]+")) continue;
                    long installed = 0;
                    try { installed = code(pm.getPackageInfo(pkg, 0)); }
                    catch (PackageManager.NameNotFoundException absent) { }
                    long published = part.optLong("versionCode", 0);
                    if (published > installed) result.add(new Part(name, url, hash, pkg, published, true));
                } else {
                    Part staged = new Part(name, url, hash, "", 0, false);
                    if (!hash.equals(prefs(c).getString(STAGED_KEY + staged.stageName(), ""))) result.add(staged);
                }
            } catch (JSONException unreadable) { /* one bad line does not stop the others */ }
        }
        return result;
    }

    /** Downloads one part, checks its checksum, and installs it: APKs through the system installer, the bridge
     *  DEX into the launcher folder the shell guard reads. Returns what the caller should tell the owner. */
    private static String updatePart(Context c, Part part) throws Exception {
        File dir = new File(c.getCacheDir(), "updates");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("No update directory");
        File file = new File(dir, part.name + "-" + part.sha256.substring(0, 12));
        if (!file.isFile() || !part.sha256.equalsIgnoreCase(sha256(file))) {
            if (dir.getUsableSpace() < MAX_APK + 8 * 1024 * 1024) throw new IOException("Not enough space");
            try (OutputStream out = new FileOutputStream(file)) { transfer(part.url, out, MAX_APK, null); }
        }
        if (!part.sha256.equalsIgnoreCase(sha256(file))) throw new IOException("Checksum mismatch");
        if (!part.apk) {
            File folder = c.getExternalFilesDir(null);
            if (folder == null) throw new IOException("No launcher folder");
            File targetDir = new File(folder, "bridge");
            if (!targetDir.isDirectory() && !targetDir.mkdirs()) throw new IOException("No bridge folder");
            for (int attempt = 0; ; attempt++) {
                try {
                    stage(file, targetDir, part);
                    break;
                } catch (FileNotFoundException denied) {
                    // A bridge folder left by the shell domain during an earlier setup belongs to adb, not to
                    // this app, so the launcher cannot create files inside it. An empty one is removed and
                    // recreated by the launcher itself; a folder with content is left alone.
                    if (attempt > 0) throw denied;
                    File[] left = targetDir.listFiles();
                    if (left == null || left.length > 0) throw denied;
                    if (!targetDir.delete() || !targetDir.mkdirs()) throw denied;
                }
            }
            prefs(c).edit().putString(STAGED_KEY + part.stageName(), part.sha256).apply();
            return part.label();
        }
        PackageInfo archive = c.getPackageManager().getPackageArchiveInfo(file.getAbsolutePath(), 0);
        if (archive == null || !archive.packageName.equals(part.packageName) || code(archive) != part.versionCode)
            throw new IOException("Wrong package or version");
        Uri uri = FileProvider.getUriForFile(c, c.getPackageName() + ".updates", file);
        c.startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK));
        return part.label();
    }

    /** Copies a verified part into the folder the shell guard reads, together with its checksum marker. */
    private static void stage(File source, File targetDir, Part part) throws IOException {
        File target = new File(targetDir, part.stageName());
        try (InputStream input = new FileInputStream(source); OutputStream out = new FileOutputStream(target)) {
            byte[] bytes = new byte[8192]; int count;
            while ((count = input.read(bytes)) != -1) out.write(bytes, 0, count);
        }
        try (OutputStream marker = new FileOutputStream(target.getAbsolutePath() + ".sha256")) {
            marker.write((part.sha256 + "\n").getBytes("UTF-8"));
        }
    }

    /** A short reason for the owner: the class name alone said nothing useful on the TV. */
    private static String describe(Exception failure) {
        if (failure instanceof FileNotFoundException) return "телевизор не разрешил записать файл в папку моста";
        if (failure instanceof IOException) { String message = failure.getMessage(); return message == null || message.isEmpty() ? "сеть недоступна" : message; }
        return failure.getClass().getSimpleName();
    }

    static void schedule(Context c) {
        JobScheduler jobs=c.getSystemService(JobScheduler.class);
        if(jobs==null) return;
        if(repository(c).isEmpty()) { jobs.cancel(JOB_ID); return; }
        if(jobs.getPendingJob(JOB_ID)!=null) return;
        jobs.schedule(new JobInfo.Builder(JOB_ID,new ComponentName(c,UpdateCheckJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(DAY).setPersisted(true).build());
    }
    static void install(Activity activity) {
        if(activity.isFinishing() || activity.isDestroyed()) return;
        String latest = availableVersion(activity);
        final List<Part> parts = pendingParts(activity);
        if(latest.isEmpty() && parts.isEmpty()) { toast(activity,"Нет доступного обновления"); return; }
        if(!activity.getPackageManager().canRequestPackageInstalls()) {
            try {
                activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+activity.getPackageName())));
                toast(activity,"Разрешите установку и снова нажмите обновление");
            } catch(RuntimeException missing) { toast(activity,"Настройки установки недоступны"); }
            return;
        }
        if(!INSTALLING.compareAndSet(false,true)) { toast(activity,"Обновление уже загружается"); return; }
        Context c=activity.getApplicationContext();
        SharedPreferences p=prefs(c);
        final String url=p.getString("apk_url",""), hash=p.getString("apk_sha256","");
        final long version=p.getLong("version_code",0);
        WeakReference<Activity> owner=new WeakReference<>(activity);
        toast(activity,"Загрузка обновления…");
        WORK.execute(() -> {
            File file=null;
            try {
                // The parts are small, so they are fetched first: the APK of the launcher is the largest download
                // and the installer dialog that follows can take the owner's attention for a while.
                List<String> done=new ArrayList<>();
                for (Part part : parts) {
                    try { done.add(updatePart(c,part)); }
                    catch (Exception failure) {
                        android.util.Log.w("AppUpdates","Part "+part.name+" unavailable: "+failure);
                        MAIN.post(() -> toast(c,"Компонент "+part.label()+" не обновлён: "+describe(failure)));
                    }
                }
                if (!done.isEmpty() && new BridgeTrigger().helperInstalled(c)) new BridgeTrigger().ask(c);
                if (latest.isEmpty()) {
                    MAIN.post(() -> {
                        INSTALLING.set(false);
                        Activity a=owner.get();
                        if(a!=null && !a.isDestroyed() && !a.isFinishing())
                            toast(a, done.isEmpty() ? "Обновлений нет" : "Обновлены компоненты: "+String.join(", ",done));
                    });
                    return;
                }
                File dir=new File(c.getCacheDir(),"updates");
                if(!dir.isDirectory() && !dir.mkdirs()) throw new IOException("No update directory");
                if(!UpdateRules.sha256(hash))throw new IOException("Invalid checksum");
                file=new File(dir,"TVHome-"+version+"-"+hash.substring(0,12)+".apk");
                File[] old=dir.listFiles();if(old!=null)for(File f:old)if(f.isFile()&&!f.equals(file)
                        && f.getName().matches("TVHome-[0-9]+.*\\.apk"))f.delete();
                if(!file.isFile()||!hash.equalsIgnoreCase(sha256(file))){
                    if(dir.getUsableSpace()<MAX_APK+8*1024*1024) throw new IOException("Not enough space");
                    try(OutputStream out=new FileOutputStream(file)) { transfer(url,out,MAX_APK,null); }
                }
                if(!UpdateRules.sha256(hash) || !hash.equalsIgnoreCase(sha256(file))) throw new IOException("Checksum mismatch");
                verify(c,file,version);
                File ready=file;
                final String partsText = done.isEmpty() ? "" : " (компоненты: "+String.join(", ",done)+")";
                MAIN.post(() -> {
                    INSTALLING.set(false);
                    Activity a=owner.get();
                    if(a==null || a.isDestroyed() || a.isFinishing() || !a.hasWindowFocus()) { toast(c,"Обновление загружено"+partsText+". Нажмите кнопку обновления в лаунчере");return; }
                    try {
                        Uri uri=FileProvider.getUriForFile(a,a.getPackageName()+".updates",ready);
                        a.startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
                    } catch(RuntimeException unavailable) { ready.delete(); toast(a,"Не удалось открыть установщик"); }
                });
            } catch(Exception failure) {
                if(file!=null) file.delete();
                MAIN.post(() -> { INSTALLING.set(false); Activity a=owner.get(); if(a!=null&&!a.isDestroyed()&&!a.isFinishing()) toast(a,"Обновление не загружено или не прошло проверку"); });
            }
        });
    }
    @SuppressWarnings("deprecation")
    private static void verify(Context c,File file,long expected) throws Exception {
        PackageManager pm=c.getPackageManager();
        int flags=Build.VERSION.SDK_INT>=28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo archive=pm.getPackageArchiveInfo(file.getAbsolutePath(),flags), current=pm.getPackageInfo(c.getPackageName(),flags);
        if(archive==null || !c.getPackageName().equals(archive.packageName) || code(archive)!=expected || code(archive)<=code(current))
            throw new IOException("Wrong package or version");
        Signature[] wanted=signatures(current), got=signatures(archive);
        if(wanted==null||got==null||wanted.length==0||wanted.length!=got.length
                || !new HashSet<>(Arrays.asList(wanted)).equals(new HashSet<>(Arrays.asList(got)))) throw new IOException("Certificate mismatch");
    }
    @SuppressWarnings("deprecation")
    private static Signature[] signatures(PackageInfo p) {
        return Build.VERSION.SDK_INT>=28 ? (p.signingInfo==null?null:p.signingInfo.getApkContentsSigners()) : p.signatures;
    }
    private static String sha256(File file) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream input=new FileInputStream(file)) { byte[] bytes=new byte[16384];int count;while((count=input.read(bytes))!=-1)digest.update(bytes,0,count); }
        StringBuilder result=new StringBuilder();for(byte b:digest.digest())result.append(String.format(Locale.ROOT,"%02x",b&255));return result.toString();
    }
    private static void transfer(String address,OutputStream output,long limit,CancellationSignal cancellation) throws IOException {
        URL url=new URL(address);long deadline=SystemClock.elapsedRealtime()+120000;
        for(int redirects=0;redirects<6;redirects++) {
            String host=url.getHost();
            if(!UpdateRules.downloadUrl(url.toExternalForm())) throw new IOException("Untrusted download URL");
            HttpURLConnection connection=(HttpURLConnection)url.openConnection();
            connection.setConnectTimeout(10000);connection.setReadTimeout(10000);connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent","TVHome-Updater");
            connection.setRequestProperty("Accept",host.equals("api.github.com")?"application/vnd.github+json":"application/octet-stream");
            if(cancellation!=null){cancellation.throwIfCanceled();cancellation.setOnCancelListener(connection::disconnect);}
            try {
                int status=connection.getResponseCode();
                if(status>=300&&status<400) { String target=connection.getHeaderField("Location");if(target==null)throw new IOException("Missing redirect");url=new URL(url,target);continue; }
                if(status!=200 || connection.getContentLengthLong()>limit) throw new IOException("HTTP or size error");
                try(InputStream input=connection.getInputStream()) {
                    byte[] bytes=new byte[16384];long total=0;int count;
                    while((count=input.read(bytes))!=-1) { if(cancellation!=null)cancellation.throwIfCanceled();total+=count;if(total>limit || SystemClock.elapsedRealtime()>deadline || Thread.currentThread().isInterrupted())throw new IOException("Download limit");output.write(bytes,0,count); }
                }
                return;
            } finally {if(cancellation!=null)cancellation.setOnCancelListener(null);connection.disconnect();}
        }
        throw new IOException("Too many redirects");
    }
    private static void toast(Context c,String message) { MAIN.post(()->Toast.makeText(c.getApplicationContext(),message,Toast.LENGTH_LONG).show()); }
    private AppUpdates() {}
}
