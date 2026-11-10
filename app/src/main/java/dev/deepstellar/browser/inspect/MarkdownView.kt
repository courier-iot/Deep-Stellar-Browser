package dev.deepstellar.browser.inspect

import android.content.Context
import dev.deepstellar.browser.Prefs
import dev.deepstellar.browser.R
import dev.deepstellar.browser.ui.Ui

/**
 * Renders a raw Markdown file (README.md, docs …) in place, in the current theme.
 * Headings, lists, task boxes, quotes, code blocks, tables, links, images, emphasis.
 * Only http(s) and relative links are kept.
 */
object MarkdownView {

    fun looksLikeMarkdown(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return path.endsWith(".md") || path.endsWith(".markdown") || path.endsWith(".mdown") || path.endsWith(".mkd")
    }

    private fun hex(c: Int) = String.format("#%06X", 0xFFFFFF and c)

    fun script(ctx: Context): String {
        val bg = hex(Ui.color(ctx, R.color.void_black))
        val text = hex(Ui.color(ctx, R.color.starlight))
        val dim = hex(Ui.color(ctx, R.color.starlight_dim))
        val accent = hex(Ui.color(ctx, R.color.nebula_violet))
        val surface = hex(Ui.color(ctx, R.color.void_surface))
        val stroke = hex(Ui.color(ctx, R.color.void_stroke))
        val size = 16 * Prefs.raw.getInt("reader_size", 100) / 100
        return """
(function(){
try{
  var src=document.body?document.body.innerText:'';
  if(!src.trim()) return 'empty';
  function esc(s){return s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');}
  function safe(u){return /^(https?:|\/|\.|#|[\w-]+(\/|\.|$))/i.test(u)&&!/^javascript:/i.test(u)?u:'#';}
  function inline(s){
    s=esc(s);
    var codes=[];
    s=s.replace(/`([^`]+)`/g,function(_,c){codes.push(c);return '\u0000'+(codes.length-1)+'\u0000';});
    s=s.replace(/!\[([^\]]*)\]\(([^)\s]+)[^)]*\)/g,function(_,a,u){return '<img alt="'+a+'" src="'+safe(u)+'">';});
    s=s.replace(/\[([^\]]+)\]\(([^)\s]+)[^)]*\)/g,function(_,t,u){return '<a href="'+safe(u)+'">'+t+'</a>';});
    s=s.replace(/\*\*([^*]+)\*\*|__([^_]+)__/g,function(_,a,b){return '<strong>'+(a||b)+'</strong>';});
    s=s.replace(/(^|[^*\w])\*([^*\s][^*]*)\*/g,'$1<em>$2</em>');
    s=s.replace(/~~([^~]+)~~/g,'<del>$1</del>');
    s=s.replace(/(^|[\s(])(https?:\/\/[^\s<)]+)/g,'$1<a href="$2">$2</a>');
    s=s.replace(/\u0000(\d+)\u0000/g,function(_,i){return '<code>'+codes[+i]+'</code>';});
    return s;
  }
  var LIST=/^\s*([-*+]|\d+[.)])\s+/;
  var lines=src.replace(/\r/g,'').split('\n'),out=[],i=0;
  while(i<lines.length){
    var l=lines[i];
    if(/^\s*```/.test(l)){
      var buf=[];i++;
      while(i<lines.length&&!/^\s*```/.test(lines[i])){buf.push(esc(lines[i]));i++;}
      i++;out.push('<pre><code>'+buf.join('\n')+'</code></pre>');continue;
    }
    var h=l.match(/^(#{1,6})\s+(.*)$/);
    if(h){var n=h[1].length;out.push('<h'+n+'>'+inline(h[2].replace(/\s#+\s*$/,''))+'</h'+n+'>');i++;continue;}
    if(/^\s*([-*_])(\s*\1){2,}\s*$/.test(l)){out.push('<hr>');i++;continue;}
    if(/^>/.test(l)){
      var q=[];
      while(i<lines.length&&/^>/.test(lines[i])){q.push(lines[i].replace(/^>\s?/,''));i++;}
      out.push('<blockquote>'+q.map(inline).join('<br>')+'</blockquote>');continue;
    }
    if(LIST.test(l)){
      var ordered=/^\s*\d/.test(l),items=[];
      while(i<lines.length&&LIST.test(lines[i])){
        var item=lines[i].replace(LIST,'').replace(/^\[ \]\s/,'☐ ').replace(/^\[[xX]\]\s/,'☑ ');
        items.push('<li>'+inline(item)+'</li>');i++;
      }
      out.push((ordered?'<ol>':'<ul>')+items.join('')+(ordered?'</ol>':'</ul>'));continue;
    }
    if(/^\|.*\|\s*$/.test(l)&&i+1<lines.length&&/^\|\s*:?-+/.test(lines[i+1])){
      var rows=[];
      while(i<lines.length&&/^\|.*\|\s*$/.test(lines[i])){rows.push(lines[i]);i++;}
      var cells=function(r){return r.trim().replace(/^\||\|$/g,'').split('|').map(function(c){return c.trim();});};
      var head=cells(rows[0]);
      out.push('<table><thead><tr>'+head.map(function(c){return '<th>'+inline(c)+'</th>';}).join('')+'</tr></thead><tbody>'+
        rows.slice(2).map(function(r){return '<tr>'+cells(r).map(function(c){return '<td>'+inline(c)+'</td>';}).join('')+'</tr>';}).join('')+
        '</tbody></table>');
      continue;
    }
    if(!l.trim()){i++;continue;}
    var p=[l];i++;
    while(i<lines.length&&lines[i].trim()&&!/^(#{1,6}\s|\s*```|>|\|)/.test(lines[i])&&!LIST.test(lines[i])){p.push(lines[i]);i++;}
    out.push('<p>'+inline(p.join(' '))+'</p>');
  }
  var css='html,body{background:$bg!important;margin:0}'+
    'body{color:$text;font:${size}px/1.65 system-ui,sans-serif;padding:24px 18px 80px;max-width:760px;margin:0 auto;word-wrap:break-word}'+
    'h1,h2{border-bottom:1px solid $stroke;padding-bottom:.3em}h1,h2,h3,h4,h5,h6{line-height:1.3;margin:1.4em 0 .5em;font-weight:500}'+
    'a{color:$accent;text-decoration:none}img{max-width:100%;border-radius:8px}'+
    'code{font-family:ui-monospace,monospace;font-size:.88em;background:$surface;padding:1px 5px;border-radius:5px}'+
    'pre{background:$surface;border:1px solid $stroke;border-radius:8px;padding:12px;overflow-x:auto}pre code{padding:0;background:none}'+
    'blockquote{margin:1em 0;padding:.1em 1em;border-left:3px solid $accent;color:$dim}'+
    'table{border-collapse:collapse;display:block;overflow-x:auto}th,td{border:1px solid $stroke;padding:6px 10px}th{background:$surface}'+
    'hr{border:0;border-top:1px solid $stroke;margin:2em 0}li{margin:.25em 0}';
  var title=location.pathname.split('/').pop()||'Markdown';
  document.head.innerHTML='<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>'+esc(title)+'</title><style>'+css+'</style>';
  document.body.removeAttribute('style');
  document.body.innerHTML=out.join('\n');
  return 'ok';
}catch(e){return 'error';}
})();
""".trimIndent()
    }
}
