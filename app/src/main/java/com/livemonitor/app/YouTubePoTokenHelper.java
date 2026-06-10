package com.livemonitor.app;

import android.net.Uri;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.Locale;

/**
 * Shared utilities for GVS PO token extraction.
 * Used by both YouTubeSignInActivity (visible WebView) and
 * PoTokenRefreshWorker (headless background WebView).
 */
public final class YouTubePoTokenHelper {

    private YouTubePoTokenHelper() {}

    /**
     * JavaScript injected into a YouTube watch page to extract the GVS PO token.
     * Walks ytcfg, ytInitialPlayerResponse, and various player globals.
     * Returns a JSON string: {token, tokenType, source, clientName, clientVersion,
     * visitorData, videoId, playerUrl}.
     */
    public static final String PO_TOKEN_SCRIPT = "(function(){"
        + "function readCfg(name){try{return window.ytcfg&&window.ytcfg.get?window.ytcfg.get(name):null;}catch(e){return null;}}"
        + "function isTokenKey(key){return /po[_-]?token|potoken/i.test(String(key||''));}"
        + "function validToken(value){return typeof value==='string'&&value.length>10&&value.indexOf('TOKEN')<0&&value.indexOf('...')<0;}"
        + "var found={token:'',source:''};"
        + "function remember(token,source){if(!found.token&&validToken(token)){found.token=token;found.source=source;}}"
        + "function readPotFromUrl(value,path){if(found.token||typeof value!=='string'||value.indexOf('pot=')<0){return;}"
        + "try{remember(new URL(value,location.href).searchParams.get('pot'),path+'.url.pot');}catch(e){"
        + "var match=value.match(/[?&]pot=([^&#]+)/);if(match){remember(decodeURIComponent(match[1].replace(/\\+/g,'%20')),path+'.url.pot');}}}"
        + "function walk(value,path,depth){"
        + "if(found.token||!value||depth>8){return;}"
        + "readPotFromUrl(value,path);if(found.token){return;}"
        + "if(Array.isArray(value)){for(var i=0;i<value.length&&!found.token;i++){walk(value[i],path+'['+i+']',depth+1);}return;}"
        + "if(typeof value==='object'){var keys=Object.keys(value);for(var k=0;k<keys.length&&!found.token;k++){"
        + "var key=keys[k];var next=value[key];var nextPath=path+'.'+key;"
        + "if(isTokenKey(key)&&validToken(next)){remember(next,nextPath);return;}"
        + "walk(next,nextPath,depth+1);}}}"
        + "try{walk(readCfg('WEB_PLAYER_CONTEXT_CONFIGS'),'ytcfg.WEB_PLAYER_CONTEXT_CONFIGS',0);}catch(e){}"
        + "try{walk(window.ytInitialPlayerResponse,'ytInitialPlayerResponse',0);}catch(e){}"
        + "try{walk(readCfg('PLAYER_VARS'),'ytcfg.PLAYER_VARS',0);}catch(e){}"
        + "try{walk(readCfg('PLAYER_CONFIG'),'ytcfg.PLAYER_CONFIG',0);}catch(e){}"
        + "try{walk(window.ytplayer&&window.ytplayer.config,'ytplayer.config',0);}catch(e){}"
        + "try{walk(window.yt&&window.yt.config_,'yt.config_',0);}catch(e){}"
        + "try{walk(window._yt_player,'_yt_player',0);}catch(e){}"
        + "try{walk(document.documentElement.innerHTML,'document.html',0);}catch(e){}"
        + "var clientName=readCfg('INNERTUBE_CONTEXT_CLIENT_NAME')||readCfg('INNERTUBE_CLIENT_NAME')||'MWEB';"
        + "var clientVersion=readCfg('INNERTUBE_CONTEXT_CLIENT_VERSION')||readCfg('INNERTUBE_CLIENT_VERSION')||'';"
        + "var visitorData=readCfg('VISITOR_DATA')||'';"
        + "var videoId=(window.ytInitialPlayerResponse&&window.ytInitialPlayerResponse.videoDetails&&window.ytInitialPlayerResponse.videoDetails.videoId)||'';"
        + "if(!videoId){try{videoId=new URL(location.href).searchParams.get('v')||'';}catch(e){}}"
        + "return JSON.stringify({token:found.token,tokenType:'gvs',source:found.source,clientName:clientName,clientVersion:clientVersion,visitorData:visitorData,videoId:videoId,playerUrl:location.href});"
        + "})()";

    /**
     * Injected into a YouTube page as early as possible (onPageStarted) so that
     * it is installed before YouTube's player JavaScript executes.
     *
     * <p>YouTube's po_token is no longer reliably stored in page globals such as
     * {@code ytcfg} or {@code ytInitialPlayerResponse}.  Instead it is generated
     * by BotGuard and sent in the body of the {@code /youtubei/v1/player} API
     * request.  This script overrides {@code window.fetch} and
     * {@code XMLHttpRequest} to intercept that request and read the token directly
     * out of the JSON body before it is sent to YouTube's servers.
     *
     * <p>The intercepted token is forwarded to the Android app via the
     * {@code LiveMonitorApp} JavascriptInterface (method
     * {@code onPoTokenIntercepted(token, clientName, videoId, source)}).
     *
     * <p>The guard {@code window.__lm_pot_interceptor} prevents the script from
     * installing itself more than once if injected on both {@code onPageStarted}
     * and {@code onPageFinished}.
     */
    public static final String FETCH_INTERCEPTOR_SCRIPT = "(function(){"
        + "if(window.__lm_pot_interceptor)return;"
        + "window.__lm_pot_interceptor=true;"
        + "var __lm_reqCount=0;"
        /*
         * Match any InnerTube API call so we can log what YouTube is actually
         * calling — not just /player but also /next, /browse, etc.
         */
        + "function isInnerTube(u){return typeof u==='string'&&u.indexOf('/youtubei/v1/')>=0;}"
        + "function isPlayerReq(u){return typeof u==='string'&&u.indexOf('/youtubei/v1/player')>=0;}"
        + "function getStr(b,k){try{return(b&&b[k])||'';}catch(e){return '';}}"
        + "function getClient(b){try{return(b&&b.context&&b.context.client&&b.context.client.clientName)||'';}catch(e){return '';}}"
        + "function tryNotify(token,client,videoId,src){"
        + "if(!token||typeof token!=='string'||token.length<16||token.indexOf(' ')>=0)return;"
        + "try{LiveMonitorApp.onPoTokenIntercepted(token,client||'',videoId||'',src||'');}catch(e){}}"
        /*
         * Scan a JSON body (request or response) for a po_token.
         * Also scans streaming URL strings for the ?pot= query parameter
         * which appears in YouTube's adaptive format URLs in the player response.
         */
        + "function scanForPot(obj,src,depth){"
        + "if(!obj||depth>6)return;"
        + "if(typeof obj==='string'){"
        + "if(obj.indexOf('pot=')>=0){var m=obj.match(/[?&]pot=([^&#]+)/);if(m){tryNotify(decodeURIComponent(m[1]),'' ,'',src+'.url.pot');}}"
        + "return;}"
        + "if(Array.isArray(obj)){for(var i=0;i<obj.length;i++)scanForPot(obj[i],src,depth+1);return;}"
        + "if(typeof obj==='object'){"
        + "var keys=Object.keys(obj);"
        + "for(var k=0;k<keys.length;k++){"
        + "var key=keys[k],val=obj[key];"
        + "if(/po[_-]?token|potoken/i.test(key)&&typeof val==='string'&&val.length>10){tryNotify(val,'' ,'',src+'.key.'+key);}"
        + "else scanForPot(val,src,depth+1);}}}"
        + "function checkBody(body,src,vid,client){"
        + "try{"
        + "var b=typeof body==='string'?JSON.parse(body):body;"
        + "if(!b)return;"
        + "var sid=b.serviceIntegrityDimensions;"
        + "var hasSid=!!(sid&&sid.poToken);"
        + "try{LiveMonitorApp.onApiRequestSeen('player',vid||getStr(b,'videoId'),hasSid?'has-token':'no-token',src);}catch(e){}"
        + "if(hasSid){tryNotify(sid.poToken,client||getClient(b),vid||getStr(b,'videoId'),src+'.sid');return;}"
        + "if(b.poToken){tryNotify(b.poToken,client||getClient(b),vid||getStr(b,'videoId'),src+'.body');return;}"
        + "scanForPot(b,src,0);"
        + "}catch(e){"
        + "try{LiveMonitorApp.onApiRequestSeen('player.parseerr','','parse-error:'+String(e),src);}catch(e2){}}}"
        /*
         * Intercept fetch — check request body AND response body.
         * The response body contains streaming format URLs with pot= parameter.
         */
        + "var _f=window.fetch;"
        + "window.fetch=function(input,init){"
        + "var u=typeof input==='string'?input:(input&&input.url)||'';"
        + "var rn=++__lm_reqCount;"
        + "var pr=_f.apply(this,arguments);"
        + "if(isPlayerReq(u)){"
        + "var vid='';try{vid=JSON.parse((init&&init.body)||'{}').videoId||'';}catch(e){}"
        + "if(init&&init.body){checkBody(init.body,'fetch.req',vid,'');}"
        + "pr.then(function(resp){"
        + "try{resp.clone().text().then(function(txt){checkBody(txt,'fetch.resp',vid,'');});}catch(e){}});"
        + "}else if(isInnerTube(u)){"
        + "try{var path=u.split('/youtubei/v1/')[1].split('?')[0]||'?';"
        + "LiveMonitorApp.onApiRequestSeen(path,'','intercepted',u.indexOf('fetch')>=0?'fetch':'xhr');}catch(e){}}"
        + "return pr;};"
        /*
         * Intercept XHR — check request body AND response body.
         */
        + "var _o=XMLHttpRequest.prototype.open,_s=XMLHttpRequest.prototype.send;"
        + "XMLHttpRequest.prototype.open=function(m,u){this.__lmu=u;return _o.apply(this,arguments);};"
        + "XMLHttpRequest.prototype.send=function(body){"
        + "var self=this;"
        + "if(isPlayerReq(this.__lmu)){"
        + "var vid='';try{vid=JSON.parse(body||'{}').videoId||'';}catch(e){}"
        + "if(body){checkBody(body,'xhr.req',vid,'');}"
        + "var origOnReady=this.onreadystatechange;"
        + "this.onreadystatechange=function(){"
        + "if(self.readyState===4&&self.responseText){"
        + "try{checkBody(self.responseText,'xhr.resp',vid,'');}catch(e){}}"
        + "if(origOnReady)origOnReady.apply(self,arguments);};"
        + "}else if(isInnerTube(this.__lmu)){"
        + "try{var p2=this.__lmu.split('/youtubei/v1/')[1].split('?')[0]||'?';"
        + "LiveMonitorApp.onApiRequestSeen(p2,'','intercepted','xhr');}catch(e){}}"
        + "return _s.apply(this,arguments);};"
        + "try{LiveMonitorApp.onApiRequestSeen('interceptor-installed','','ok','init');}catch(e){}"
        + "})()";

    /**
     * Returns true if the URL looks like a YouTube watch page that will have
     * player context loaded (i.e. contains a video ID parameter or path segment).
     */
    public static boolean looksLikePlayerUrl(String url) {
        return !isBlank(extractVideoId(url));
    }

    /**
     * Parses the raw JavaScript result string, extracts the PO token, and saves
     * it to AppSettings via the provided AppStorage.
     *
     * @param jsResult   raw value from WebView.evaluateJavascript (may be JSON-wrapped string)
     * @param storage    AppStorage for loading and persisting settings
     * @param pageUrl    the URL of the page where the script ran (used as fallback videoId source)
     * @return true if a valid token was found and saved
     */
    public static boolean parseAndSaveToken(String jsResult, AppStorage storage, String pageUrl) {
        if (isBlank(jsResult) || "null".equals(jsResult.trim())) {
            return false;
        }

        try {
            Object unwrapped = new JSONTokener(jsResult).nextValue();
            String jsonText = unwrapped instanceof String ? (String) unwrapped : String.valueOf(unwrapped);
            JSONObject json = new JSONObject(jsonText);
            String token = json.optString("token", "").trim();

            if (isBlank(token)) {
                return false;
            }

            String client = normalizeClientForYtDlp(json.optString("clientName", "mweb"));
            String tokenType = json.optString("tokenType", "gvs");
            String videoId = json.optString("videoId", extractVideoId(pageUrl));
            String playerUrl = json.optString("playerUrl", pageUrl == null ? "" : pageUrl);
            String source = "background-webview:" + json.optString("source", "auto-refresh");

            AppSettings appSettings = storage.loadSettings();
            appSettings.setYtDlpPoTokenClient(client);
            appSettings.setYtDlpPoTokenValue(token);
            appSettings.setYtDlpPoTokenMetadata(
                tokenType,
                System.currentTimeMillis(),
                source,
                "",
                videoId,
                playerUrl
            );
            storage.saveSettings(appSettings);
            storage.appendLog(LogItem.info(
                LogItem.SOURCE_REMOTE_CONFIG,
                "Auto-refreshed GVS PO token from background WebView. client="
                    + client + ", type=" + tokenType + ", videoId=" + videoId
            ));
            return true;

        } catch (Exception e) {
            return false;
        }
    }

    public static String extractVideoId(String url) {
        if (isBlank(url)) {
            return "";
        }

        try {
            Uri uri = Uri.parse(url.trim());
            String queryVideoId = uri.getQueryParameter("v");

            if (!isBlank(queryVideoId)) {
                return queryVideoId.trim();
            }

            String lastPathSegment = uri.getLastPathSegment();

            if (!isBlank(lastPathSegment) && lastPathSegment.matches("^[A-Za-z0-9_-]{11}$")) {
                return lastPathSegment;
            }
        } catch (RuntimeException ignored) {
        }

        return "";
    }

    private static String normalizeClientForYtDlp(String clientName) {
        String normalized = clientName == null ? "" : clientName.trim().toLowerCase(Locale.US);

        if (normalized.contains("mweb")) return "mweb";
        if (normalized.contains("web"))  return "web";
        if (normalized.contains("android")) return "android";
        if (normalized.contains("ios")) return "ios";

        return "mweb";
    }

    public static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
