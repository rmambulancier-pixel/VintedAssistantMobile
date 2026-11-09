package fr.vintedassistant.mobile.ui.purchases

import org.json.JSONArray
import org.json.JSONObject

/**
 * Chasseur de pépites : lecture des bijoux affichés sur la page du fournisseur (nom, prix, lien) et pose d'une
 * pastille sur chacun. Lecture seule : rien n'est ajouté au panier ni commandé. Le site n'étant pas documenté,
 * la détection est générique : une image de produit et, autour d'elle, un prix.
 */
object PepitesScripts {
    val scan: String = """
        (function(){
          if(/order|cart|checkout|account/i.test(location.pathname))return '[]';
          const priceRe=/(?:€|EUR|US\$|\$)\s*(\d+(?:[.,]\d{1,2})?)|(\d+(?:[.,]\d{1,2})?)\s*(?:€|EUR)/;
          window.__vbSeq=window.__vbSeq||0;
          const out=[];const seen=new Set();
          for(const img of Array.from(document.querySelectorAll('img'))){
            const r=img.getBoundingClientRect();
            if(r.width<90||r.height<90)continue;
            let card=img.parentElement,hit=null;
            for(let d=0;d<6&&card&&card!==document.body;d++,card=card.parentElement){
              const t=(card.innerText||'').trim();
              if(t.length>420)break;
              // Plusieurs grandes images : c'est la liste entière, pas la carte d'un bijou.
              if(Array.from(card.querySelectorAll('img')).filter(i=>i.getBoundingClientRect().width>=90).length>1)break;
              const m=t.match(priceRe);
              if(m){hit={card:card,m:m,t:t};break;}
            }
            if(!hit||seen.has(hit.card))continue;
            seen.add(hit.card);
            const link=hit.card.querySelector('a[href]')||hit.card.closest('a[href]');
            const lines=hit.t.split('\n').map(s=>s.trim()).filter(s=>s.length>=12&&!priceRe.test(s));
            const name=(img.alt&&img.alt.length>=12?img.alt:'')||(link&&link.title)||lines.sort((a,b)=>b.length-a.length)[0]||'';
            if(!name)continue;
            if(!hit.card.dataset.vb)hit.card.dataset.vb=String(++window.__vbSeq);
            out.push({id:hit.card.dataset.vb,name:name.slice(0,200),price:(hit.m[1]||hit.m[2]).replace(',','.'),href:link?link.href:''});
            if(out.length>=150)break;
          }
          return JSON.stringify(out);
        })();
    """.trimIndent()

    /** Pose ou met à jour les pastilles : [{id, text, color}]. */
    fun paint(badges: JSONArray): String = """
        (function(list){
          for(const b of list){
            const card=document.querySelector('[data-vb="'+b.id+'"]');if(!card)continue;
            let tag=card.querySelector(':scope > .vb-badge');
            if(!tag){
              tag=document.createElement('span');tag.className='vb-badge';
              tag.style.cssText='position:absolute;top:4px;left:4px;z-index:50;padding:2px 6px;border-radius:10px;font:bold 12px sans-serif;color:#fff;pointer-events:none;box-shadow:0 1px 3px rgba(0,0,0,.4)';
              if(getComputedStyle(card).position==='static')card.style.position='relative';
              card.appendChild(tag);
            }
            tag.textContent=b.text;tag.style.background=b.color;
          }
          return 'ok';
        })($badges);
    """.trimIndent()

    /** Lance une recherche sur le site avec [keyword]. Renvoie « ok » ou « absent » si aucun champ de recherche. */
    fun search(keyword: String): String = """
        (function(k){
          const cands=Array.from(document.querySelectorAll('input[type="search"],input[name*="key" i],input[name*="search" i],input[placeholder*="rech" i],input[placeholder*="search" i],input[class*="search" i]'));
          const e=cands.find(x=>x.offsetParent!==null)||cands[0];
          if(!e)return 'absent';
          e.focus();
          Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(e,k);
          e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));
          const o={key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true};
          e.dispatchEvent(new KeyboardEvent('keydown',o));e.dispatchEvent(new KeyboardEvent('keypress',o));e.dispatchEvent(new KeyboardEvent('keyup',o));
          if(e.form){if(e.form.requestSubmit)e.form.requestSubmit();else e.form.submit();}
          return 'ok';
        })(${JSONObject.quote(keyword)});
    """.trimIndent()

    val scrollDown: String = "(function(){window.scrollBy(0,Math.round(innerHeight*0.9));return 'ok';})();"
}
