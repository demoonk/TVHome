package com.gathin.tvhome;

import java.net.URI;

/** Only actual content pages can become app-launch cards. */
final class SiteSearchRules {
    static boolean content(String id,String address){
        try{
            URI u=new URI(address);String h=u.getHost(),p=u.getPath();
            if(!"https".equals(u.getScheme())||u.getUserInfo()!=null||h==null||p==null)return false;
            if("vk".equals(id))return (h.equals("vkvideo.ru")||h.equals("vk.com")||h.equals("www.vk.com"))&&p.matches("/video-?[0-9]+_[0-9]+/?");
            if("rutube".equals(id))return (h.equals("rutube.ru")||h.equals("www.rutube.ru"))&&p.matches("/video/[a-fA-F0-9]{32}/?");
            if("kinopoisk".equals(id))return (h.equals("www.kinopoisk.ru")||h.equals("kinopoisk.ru"))&&p.matches("/(film|series)/[0-9]+/?");
            return false;
        }catch(Exception invalid){return false;}
    }
    private SiteSearchRules(){}
}
