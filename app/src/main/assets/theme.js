/* Deep Stellar theme palettes, shared by the built-in pages (start.html, projects.html).
   Keep in sync with app/src/main/res/values/themes.xml. */
(function(){
  window.DS_THEMES = {
    nebula:    {void:'#05060F',surface:'#0B0E1F',surface2:'#121633',stroke:'#26305A',star:'#E8ECFF',dim:'#8A93C2',violet:'#9B7BFF',blue:'#5AB8FF',
                n1:'123,92,255',n2:'90,184,255',n3:'255,122,217',g:['#E8ECFF','#B9A8FF','#8FD0FF'],orb:['#C9B8FF','#7B5CFF','#2A1F6E'],tint:['201,184,255','155,212,255']},
    horizon:   {void:'#000000',surface:'#0A0A0A',surface2:'#141414',stroke:'#2A2A2A',star:'#F2F2F2',dim:'#8C8C8C',violet:'#FFA552',blue:'#FFE0A3',
                n1:'255,140,60',n2:'255,90,50',n3:'255,220,160',g:['#FFFFFF','#FFD7A8','#FF9E4A'],orb:['#FFE0A3','#FF8A3D','#3A1200'],tint:['255,214,170','255,170,110']},
    supernova: {void:'#0F0605',surface:'#1C0D0A',surface2:'#2A1410',stroke:'#4A2A22',star:'#FFF1E8',dim:'#C29A8A',violet:'#FF8A5C',blue:'#FFC857',
                n1:'255,110,70',n2:'255,200,87',n3:'255,79,123',g:['#FFF1E8','#FFB38A','#FFC857'],orb:['#FFD9B0','#FF7A45','#4A120A'],tint:['255,200,160','255,170,200']},
    aurora:    {void:'#03100E',surface:'#071C1A',surface2:'#0C2A27',stroke:'#1E4A44',star:'#E6FFF8',dim:'#86B8AE',violet:'#4FF0B5',blue:'#7FD7FF',
                n1:'79,240,181',n2:'127,215,255',n3:'185,140,255',g:['#E6FFF8','#8FF5CF','#7FD7FF'],orb:['#C8FFEA','#2FD99A','#06352B'],tint:['170,255,220','170,225,255']}
  };

  /** Sets the CSS variables for ?theme=<key> and returns the palette. */
  window.dsApplyTheme = function(key){
    var th = window.DS_THEMES[key] || window.DS_THEMES.nebula;
    var rs = document.documentElement.style;
    ['void','surface','surface2','stroke','star','dim','violet','blue'].forEach(function(k){ rs.setProperty('--'+k, th[k]); });
    rs.setProperty('--n1', th.n1); rs.setProperty('--n2', th.n2); rs.setProperty('--n3', th.n3);
    rs.setProperty('--g1', th.g[0]); rs.setProperty('--g2', th.g[1]); rs.setProperty('--g3', th.g[2]);
    rs.setProperty('--orb1', th.orb[0]); rs.setProperty('--orb2', th.orb[1]); rs.setProperty('--orb3', th.orb[2]);
    if (document.body) document.body.style.background = th.void;
    return th;
  };
})();
