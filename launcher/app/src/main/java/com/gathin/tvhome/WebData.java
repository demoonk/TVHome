package com.gathin.tvhome;

import android.os.CancellationSignal;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

final class WebData {
    static String get(String address, CancellationSignal signal) throws IOException {
        URL url=new URL(address);
        for(int redirect=0;redirect<6;redirect++){
            if(!"https".equals(url.getProtocol())||url.getUserInfo()!=null)throw new IOException("HTTPS required");
            HttpURLConnection c=(HttpURLConnection)url.openConnection();
            c.setConnectTimeout(5000);c.setReadTimeout(6000);c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent","Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36");
            if(signal!=null){signal.throwIfCanceled();signal.setOnCancelListener(c::disconnect);}
            try{
                int status=c.getResponseCode();
                if(status>=300&&status<400){String location=c.getHeaderField("Location");if(location==null)throw new IOException("Missing redirect");url=new URL(url,location);continue;}
                if(status!=200)throw new IOException("HTTP "+status);
                try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                    byte[] b=new byte[8192];int n;
                    while((n=in.read(b))!=-1){if(signal!=null)signal.throwIfCanceled();if(out.size()+n>4*1024*1024)throw new IOException("Response too large");out.write(b,0,n);}
                    return out.toString(StandardCharsets.UTF_8.name());
                }
            }finally{if(signal!=null)signal.setOnCancelListener(null);c.disconnect();}
        }
        throw new IOException("Too many redirects");
    }
    private WebData(){}
}
