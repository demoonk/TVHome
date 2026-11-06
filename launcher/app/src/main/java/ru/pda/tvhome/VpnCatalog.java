package ru.pda.tvhome;
import android.content.*;
import android.content.pm.*;
import java.util.*;
final class VpnCatalog {
 static List<String> installed(Context c){
  Set<String> found=new TreeSet<>();PackageManager pm=c.getPackageManager();
  for(ResolveInfo r:pm.queryIntentServices(new Intent("android.net.VpnService"),0))if(r.serviceInfo!=null&&r.serviceInfo.applicationInfo.enabled)found.add(r.serviceInfo.packageName);
  for(PackageInfo p:pm.getInstalledPackages(PackageManager.GET_SERVICES))if(p.applicationInfo.enabled&&p.services!=null)for(ServiceInfo service:p.services)if("android.permission.BIND_VPN_SERVICE".equals(service.permission))found.add(p.packageName);
  List<String> result=new ArrayList<>(found);result.sort((a,b)->label(c,a).compareToIgnoreCase(label(c,b)));return result;
 }
 static String label(Context c,String pkg){try{return c.getPackageManager().getApplicationLabel(c.getPackageManager().getApplicationInfo(pkg,0)).toString();}catch(Exception e){return pkg;}}
 static int icon(Context c,String pkg){String key=(pkg+label(c,pkg)).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]","");String slug="shield";
 if(key.contains("wireguardautotunnel"))slug="wgtunnel";
 if(key.contains("byedpi"))slug="byedpi";
 if(key.contains("protonvpn"))slug="protonvpn";
 if(key.contains("nordvpn"))slug="nordvpn";
 if(key.contains("surfshark"))slug="surfshark";
 if(key.contains("expressvpn"))slug="expressvpn";
 if(key.contains("mullvadvpn"))slug="mullvadvpn";
 if(key.contains("privateinternetaccess"))slug="privateinternetaccess";
 if(key.contains("wireguard"))slug="wireguard";
 if(key.contains("openvpn"))slug="openvpn";
 if(key.contains("tailscale"))slug="tailscale";
 if(key.contains("zerotier"))slug="zerotier";
 if(key.contains("cloudflare"))slug="cloudflarewarp";
 if(key.contains("adguardvpn"))slug="adguardvpn";
 if(key.contains("orbot"))slug="tororbot";
 if(key.contains("hiddify"))slug="hiddify";
 if(key.contains("amneziavpn"))slug="amneziavpn";
 if(key.contains("windscribe"))slug="windscribe";
 if(key.contains("ivpn"))slug="ivpn";
 if(key.contains("singbox"))slug="singbox";
 if(key.contains("wireguardautotunnel"))slug="wgtunnel";
 int id=c.getResources().getIdentifier("vpn_catalog_"+slug,"drawable",c.getPackageName());return id==0?R.drawable.vpn_catalog_shield:id;
 }
}
