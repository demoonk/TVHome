package com.gathin.tvhome;

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
    private static final int JOB_ID = 0x545655;
    private static final long DAY = 24L * 60 * 60 * 1000, MAX_APK = 100L * 1024 * 1024;
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean CHECKING = new AtomicBoolean(), INSTALLING = new AtomicBoolean(), STARTED = new AtomicBoolean();
    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, 0); }
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
    private static JSONObject json(String url,CancellationSignal cancellation) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        transfer(url, bytes, 1024 * 1024,cancellation);
        return new JSONObject(bytes.toString("UTF-8"));
    }
    private static void fetch(Context c,CancellationSignal cancellation) throws Exception {
        String repo = repository(c), slug = repo.substring("https://github.com/".length());
        JSONObject release = json("https://api.github.com/repos/"+slug+"/releases/latest",cancellation);
        if(release.optBoolean("draft") || release.optBoolean("prerelease")) return;
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
        SharedPreferences.Editor edit=prefs(c).edit().putLong("last_check",System.currentTimeMillis());
        if(version>installed(c)) {
            edit.putString("repository",repo).putString("version_name",name).putLong("version_code",version)
                    .putString("apk_url",links.get(apk)).putString("release_url",release.getString("html_url"))
                    .putString("apk_sha256",hash.toLowerCase(Locale.ROOT));
        } else edit.remove("version_name").remove("version_code").remove("apk_url").remove("release_url").remove("apk_sha256");
        edit.apply();
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
        if(availableVersion(activity).isEmpty()) { toast(activity,"Нет доступного обновления"); return; }
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
                File dir=new File(c.getCacheDir(),"updates");
                if(!dir.isDirectory() && !dir.mkdirs()) throw new IOException("No update directory");
                if(!UpdateRules.sha256(hash))throw new IOException("Invalid checksum");
                file=new File(dir,"TVHome-"+version+"-"+hash.substring(0,12)+".apk");
                File[] old=dir.listFiles();if(old!=null)for(File f:old)if(f.isFile()&&!f.equals(file))f.delete();
                if(!file.isFile()||!hash.equalsIgnoreCase(sha256(file))){
                    if(dir.getUsableSpace()<MAX_APK+8*1024*1024) throw new IOException("Not enough space");
                    try(OutputStream out=new FileOutputStream(file)) { transfer(url,out,MAX_APK,null); }
                }
                if(!UpdateRules.sha256(hash) || !hash.equalsIgnoreCase(sha256(file))) throw new IOException("Checksum mismatch");
                verify(c,file,version);
                File ready=file;
                MAIN.post(() -> {
                    INSTALLING.set(false);
                    Activity a=owner.get();
                    if(a==null || a.isDestroyed() || a.isFinishing() || !a.hasWindowFocus()) { toast(c,"Обновление загружено. Нажмите кнопку обновления в лаунчере");return; }
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
