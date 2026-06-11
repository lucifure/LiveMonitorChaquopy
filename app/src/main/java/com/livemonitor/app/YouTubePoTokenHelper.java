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
        + "function decodePart(v){try{return decodeURIComponent(String(v||'').replace(/\\+/g,'%20'));}catch(e){return String(v||'');}}"
        + "function readPotFromUrl(value,path){if(found.token||typeof value!=='string')return;"
        + "var queue=[value],seen={};for(var qi=0;qi<queue.length&&!found.token;qi++){var text=queue[qi];if(!text||seen[text])continue;seen[text]=true;"
        + "var decoded=decodePart(text);if(decoded&&decoded!==text)queue.push(decoded);"
        + "try{var params=new URLSearchParams(text.charAt(0)==='?'?text.substring(1):text);remember(params.get('pot')||params.get('po_token'),path+'.params.pot');"
        + "var nested=params.get('url')||params.get('u');if(nested)queue.push(nested);}catch(e){}"
        + "try{var url=new URL(text,location.href);remember(url.searchParams.get('pot')||url.searchParams.get('po_token'),path+'.url.pot');"
        + "var nestedUrl=url.searchParams.get('url')||url.searchParams.get('u');if(nestedUrl)queue.push(nestedUrl);}catch(e2){}"
        + "var match=text.match(/[?&](?:pot|po_token)=([^&#]+)/i);if(match){remember(decodePart(match[1]),path+'.regex.pot');}}}"
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
     * by BotGuard and sent in InnerTube API request bodies. YouTube may issue
     * these calls via {@code fetch(Request)} rather than {@code fetch(url, init)},
     * and the first observable token is not always on the player request itself.
     * This script overrides {@code window.fetch} and {@code XMLHttpRequest} to
     * inspect InnerTube request bodies and player responses before they leave the
     * page context.
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
        + "var __lm_diagSeen={};"
        /*
         * Match InnerTube API calls so we can inspect token-bearing bodies while
         * logging only PO-token capture successes/failures.
         */
        + "function isInnerTube(u){return typeof u==='string'&&u.indexOf('/youtubei/v1/')>=0;}"
        + "function isPlayerReq(u){return typeof u==='string'&&u.indexOf('/youtubei/v1/player')>=0&&u.indexOf('/player/heartbeat')<0;}"
        + "function getStr(b,k){try{return(b&&b[k])||'';}catch(e){return '';}}"
        + "function getClient(b){try{return(b&&b.context&&b.context.client&&b.context.client.clientName)||'';}catch(e){return '';}}"
        + "function diag(api,vid,status,src){try{var key=[api||'',vid||'',status||'',src||''].join('|');if(__lm_diagSeen[key])return;__lm_diagSeen[key]=true;LiveMonitorApp.onApiRequestSeen(api||'po-token',vid||'',status||'',src||'');}catch(e){}}"
        + "function tryNotify(token,client,videoId,src){"
        + "if(!token||typeof token!=='string'||token.length<16||token.indexOf(' ')>=0)return false;"
        + "try{LiveMonitorApp.onPoTokenIntercepted(token,client||'',videoId||'',src||'');return true;}catch(e){diag('bridge',videoId||'','notify-failed:'+String(e).substring(0,80),src);return false;}}"
        + "function decodePart(v){try{return decodeURIComponent(String(v||'').replace(/\\+/g,'%20'));}catch(e){return String(v||'');}}"
        + "function scanUrlForPot(value,src){if(typeof value!=='string')return false;var q=[value],seen={};for(var i=0;i<q.length;i++){var text=q[i];if(!text||seen[text])continue;seen[text]=true;"
        + "var decoded=decodePart(text);if(decoded&&decoded!==text)q.push(decoded);"
        + "try{var params=new URLSearchParams(text.charAt(0)==='?'?text.substring(1):text);var pot=params.get('pot')||params.get('po_token');if(pot&&tryNotify(pot,'' ,'',src+'.params.pot'))return true;var nested=params.get('url')||params.get('u');if(nested)q.push(nested);}catch(e){}"
        + "try{var url=new URL(text,location.href);var urlPot=url.searchParams.get('pot')||url.searchParams.get('po_token');if(urlPot&&tryNotify(urlPot,'' ,'',src+'.url.pot'))return true;var nestedUrl=url.searchParams.get('url')||url.searchParams.get('u');if(nestedUrl)q.push(nestedUrl);}catch(e2){}"
        + "var m=text.match(/[?&](?:pot|po_token)=([^&#]+)/i);if(m&&tryNotify(decodePart(m[1]),'' ,'',src+'.regex.pot'))return true;}return false;}"
        /*
         * Scan a JSON body (request or response) for a po_token.
         * Also scans streaming URL strings for the ?pot= query parameter
         * which appears in YouTube's adaptive format URLs in the player response.
         */
        + "function scanForPot(obj,src,depth,client,vid){"
        + "if(!obj||depth>8)return false;"
        + "if(typeof obj==='string'){return scanUrlForPot(obj,src); }"
        + "if(Array.isArray(obj)){for(var i=0;i<obj.length;i++){if(scanForPot(obj[i],src+'['+i+']',depth+1,client,vid))return true;}return false;}"
        + "if(typeof obj==='object'){"
        + "var sid=obj.serviceIntegrityDimensions;"
        + "if(sid&&tryNotify(sid.poToken,client||getClient(obj),vid||getStr(obj,'videoId'),src+'.serviceIntegrityDimensions.poToken'))return true;"
        + "var keys=Object.keys(obj);"
        + "for(var k=0;k<keys.length;k++){"
        + "var key=keys[k],val=obj[key];"
        + "if(/po[_-]?token|potoken/i.test(key)&&typeof val==='string'&&val.length>10){if(tryNotify(val,client||getClient(obj),vid||getStr(obj,'videoId'),src+'.key.'+key))return true;}"
        + "else if(scanForPot(val,src+'.'+key,depth+1,client,vid))return true;}}return false;}"
        + "function looksJsonText(text){text=String(text||'').replace(/^\\s+/, '');return text.charAt(0)==='{'||text.charAt(0)==='[';}"
        + "function isBinaryText(text){text=String(text||'');if(!text)return false;var c=text.charCodeAt(0);return c===31||c===0||c===65533;}"
        + "function checkBody(body,src,api,vid,client){"
        + "try{"
        + "if(typeof body==='string'&&!looksJsonText(body)){if(scanUrlForPot(body,src+'.text')){diag(api||'player',vid||'','has-token',src);return true;}if(api==='player'&&isBinaryText(body))diag(api,vid||'','body-not-json:compressed-or-binary',src);return false;}"
        + "var b=typeof body==='string'?JSON.parse(body):body;"
        + "if(!b)return false;"
        + "var bodyVid=vid||getStr(b,'videoId');var bodyClient=client||getClient(b);"
        + "var sid=b.serviceIntegrityDimensions;"
        + "var hasSid=!!(sid&&sid.poToken);"
        + "if(hasSid&&tryNotify(sid.poToken,bodyClient,bodyVid,src+'.sid')){diag(api||'player',bodyVid,'has-token',src);return true;}"
        + "if(b.poToken&&tryNotify(b.poToken,bodyClient,bodyVid,src+'.body')){diag(api||'player',bodyVid,'has-token',src);return true;}"
        + "var found=scanForPot(b,src,0,bodyClient,bodyVid);"
        + "if(found||api==='player')diag(api||'player',bodyVid,found?'has-token':'no-token',src);"
        + "return found;"
        + "}catch(e){"
        + "if(api==='player')diag(api,vid||'','parse-error:'+String(e).substring(0,120),src);return false;}}"
        /*
         * Intercept fetch — check request body AND response body.
         * The response body contains streaming format URLs with pot= parameter.
         */
        + "function readBodyInfo(body){var info={vid:'',client:''};try{var parsed=typeof body==='string'?JSON.parse(body):body;info.vid=(parsed&&parsed.videoId)||'';info.client=getClient(parsed);}catch(e){}return info;}"
        + "function inspectFetchReq(input,init,api){"
        + "var body=init&&init.body;if(body){var info=readBodyInfo(body);checkBody(body,'fetch.req.'+api,api,info.vid,info.client);return;}"
        + "try{if(input&&typeof input.clone==='function'&&typeof input.clone().text==='function'){input.clone().text().then(function(txt){var info=readBodyInfo(txt);checkBody(txt,'fetch.req.'+api,api,info.vid,info.client);});}}catch(e){}"
        + "}"
        + "var _f=window.fetch;"
        + "window.fetch=function(input,init){"
        + "var u=typeof input==='string'?input:(input&&input.url)||'';"
        + "var api='';try{api=isInnerTube(u)?(u.split('/youtubei/v1/')[1].split('?')[0]||'?'):'';}catch(e){}"
        + "var reqInfo=readBodyInfo(init&&init.body);"
        + "if(api){inspectFetchReq(input,init,api);}"
        + "var pr=_f.apply(this,arguments);"
        + "if(api){"
        + "if(isPlayerReq(u)){pr.then(function(resp){try{resp.clone().text().then(function(txt){checkBody(txt,'fetch.resp.'+api,api,reqInfo.vid,reqInfo.client);});}catch(e4){}});}"
        + "}"
        + "return pr;};"
        /*
         * Intercept XHR — check request body AND response body.
         */
        + "var _o=XMLHttpRequest.prototype.open,_s=XMLHttpRequest.prototype.send;"
        + "XMLHttpRequest.prototype.open=function(m,u){this.__lmu=u;return _o.apply(this,arguments);};"
        + "XMLHttpRequest.prototype.send=function(body){"
        + "var self=this;var api='';try{api=isInnerTube(this.__lmu)?(this.__lmu.split('/youtubei/v1/')[1].split('?')[0]||'?'):'';}catch(e){}"
        + "var vid='';var client='';try{var parsed=JSON.parse(body||'{}');vid=(parsed&&parsed.videoId)||'';client=getClient(parsed);}catch(e2){}"
        + "if(api){"
        + "if(body){checkBody(body,'xhr.req.'+api,api,vid,client);}"
        + "if(isPlayerReq(this.__lmu)){var origOnReady=this.onreadystatechange;"
        + "this.onreadystatechange=function(){"
        + "if(self.readyState===4&&self.responseText){"
        + "try{checkBody(self.responseText,'xhr.resp.'+api,api,vid,client);}catch(e4){}}"
        + "if(origOnReady)origOnReady.apply(self,arguments);};}"
        + "}"
        + "return _s.apply(this,arguments);};"
        + "diag('capture','','interceptor-installed','init');"
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
