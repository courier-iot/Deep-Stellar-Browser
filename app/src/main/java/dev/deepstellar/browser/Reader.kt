package dev.deepstellar.browser

import android.content.Context
import dev.deepstellar.browser.ui.Ui

/**
 * Reader mode: finds the article on the page (the block with the most
 * paragraph text), keeps only text, headings, lists, quotes, code and images,
 * and re-lays it out in the current theme. Leaving reader mode reloads the page.
 */
object Reader {

    private fun hex(c: Int) = String.format("#%06X", 0xFFFFFF and c)

    fun script(ctx: Context): String {
        val bg = hex(Ui.color(ctx, R.color.void_black))
        val text = hex(Ui.color(ctx, R.color.starlight))
        val dim = hex(Ui.color(ctx, R.color.starlight_dim))
        val accent = hex(Ui.color(ctx, R.color.nebula_violet))
        val accent2 = hex(Ui.color(ctx, R.color.cosmic_blue))
        val surface = hex(Ui.color(ctx, R.color.void_surface))
        val stroke = hex(Ui.color(ctx, R.color.void_stroke))
        val size = (18 * Prefs.raw.getInt("reader_size", 100) / 100)
        return """
(function(){
try{
  var KEEP={P:1,H1:1,H2:1,H3:1,H4:1,H5:1,H6:1,UL:1,OL:1,LI:1,BLOCKQUOTE:1,PRE:1,CODE:1,IMG:1,FIGURE:1,FIGCAPTION:1,A:1,EM:1,STRONG:1,B:1,I:1,BR:1,HR:1,TABLE:1,THEAD:1,TBODY:1,TR:1,TD:1,TH:1,SUP:1,SUB:1,SPAN:1,DIV:1,SECTION:1,ARTICLE:1,PICTURE:1,SOURCE:1};
  var DROP='script,style,noscript,iframe,form,nav,aside,footer,header,button,input,select,textarea,svg,canvas,video,audio,object,embed,[aria-hidden="true"],[role="navigation"],[role="banner"],[role="complementary"],.ad,.ads,.advert,.advertisement,.share,.social,.related,.newsletter,.comments,#comments,.sidebar';
  var BAD=/comment|footer|sidebar|related|share|social|promo|sponsor|advert|newsletter|subscribe|cookie|popup|modal|nav|menu|breadcrumb/i;

  // 1) Score blocks by the paragraph text they contain.
  var scores=new Map();
  document.querySelectorAll('p, pre, blockquote').forEach(function(p){
    var len=(p.innerText||'').trim().length;
    if(len<40) return;
    var commas=(p.innerText.match(/,/g)||[]).length;
    var s=1+Math.min(len/100,3)+commas*0.3;
    var parent=p.parentElement, gp=parent&&parent.parentElement;
    if(parent){ scores.set(parent,(scores.get(parent)||0)+s); }
    if(gp){ scores.set(gp,(scores.get(gp)||0)+s/2); }
  });
  var best=null,bestScore=0;
  scores.forEach(function(s,el){
    var id=(el.id||'')+' '+(typeof el.className==='string'?el.className:'');
    if(BAD.test(id)) s*=0.3;
    if(/article|content|post|entry|story|body|main|text/i.test(id)) s*=1.25;
    if(el.tagName==='ARTICLE'||el.getAttribute('role')==='main') s*=1.3;
    if(s>bestScore){bestScore=s;best=el;}
  });
  if(!best||(best.innerText||'').trim().length<400) return 'none';

  // 2) Clean a copy.
  var root=best.cloneNode(true);
  root.querySelectorAll(DROP).forEach(function(n){n.remove();});
  (function walk(el){
    Array.prototype.slice.call(el.children).forEach(function(c){
      walk(c);
      if(c.tagName==='IMG'){
        var lazy=c.getAttribute('data-src')||c.getAttribute('data-lazy-src')||c.getAttribute('data-original');
        if(lazy) c.setAttribute('src',lazy);
        if(!c.getAttribute('src')){ c.remove(); return; }
      }
      var keepAttr={href:1,src:1,alt:1};
      Array.prototype.slice.call(c.attributes).forEach(function(a){ if(!keepAttr[a.name]) c.removeAttribute(a.name); });
      if(c.tagName==='A' && c.getAttribute('href')){ c.setAttribute('href', c.href); }
      if(c.tagName==='IMG'){ c.setAttribute('src', c.src); c.setAttribute('loading','lazy'); }
      // Unknown tags (time, mark, u …) are unwrapped so their text survives.
      if(!KEEP[c.tagName]){ while(c.firstChild) el.insertBefore(c.firstChild,c); c.remove(); }
    });
  })(root);

  // 3) Title, site, reading time.
  var h1=document.querySelector('h1');
  var title=(document.querySelector('meta[property="og:title"]')||{}).content||(h1&&h1.innerText)||document.title||'';
  var words=(root.innerText||'').split(/\s+/).length;
  var minutes=Math.max(1,Math.round(words/230));
  root.querySelectorAll('h1').forEach(function(h){ if(h.innerText.trim()===title.trim()) h.remove(); });

  var css='html,body{background:$bg!important;margin:0}'+
    'body{color:$text;font:${size}px/1.7 Georgia,"Noto Serif",serif;padding:28px 20px 80px;max-width:680px;margin:0 auto;word-wrap:break-word}'+
    '.ds-site{font:600 11px/1 system-ui,sans-serif;letter-spacing:.25em;text-transform:uppercase;color:$accent2;margin-bottom:14px}'+
    'h1.ds-title{font:300 1.7em/1.25 system-ui,sans-serif;letter-spacing:.01em;color:$text;margin:0 0 10px}'+
    '.ds-meta{font:12px/1 ui-monospace,monospace;color:$dim;margin-bottom:26px;padding-bottom:18px;border-bottom:1px solid $stroke}'+
    'h2,h3,h4{font-family:system-ui,sans-serif;font-weight:500;line-height:1.3;color:$text;margin:1.6em 0 .5em}'+
    'a{color:$accent;text-decoration:none;border-bottom:1px solid $stroke}'+
    'img,figure{max-width:100%!important;height:auto!important;border-radius:10px;margin:14px 0}'+
    'figcaption{font-size:.8em;color:$dim}'+
    'blockquote{margin:1.2em 0;padding:.2em 0 .2em 1em;border-left:3px solid $accent;color:$dim}'+
    'pre,code{font-family:ui-monospace,monospace;font-size:.85em;background:$surface;border-radius:6px}'+
    'pre{padding:12px;overflow-x:auto}code{padding:1px 4px}'+
    'table{border-collapse:collapse;display:block;overflow-x:auto}td,th{border:1px solid $stroke;padding:6px 8px}'+
    'hr{border:0;border-top:1px solid $stroke;margin:2em 0}'+
    '.ds-end{text-align:center;color:$dim;font:11px/1 system-ui;letter-spacing:.4em;margin-top:48px}';

  var site=location.hostname.replace(/^www\./,'');
  var esc=function(s){var d=document.createElement('div');d.textContent=s;return d.innerHTML;};
  document.head.innerHTML='<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>'+esc(title)+'</title><style>'+css+'</style>';
  document.body.removeAttribute('class');document.body.removeAttribute('style');
  document.documentElement.removeAttribute('class');document.documentElement.removeAttribute('style');
  document.body.innerHTML='<div class="ds-site">'+esc(site)+'</div><h1 class="ds-title">'+esc(title)+'</h1>'+
    '<div class="ds-meta">'+minutes+' min read · reader mode</div>';
  document.body.appendChild(root);
  var end=document.createElement('div');end.className='ds-end';end.textContent='✦ ✦ ✦';document.body.appendChild(end);
  window.scrollTo(0,0);
  return 'ok';
}catch(e){return 'error';}
})();
""".trimIndent()
    }
}
