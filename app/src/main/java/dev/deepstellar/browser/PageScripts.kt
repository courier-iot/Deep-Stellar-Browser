package dev.deepstellar.browser

import org.json.JSONArray
import kotlin.random.Random

/**
 * Small scripts the browser runs inside pages for privacy:
 *  - WebRTC IP protection  (before the page's own scripts)
 *  - fingerprint resistance (before the page's own scripts)
 *  - referrer trimming      (best effort)
 *  - cookie-banner handling (after the page has loaded)
 *
 * WebView has no native switches for these, so they work by wrapping the page's
 * browser APIs. A determined site can detect that — they raise the cost of
 * tracking, they don't make you invisible.
 */
object PageScripts {

    /** Random per app start; fingerprint noise is stable within a session and different across sessions. */
    private val sessionSeed = Random.nextInt(1, 1_000_000)

    /** Property name pages can't guess; the app sets it to let one page use WebRTC directly ("Allow once"). */
    val rtcFlag: String = "__ds" + java.lang.Long.toString(Random.nextLong(Long.MAX_VALUE), 36)

    val webRtcMode: String get() = Prefs.raw.getString("webrtc_mode", "relay") ?: "relay"
    val fingerprint: Boolean get() = Prefs.raw.getBoolean("fp_resist", true)
    val hideReferrer: Boolean get() = Prefs.raw.getBoolean("referrer_trim", false)
    val cookieBanners: Boolean get() = Prefs.raw.getBoolean("cookie_banners", true)

    /** Everything that must run before the page's scripts, as one script (or null if all are off). */
    fun documentStart(): String? {
        val parts = listOfNotNull(
            webRtcScript(),
            if (fingerprint) FINGERPRINT.replace("__SEED__", sessionSeed.toString()) else null,
            if (hideReferrer) REFERRER else null,
        )
        return if (parts.isEmpty()) null else parts.joinToString("\n")
    }

    /** What changes the document-start script; used to know when to re-install it. */
    fun signature(): String = webRtcMode + fingerprint + hideReferrer + SitePrefs.webRtcAllowedSites().joinToString(",")

    // ------------------------------------------------------------ WebRTC

    private fun webRtcScript(): String? = when (webRtcMode) {
        "allow" -> null
        "off" ->
            "(function(){try{['RTCPeerConnection','webkitRTCPeerConnection','RTCDataChannel'].forEach(function(k){" +
            "try{Object.defineProperty(window,k,{value:undefined,configurable:false,writable:false});}catch(e){window[k]=undefined;}});}catch(e){}})();"
        else -> RTC_RELAY
            .replace("__ALLOWED__", JSONArray(SitePrefs.webRtcAllowedSites()).toString())
            .replace("__FLAG__", rtcFlag)
    }

    /** Lets the current page make direct connections (after you allowed camera / microphone once). */
    fun allowRtcOnce(): String = "window['$rtcFlag']=true;"

    /**
     * Forces "relay only" connections unless the site is allowed: a page can then only connect
     * through a relay server it provides, so it never learns your local or public IP address.
     */
    private const val RTC_RELAY = """
(function(){try{
  var O=window.RTCPeerConnection||window.webkitRTCPeerConnection; if(!O||O.__ds) return;
  var ALLOWED=__ALLOWED__, FLAG='__FLAG__';
  function ok(){
    try{ if(window[FLAG]===true) return true; var h=location.hostname;
      for(var i=0;i<ALLOWED.length;i++){ if(h===ALLOWED[i]||h.slice(-ALLOWED[i].length-1)==='.'+ALLOWED[i]) return true; } }catch(e){}
    return false;
  }
  function relay(c){ c=Object.assign({},c||{}); c.iceTransportPolicy='relay'; return c; }
  function W(config,constraints){ return new O(ok()?config:relay(config),constraints); }
  W.prototype=O.prototype;
  try{ Object.setPrototypeOf(W,O); }catch(e){}
  W.__ds=1;
  var sc=O.prototype.setConfiguration;
  if(sc) O.prototype.setConfiguration=function(c){ return sc.call(this, ok()?c:relay(c)); };
  window.RTCPeerConnection=W;
  if(window.webkitRTCPeerConnection) window.webkitRTCPeerConnection=W;
}catch(e){}})();
"""

    // ------------------------------------------------------------ fingerprinting

    private const val FINGERPRINT = """
(function(){try{
  if(window.__dsFp) return; Object.defineProperty(window,'__dsFp',{value:1});
  var SEED=__SEED__;
  function rnd(i){ var x=Math.sin(SEED*0.0001+i*12.9898)*43758.5453; return x-Math.floor(x); }

  // Canvas: flip the lowest bit of a few pixels, so a canvas "fingerprint" differs every session.
  try{
    var C=HTMLCanvasElement.prototype, X=CanvasRenderingContext2D.prototype;
    var oTD=C.toDataURL, oTB=C.toBlob, oGID=X.getImageData;
    function touch(a,w,h){ var n=w*h; if(n<16) return; for(var i=0;i<32;i++){ var p=Math.floor(rnd(i)*n)*4; a[p]^=1; a[p+1]^=1; } }
    function noisy(c){
      try{ var w=c.width,h=c.height; if(!w||!h||w*h>4000000) return c;
        var k=document.createElement('canvas'); k.width=w; k.height=h;
        var x=k.getContext('2d'); x.drawImage(c,0,0);
        var d=oGID.call(x,0,0,w,h); touch(d.data,w,h); x.putImageData(d,0,0); return k;
      }catch(e){ return c; }
    }
    C.toDataURL=function(){ return oTD.apply(noisy(this),arguments); };
    if(oTB) C.toBlob=function(){ return oTB.apply(noisy(this),arguments); };
    X.getImageData=function(){ var d=oGID.apply(this,arguments); try{ touch(d.data,d.width,d.height); }catch(e){} return d; };
  }catch(e){}

  // WebGL: report a generic graphics chip.
  function gl(P){ if(!P) return; var g=P.prototype.getParameter;
    P.prototype.getParameter=function(p){ if(p===37445) return 'Google Inc.'; if(p===37446) return 'ANGLE (Generic GPU)'; return g.call(this,p); }; }
  try{ gl(window.WebGLRenderingContext); gl(window.WebGL2RenderingContext); }catch(e){}

  // Audio: tiny noise in decoded audio data (used by "audio fingerprint" scripts).
  try{ var A=window.AudioBuffer&&AudioBuffer.prototype;
    if(A){ var g2=A.getChannelData; A.getChannelData=function(){ var d=g2.apply(this,arguments);
      try{ if(!d.__ds){ for(var i=0;i<d.length;i+=997){ d[i]+=(rnd(i)-0.5)*1e-7; } Object.defineProperty(d,'__ds',{value:1}); } }catch(e){} return d; }; }
  }catch(e){}

  // Device details: common values instead of this phone's.
  function fix(o,k,v){ try{ Object.defineProperty(o,k,{get:function(){return v;},configurable:true}); }catch(e){} }
  try{ var N=Navigator.prototype; fix(N,'hardwareConcurrency',4); fix(N,'deviceMemory',4);
    if(N.getBattery) N.getBattery=undefined; }catch(e){}
}catch(e){}})();
"""

    // ------------------------------------------------------------ referrer

    private const val REFERRER = """
(function(){try{
  function add(){ try{ if(document.querySelector('meta[name=referrer][data-ds]')) return true; var r=document.head||document.documentElement; if(!r) return false;
    var m=document.createElement('meta'); m.name='referrer'; m.content='same-origin'; m.setAttribute('data-ds','1'); r.insertBefore(m,r.firstChild); return true; }catch(e){ return true; } }
  if(!add()){ var ob=new MutationObserver(function(){ if(add()) ob.disconnect(); }); ob.observe(document,{childList:true,subtree:true}); }
}catch(e){}})();
"""

    // ------------------------------------------------------------ cookie banners

    /**
     * Runs after load: clicks the "reject" button of the common consent tools when there is one,
     * hides the banner otherwise, and restores scrolling. Never clicks "accept".
     */
    const val COOKIE_BANNERS = """
(function(){try{
  if(window.__dsCb) return; window.__dsCb=1;
  var REJECT=['#onetrust-reject-all-handler','.ot-pc-refuse-all-handler','#CybotCookiebotDialogBodyButtonDecline','#CybotCookiebotDialogBodyLevelButtonLevelOptinDeclineAll',
    '#didomi-notice-disagree-button','.didomi-continue-without-agreeing','button[data-testid="uc-deny-all-button"]','.cmpboxbtnno','#truste-consent-required',
    '.qc-cmp2-summary-buttons button[mode="secondary"]','button.fc-cta-do-not-consent','#cookie_action_close_header_reject','.cky-btn-reject','.cc-deny','.cc-dismiss[data-reject]',
    '#wt-cli-reject-btn','.iubenda-cs-reject-btn','[data-cookiefirst-action="reject"]','.osano-cm-deny','#tarteaucitronAllDenied2','.klaro .cn-decline'];
  var HIDE='#onetrust-consent-sdk,#onetrust-banner-sdk,#CybotCookiebotDialog,#CybotCookiebotDialogBodyUnderlay,#didomi-host,#usercentrics-root,#cmpbox,#cmpbox2,.cmpboxBG,'+
    '#truste-consent-track,.truste_overlay,.truste_box_overlay,.qc-cmp2-container,.fc-consent-root,#cookie-law-info-bar,.cky-consent-container,.cky-overlay,.cc-window,.cc-banner,'+
    '#iubenda-cs-banner,.cookiefirst-root,.osano-cm-window,#tarteaucitronRoot,.klaro,#cookie-notice,#cookie-banner,.cookie-banner,#cookieConsent,.cookie-consent,#gdpr-banner,'+
    '[id^="sp_message_container_"],.message-overlay[class*="sp_"]';
  var tries=0;
  function pass(){
    tries++;
    var clicked=false;
    for(var i=0;i<REJECT.length && !clicked;i++){ try{ var b=document.querySelector(REJECT[i]); if(b && b.offsetParent!==null){ b.click(); clicked=true; } }catch(e){} }
    if(!document.getElementById('__ds_cb')){
      var s=document.createElement('style'); s.id='__ds_cb';
      s.textContent=HIDE+'{display:none!important}';
      (document.head||document.documentElement).appendChild(s);
    }
    // Banners often lock scrolling; unlock it only if one of them was present.
    try{ var had=document.querySelector(HIDE);
      if(had||clicked){ ['html','body'].forEach(function(t){ var e=document.querySelector(t); if(!e) return;
        var cs=getComputedStyle(e); if(cs.overflow==='hidden'||cs.overflowY==='hidden'){ e.style.setProperty('overflow','auto','important'); }
        if(cs.position==='fixed'){ e.style.setProperty('position','static','important'); } });
        ['sp-message-open','didomi-popup-open','cmp-shown','modal-open-cookie'].forEach(function(c){ document.documentElement.classList.remove(c); if(document.body) document.body.classList.remove(c); }); } }catch(e){}
    if(tries<6) setTimeout(pass, tries<3?600:1500);
  }
  pass();
}catch(e){}})();
"""
}
