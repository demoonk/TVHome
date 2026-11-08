package ru.pda.tvhome;

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
    /** The address the installed app can open. Result pages are not always on the app-link host. */
    static String appLink(String id,String address){
        try{
            URI u=new URI(address);
            if(!content(id,address))return address;
            String host=u.getHost(),path=u.getPath();
            if("kinopoisk".equals(id)){
                // Кинопоиск registers app links only for hd.kinopoisk.ru/film; its own scheme covers series.
                if(path.startsWith("/film/"))host="hd.kinopoisk.ru";
                else return new URI("kpatv",null,"seasons",-1,"/"+path.substring("/series/".length()).replaceAll("/+$",""),null,null).toString();
            }
            // VK Video registers app links on vk.com, not on the vkvideo.ru result host.
            if("vk".equals(id)&&!host.equals("vk.com"))host="vk.com";
            return new URI("https",null,host,-1,path,u.getQuery(),null).toString();
        }catch(Exception invalid){return address;}
    }
    private SiteSearchRules(){}
}
