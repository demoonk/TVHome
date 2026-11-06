package ru.pda.tvhome;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The text after the wake word is a message, never another local TV command. */
final class VoiceAliceCommand {
    private static final Pattern COMMAND=Pattern.compile("^\\s*алиса(?:[\\s,.:;!?—–-]+(.*))?\\s*$",Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE|Pattern.DOTALL);
    static String parse(String spoken){
        if(spoken==null)return null;
        Matcher match=COMMAND.matcher(spoken);
        return match.matches()?(match.group(1)==null?"":match.group(1).trim()):null;
    }
    static String url(String query){
        if(query==null||query.isEmpty())return "https://ya.ru/alice";
        StringBuilder escaped=new StringBuilder();
        for(int i=0;i<query.length();i++){
            char c=query.charAt(i);
            if(c=='"'||c=='\\')escaped.append('\\').append(c);
            else if(c<32){String hex=Integer.toHexString(c);escaped.append("\\u");for(int n=hex.length();n<4;n++)escaped.append('0');escaped.append(hex);}
            else escaped.append(c);
        }
        String json="{\"text\":\""+escaped+"\"}";
        try{return "https://ya.ru/alice?alice_deeplink="+URLEncoder.encode(json,StandardCharsets.UTF_8.name());}
        catch(java.io.UnsupportedEncodingException impossible){throw new AssertionError(impossible);}
    }
    private VoiceAliceCommand(){}
}
