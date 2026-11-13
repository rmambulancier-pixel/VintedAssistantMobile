package fr.vintedassistant.mobile.ui.vinted

import org.json.JSONObject
import org.json.JSONTokener

/**
 * Scripts exécutés dans la page Vinted ouverte par l'utilisateur, uniquement quand il appuie
 * sur un bouton. Ils ne lisent ni mot de passe ni cookie et ne publient jamais : ils remplissent le
 * formulaire (champs, menu des catégories) ou décrivent la structure de la page (sans les valeurs saisies).
 * Le bouton « Ajouter » de Vinted reste toujours à toucher par l'utilisateur.
 */
object VintedPageScripts {

    private const val HELPERS = """
        function vbVisible(e){const r=e.getBoundingClientRect();return r.width>0&&r.height>0;}
        function vbLabel(e){let t='';try{if(e.id){const l=document.querySelector('label[for="'+CSS.escape(e.id)+'"]');if(l)t+=l.innerText;}}catch(x){}
          const pl=e.closest('label');if(pl)t+=' '+pl.innerText;return t;}
        function vbDescribe(e){return [e.name,e.id,e.placeholder,e.getAttribute('aria-label'),e.getAttribute('data-testid'),vbLabel(e)].join(' ').toLowerCase();}
    """

    /** Remplit titre, description et prix du formulaire de vente affiché. */
    fun fill(title: String, description: String, price: String): String {
        val payload = JSONObject().put("title", title).put("description", description).put("price", price).toString()
        return """
        (function(p){
          $HELPERS
          function find(sel,re){return Array.from(document.querySelectorAll(sel)).filter(vbVisible).find(e=>re.test(vbDescribe(e)));}
          function setVal(e,v){
            const proto=e.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;
            Object.getOwnPropertyDescriptor(proto,'value').set.call(e,v);
            ['input','change','blur'].forEach(t=>e.dispatchEvent(new Event(t,{bubbles:true})));
          }
          const r={title:false,description:false,price:false};
          const t=find('input[type="text"],input:not([type])',/titre|title/);
          if(t&&p.title){setVal(t,p.title);r.title=true;}
          let d=find('textarea',/descri/);
          if(!d){const ta=Array.from(document.querySelectorAll('textarea')).filter(vbVisible);if(ta.length===1)d=ta[0];}
          if(d&&p.description){setVal(d,p.description);r.description=true;}
          const pr=find('input',/prix|price/);
          if(pr&&p.price){setVal(pr,p.price);r.price=true;}
          return JSON.stringify(r);
        })($payload);
        """.trimIndent()
    }

    /**
     * Catégorie : ouvre le menu de Vinted, cherche [query] dans « Trouver une catégorie », puis descend
     * vers le résultat le plus sûr (mot du bijou, genre, « bijou »), et valide avec « Fait ».
     * Structure relevée le 8 novembre 2026 : boîte `.ReactModal__Content`, lignes `.web_ui__Cell__clickable`,
     * bouton `input-dropdown-save-button`. Travail asynchrone : résultat dans `window.__vbCat`, lu par [categoryResult].
     */
    fun categoryStart(query: String, leaves: List<String>, gender: String, avoid: List<String>): String {
        val payload = JSONObject().put("query", query).put("leaf", org.json.JSONArray(leaves))
            .put("gender", gender).put("avoid", org.json.JSONArray(avoid)).toString()
        return """
        (function(p){
          const R={done:false,steps:[],options:[]};window.__vbCat=R;
          const sleep=ms=>new Promise(r=>setTimeout(r,ms));
          const norm=s=>(s||'').normalize('NFD').replace(/[̀-ͯ]/g,'').toLowerCase().replace(/\s+/g,' ').trim();
          function setVal(e,v){Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(e,v);
            e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));}
          const dialog=()=>document.querySelector('.ReactModal__Content');
          const cells=()=>{const d=dialog();return d?Array.from(d.querySelectorAll('.web_ui__Cell__clickable,[class*="Cell__clickable"]')):[];};
          const text=c=>norm(c.innerText||c.textContent);
          const score=t=>(p.leaf.some(l=>t.includes(l))?4:0)+(t.includes(p.gender)?2:0)+(t.includes('bijou')?1:0)-(p.avoid.some(a=>t.includes(a))?3:0);
          const saveBtn=()=>document.querySelector('[data-testid="input-dropdown-save-button"]');
          const canSave=b=>b&&!b.disabled&&!/disabled/.test(b.className);
          (async()=>{
            try{
              const findCat=()=>document.querySelector('[data-testid="catalog-select-dropdown-input"]')||document.getElementById('category')||
                document.querySelector('input[name="category"],input[id*="catalog"],[data-testid*="catalog"] input,[data-testid*="category"] input')||
                (()=>{const lab=Array.from(document.querySelectorAll('label')).find(l=>/^cat[eé]gorie/i.test((l.innerText||'').trim()));
                  const box=lab&&lab.parentElement&&lab.parentElement.closest('div');return box?box.querySelector('input'):null;})();
              let inp=findCat();
              // Formulaire encore en chargement : on attend jusqu'à 8 secondes.
              for(let i=0;i<40&&!inp;i++){await sleep(200);inp=findCat();}
              if(!inp){
                R.error='champ Catégorie introuvable';
                R.page=location.pathname;
                R.fields=Array.from(document.querySelectorAll('input,textarea')).map(e=>(e.getAttribute('data-testid')||e.id||e.name||e.tagName).slice(0,60)).slice(0,30);
                R.labels=Array.from(document.querySelectorAll('label')).map(l=>(l.innerText||'').trim().slice(0,40)).filter(Boolean).slice(0,30);
                return;
              }
              if(!dialog()){inp.focus();inp.click();}
              for(let i=0;i<25&&!dialog();i++)await sleep(150);
              const d=dialog();if(!d){R.error='menu des catégories non ouvert';return;}
              const cs=getComputedStyle(d),rc=d.getBoundingClientRect(),ov=getComputedStyle(d.parentElement);
              R.probe={rect:[rc.x,rc.y,rc.width,rc.height].map(Math.round),opacity:cs.opacity,transform:cs.transform,
                visibility:cs.visibility,position:cs.position,overlayOpacity:ov.opacity,viewport:[innerWidth,innerHeight]};
              // La liste se charge après l'ouverture : on attend des lignes (5 s au plus).
              const waitCells=async(ms,pred)=>{for(let t=0;t<ms;t+=200){const c=cells();if(c.length&&(!pred||pred(c)))return c;await sleep(200);}return cells();};
              await waitCells(5000);
              const search=d.querySelector('input');
              if(search&&p.query){
                search.focus();setVal(search,p.query);await sleep(400);
                await waitCells(6000,c=>c.some(x=>p.leaf.some(l=>text(x).includes(l))));
              }
              for(let depth=0;depth<6;depth++){
                if(depth>0)await waitCells(3000);
                const list=cells().map(c=>({c,t:text(c)})).filter(x=>x.t&&!R.steps.includes(x.t.slice(0,90)));
                list.forEach(x=>x.s=score(x.t));list.sort((a,b)=>b.s-a.s);
                R.options=list.slice(0,10).map(x=>x.t.slice(0,90)+' ['+x.s+']');
                if(!list.length||list[0].s<4){
                  if(!R.error)R.error=list.length?'aucune catégorie sûre':'liste des catégories vide';
                  const dd=dialog();
                  R.cellCount=cells().length;
                  R.dialogText=dd?(dd.innerText||'').replace(/\s+/g,' ').slice(0,300):'';
                  R.dialogClasses=dd?Array.from(dd.querySelectorAll('[role="button"],li,[class*="Cell"]')).slice(0,6).map(e=>String(e.className).slice(0,80)):[];
                  break;
                }
                R.steps.push(list[0].t.slice(0,90));
                list[0].c.click();
                await sleep(800);
                const b=saveBtn();
                if(canSave(b)){b.click();await sleep(600);R.ok=true;break;}
                if(!dialog()){R.ok=true;break;}
              }
              R.chosen=inp.value||'';
              if(R.ok&&dialog()){const b=saveBtn();if(canSave(b))b.click();}
            }catch(e){R.error=String(e);}finally{R.done=true;}
          })();
          return 'started';
        })($payload);
        """.trimIndent()
    }

    /**
     * Détails apparus après la catégorie (« Pour qui ? », Marque, État, Couleur, Matériau) : chaque champ est
     * un menu `input-dropdown` qui ouvre une boîte `.ReactModal__Content`. Pour chaque champ vide, on ouvre le menu,
     * on tape la valeur si une recherche existe, on coche la première valeur candidate trouvée (jusqu'à [max]),
     * puis « Fait ». Un champ déjà rempli n'est pas touché. Résultat dans `window.__vbDrop`, lu par [detailsResult].
     */
    fun detailsStart(fields: org.json.JSONArray): String = """
        (function(fields){
          const R={done:false,fields:[]};window.__vbDrop=R;
          const sleep=ms=>new Promise(r=>setTimeout(r,ms));
          const norm=s=>(s||'').normalize('NFD').replace(/[̀-ͯ]/g,'').toLowerCase().replace(/\s+/g,' ').trim();
          function setVal(e,v){Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(e,v);
            e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));}
          const vis=e=>{if(!e||!e.isConnected)return false;const r=e.getBoundingClientRect();return r.width>1&&r.height>1;};
          // La boîte de Vinted peut mesurer 0 de haut (son contenu est positionné à part) : elle compte comme
          // affichée dès qu'un de ses éléments l'est.
          const shown=m=>!!m&&m.isConnected&&(vis(m)||Array.from(m.querySelectorAll('*')).slice(0,60).some(vis));
          const modals=()=>Array.from(document.querySelectorAll('.ReactModal__Content')).filter(shown);
          const sig=m=>(m.innerText||m.textContent||'').replace(/\s+/g,' ').slice(0,300);
          // Le menu du champ en cours. Vinted peut laisser un ancien menu dans la page (13 novembre : la liste des
          // couleurs a été lue à la place de celle des états) : on ne travaille que dans le menu ouvert par ce champ.
          let cur=null;
          const dialog=()=>{if(cur&&shown(cur))return cur;const all=modals();return all.length?all[all.length-1]:null;};
          const saveBtn=()=>{const d=dialog()||document;return d.querySelector('[data-testid="input-dropdown-save-button"]')||
            Array.from(d.querySelectorAll('button')).find(b=>/^(fait|valider|ok|enregistrer|termine|terminer)$/i.test(norm(b.innerText)));};
          const canSave=b=>b&&!b.disabled&&!/disabled/.test(b.className);
          const closeBtn=()=>{const d=dialog();return d&&d.querySelector('.web_ui__Navigation__right button, [data-testid$="close-button"]');};
          function fieldInput(re){
            const lab=Array.from(document.querySelectorAll('label[class*="input-dropdown__title"]')).find(l=>re.test(norm(l.textContent)));
            const box=lab&&lab.parentElement&&lab.parentElement.closest('[class*="input-dropdown"]');
            return box&&box.querySelector('input');
          }
          const optionsOf=d=>{if(!d)return [];
            return Array.from(d.querySelectorAll('.web_ui__Cell__clickable, [role="option"], [role="radio"], [role="checkbox"], label'))
              .filter(e=>vis(e)&&!e.querySelector('.web_ui__Cell__clickable'))
              .map(e=>({e,t:norm(e.innerText||e.textContent)})).filter(x=>x.t&&x.t.length<160);};
          const options=()=>optionsOf(dialog());
          const fits=(f,list)=>!f.expect||list.some(x=>new RegExp(f.expect).test(x.t));
          // Fenêtre d'information de Vinted (ex. après « Neuf sans étiquette » : « L'article ne doit jamais avoir
          // été utilisé… ») : sans options à cocher, elle bloque les menus suivants. On la referme.
          async function dismissInfo(){
            cur=null;
            for(let k=0;k<3;k++){
              const d=dialog();if(!d||options().length)return;
              const btns=Array.from(d.querySelectorAll('button')).filter(b=>!b.disabled);
              const b=btns.find(x=>/compris|d.accord|^ok$|continuer|confirmer|fermer|valider/i.test((x.innerText||x.getAttribute('aria-label')||'').trim()))||closeBtn()||btns[btns.length-1];
              if(!b)return;
              R.dismissed=(R.dismissed||[]).concat([(b.innerText||b.getAttribute('aria-label')||'bouton').trim().slice(0,40)]);
              b.click();await sleep(600);
            }
          }
          // Referme les menus restés ouverts avant d'en ouvrir un autre.
          async function closeLeftovers(){
            cur=null;
            for(let k=0;k<3;k++){
              const d=dialog();if(!d)return;
              const c=closeBtn();if(!c)return;
              c.click();await sleep(500);
            }
          }
          const wanted=(f,value)=>{const v=norm(value);if(!v)return false;if(!f.verify)return true;
            return f.values.some(w=>{const n=norm(w);return v===n||v.includes(n);});};
          async function fill(f,out){
            const inp=fieldInput(new RegExp(f.label));
            if(!inp){out.status='absent';return;}
            if(!f.values.length){out.status=(inp.value||'').trim()?'déjà rempli':'à choisir';return;}
            if((inp.value||'').trim()){
              if(wanted(f,inp.value)){out.status='déjà rempli';out.picked=[inp.value];return;}
              out.replaced=inp.value;
            }
            await closeLeftovers();
            const before=new Map(modals().map(m=>[m,sig(m)]));
            inp.focus();inp.click();
            // Le menu du champ : une boîte nouvelle, ou une boîte réutilisée dont le contenu vient de changer.
            for(let i=0;i<25&&!cur;i++){await sleep(150);cur=modals().reverse().find(m=>!before.has(m)||before.get(m)!==sig(m))||null;}
            if(!cur){
              // Rien de nouveau : on prend la dernière boîte ; ses choix sont contrôlés juste après.
              const all=modals();
              if(all.length)cur=all[all.length-1];
              else{out.status='menu non ouvert';out.boxes=document.querySelectorAll('.ReactModal__Content').length;return;}
            }
            // La liste se charge après l'ouverture : on attend des options (5 s au plus).
            const waitOptions=async(ms,pred)=>{for(let t=0;t<ms;t+=200){const o=options();if(o.length&&(!pred||pred(o)))return o;await sleep(200);}return options();};
            const first=await waitOptions(5000,o=>fits(f,o));
            if(!fits(f,first)){
              out.status='mauvais menu ouvert';out.seen=first.slice(0,8).map(x=>x.t);
              const c=closeBtn();if(c){c.click();await sleep(400);}
              return;
            }
            const search=dialog()&&dialog().querySelector('input[type="text"],input[type="search"],input:not([type])');
            for(const v of f.values){
              if(out.picked.length>=f.max)break;
              const nv=norm(v);
              if(search&&f.search){search.focus();setVal(search,v);await sleep(400);await waitOptions(4000,o=>o.some(x=>x.t===nv||x.t.startsWith(nv)));}
              const list=options();
              out.seen=list.slice(0,40).map(x=>x.t.slice(0,30));
              // Marque : nom exact uniquement (jamais une marque voisine), sinon « Sans marque ».
              const hit=list.find(x=>x.t===nv)||(f.exact?null:(list.find(x=>x.t.startsWith(nv+' ')||x.t.startsWith(nv))||(f.loose?list.find(x=>x.t.includes(nv)):null)));
              if(hit&&!out.picked.includes(hit.t)){hit.e.click();out.picked.push(hit.t);await sleep(500);}
            }
            const b=saveBtn();
            out.save=b?(canSave(b)?'cliqué':'grisé'):'aucun bouton';
            if(out.picked.length&&canSave(b)){b.click();await sleep(700);}
            if(cur&&shown(cur)&&options().length){const c=closeBtn();if(c){c.click();await sleep(400);}}
            await sleep(300);await dismissInfo();
            if(!out.seen.length){const dd=dialog();out.dialogText=dd?(dd.innerText||'').replace(/\s+/g,' ').slice(0,200):'(boîte fermée)';}
            const value=(inp.value||'').trim();
            if(value&&wanted(f,value)){out.status='rempli';out.picked=[value];}
            else if(value){out.status='différent';out.shown=value;}
            else out.status=out.picked.length?'choisi, à vérifier':'non trouvé';
          }
          (async()=>{
            try{
              for(const f of fields){
                await dismissInfo();
                let out={field:f.name,status:'',picked:[],seen:[]};
                await fill(f,out);
                // Un second essai quand le champ n'a pas pris la bonne valeur.
                if(!['rempli','déjà rempli','absent','à choisir'].includes(out.status)){
                  const again={field:f.name,status:'',picked:[],seen:[],retry:out.status};
                  await sleep(500);await dismissInfo();await fill(f,again);
                  if(['rempli','déjà rempli'].includes(again.status)||!out.seen.length)out=again;else out.retry=again.status;
                }
                cur=null;R.fields.push(out);
              }
            }catch(e){R.error=String(e);}finally{R.done=true;}
          })();
          return 'started';
        })($fields);
    """.trimIndent()

    /** Saisit le prix (champ `#price`) et renvoie ce que Vinted affiche ensuite, pour vérifier qu'il l'a compris. */
    fun setPrice(value: String): String {
        val v = JSONObject.quote(value)
        return """
        (function(v){
          const e=document.getElementById('price')||document.querySelector('[data-testid="price-input--input"]');
          if(!e)return 'absent';
          e.focus();
          // Saisie « comme au clavier » : React enregistre la valeur (la simple affectation pouvait rester affichée
          // sans être prise en compte, d'où « Le champ prix doit être supérieur ou égal à 1.0 »).
          e.select();
          let typed=false;
          try{typed=document.execCommand('selectAll',false,null)&&document.execCommand('insertText',false,v);}catch(x){typed=false;}
          if(!typed||e.value.replace(/[^0-9]/g,'')===''){
            Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(e,v);
            e.dispatchEvent(new InputEvent('input',{bubbles:true,data:v,inputType:'insertText'}));
            e.dispatchEvent(new Event('change',{bubbles:true}));
          }
          e.blur();e.dispatchEvent(new Event('blur',{bubbles:true}));e.dispatchEvent(new FocusEvent('focusout',{bubbles:true}));
          return 'ok';
        })($v);
        """.trimIndent()
    }

    /** Valeur affichée du prix, suivie de « |ERREUR » si Vinted affiche un message d'erreur sous le champ. */
    val readPrice: String = "(function(){const e=document.getElementById('price');if(!e)return 'absent';" +
        "const box=e.closest('[class*=\"input\"]')&&e.closest('[class*=\"input\"]').parentElement;" +
        "const err=box&&/sup[eé]rieur ou [eé]gal|invalide|obligatoire/i.test(box.innerText||'');" +
        "return e.value+(err?'|ERREUR':'');})();"

    /** Taille du colis : « Petit » (recommandé pour un bijou) si aucune taille n'est cochée. */
    val packageSmall: String = """
        (function(){
          const radios=Array.from(document.querySelectorAll('input[type="radio"][id^="package_type_selector_"]'));
          if(!radios.length)return 'absent';
          if(radios.some(r=>r.checked))return 'déjà choisi';
          const cell=document.querySelector('[data-testid="1-package-size--cell"]')||document.getElementById('package_type_selector_1');
          if(cell){cell.click();return 'petit';}
          return 'absent';
        })();
    """.trimIndent()

    val detailsResult: String = "(function(){return JSON.stringify(window.__vbDrop||null);})();"

    /** Ouvre l'ajout de photos de Vinted : le sélecteur reçoit directement les photos préparées par l'application. */
    val openPhotoPicker: String = """
        (function(){
          const b=Array.from(document.querySelectorAll('button')).find(x=>/ajoute des photos|ajouter des photos|t[ée]l[ée]charge/i.test(x.innerText||''));
          const i=document.querySelector('input[type="file"]');
          if(document.querySelector('[class*="media-select__grid"] img'))return 'already';
          if(b){b.click();return 'button';}
          if(i){i.click();return 'input';}
          return 'none';
        })();
    """.trimIndent()

    /** Formulaire de mise en vente affiché : champ titre présent. */
    val formReady: String = "(function(){return !!document.querySelector('input[name=\"title\"],[data-testid*=\"title\"] input,input#title')||document.querySelectorAll('textarea').length>0?'1':'0';})();"

    val categoryResult: String = "(function(){return JSON.stringify(window.__vbCat||null);})();"

    /**
     * Correctif d'affichage : dans la fenêtre intégrée, la boîte des catégories de Vinted restait invisible
     * (fond grisé seul). On force son opacité et sa visibilité, sans toucher à sa position.
     */
    val dialogFix: String = """
        (function(){
          if(document.getElementById('vb-dialog-fix'))return 'ok';
          const s=document.createElement('style');s.id='vb-dialog-fix';
          s.textContent='.ReactModal__Content,.web_ui__Dialog__dialog{opacity:1 !important;visibility:visible !important;}';
          document.head.appendChild(s);return 'ok';
        })();
    """.trimIndent()

    /** Décrit les champs et boutons de la page (sans leur contenu), pour écrire des automatisations fiables ensuite. */
    val diagnostic: String = """
        (function(){
          $HELPERS
          const out={path:location.pathname,fields:[],buttons:[]};
          Array.from(document.querySelectorAll('input,textarea,select')).filter(vbVisible).slice(0,80).forEach(e=>{
            if(e.type==='password'||e.type==='hidden')return;
            out.fields.push({tag:e.tagName.toLowerCase(),type:e.type||'',name:e.name||'',id:e.id||'',
              placeholder:e.placeholder||'',aria:e.getAttribute('aria-label')||'',testid:e.getAttribute('data-testid')||'',
              label:vbLabel(e).trim().slice(0,60)});
          });
          Array.from(document.querySelectorAll('button,[role="button"],a[data-testid]')).filter(vbVisible).slice(0,80).forEach(e=>{
            out.buttons.push({text:(e.innerText||'').trim().slice(0,40),testid:e.getAttribute('data-testid')||'',
              aria:e.getAttribute('aria-label')||''});
          });
          return JSON.stringify(out);
        })();
    """.trimIndent()

    /** Dressing : relève chaque annonce affichée (lien, titre, prix, favoris, vues, première photo). */
    val grid: String = """
        (function(){
          const out={items:[]};const seen={};
          Array.from(document.querySelectorAll('a[href*="/items/"]')).forEach(a=>{
            const m=a.href.match(/\/items\/(\d+)/);if(!m||seen[m[1]])return;seen[m[1]]=1;
            let card=a;
            for(let i=0;i<6&&card.parentElement;i++){if(/\d+[,.]\d{2}\s?€/.test(card.innerText||''))break;card=card.parentElement;}
            const text=card.innerText||'';
            const img=card.querySelector('img');
            out.items.push({url:a.href,
              title:a.getAttribute('title')||a.getAttribute('aria-label')||(img&&img.alt)||'',
              price:(text.match(/\d+[,.]\d{2}\s?€/)||[''])[0],
              favorites:(text.match(/(\d+)\s*favori/i)||['',''])[1],
              views:(text.match(/(\d+)\s*vue/i)||['',''])[1],
              image:img?(img.currentSrc||img.src||''):''});
          });
          return JSON.stringify(out);
        })();
    """.trimIndent()

    /** Page d'une annonce : données structurées (JSON-LD), balises de partage, détails affichés, photos de l'annonce. */
    val item: String = """
        (function(){
          $HELPERS
          const out={url:location.href,title:'',price:'',description:'',brand:'',size:'',condition:'',color:'',category:'',added:'',favorites:'',views:'',images:[]};
          const crumbs=[];
          const meta=n=>{const e=document.querySelector('meta[property="'+n+'"],meta[name="'+n+'"]');return e?(e.content||''):'';};
          function walk(o){
            if(!o||typeof o!=='object')return;
            if(Array.isArray(o)){o.forEach(walk);return;}
            const t=o['@type'];
            if(t==='Product'||(Array.isArray(t)&&t.indexOf('Product')>=0)){
              out.title=out.title||o.name||'';out.description=out.description||o.description||'';
              if(o.brand)out.brand=out.brand||(o.brand.name||(typeof o.brand==='string'?o.brand:''));
              const of=Array.isArray(o.offers)?o.offers[0]:o.offers;
              if(of&&of.price!=null)out.price=out.price||String(of.price);
              const im=Array.isArray(o.image)?o.image:(o.image?[o.image]:[]);
              im.forEach(u=>out.images.push(typeof u==='string'?u:(u.url||u.contentUrl||'')));
              if(typeof o.color==='string')out.color=out.color||o.color;
            }
            if(t==='BreadcrumbList'&&Array.isArray(o.itemListElement)){
              o.itemListElement.forEach(e=>{const n=e.name||(e.item&&e.item.name);if(n)crumbs.push(String(n));});
            }
            if(o['@graph'])walk(o['@graph']);
          }
          document.querySelectorAll('script[type="application/ld+json"]').forEach(s=>{try{walk(JSON.parse(s.textContent));}catch(e){}});
          out.title=out.title||meta('og:title')||((document.querySelector('h1')||{}).innerText||'');
          out.description=out.description||meta('og:description');
          out.price=out.price||meta('product:price:amount');
          const og=meta('og:image');if(og)out.images.push(og);
          const leaves=Array.from(document.querySelectorAll('div,span,dt,dd,li,p')).filter(x=>x.children.length===0);
          function detail(labels){
            for(const l of labels){
              const e=leaves.find(x=>(x.innerText||'').trim().toLowerCase()===l);
              if(e){let v=e.nextElementSibling;if(!v&&e.parentElement)v=e.parentElement.nextElementSibling;
                if(v&&v.innerText)return v.innerText.trim().split('\n')[0].slice(0,120);}
            }
            return '';
          }
          out.brand=out.brand||detail(['marque']);out.size=detail(['taille']);
          out.condition=detail(['état','etat']);out.color=out.color||detail(['couleur']);
          out.added=detail(['ajouté','ajoute']);
          // Catégorie : fil d'Ariane structuré, sinon liens du catalogue en haut de page ; on garde le niveau le plus précis.
          if(!crumbs.length){Array.from(document.querySelectorAll('a[href*="/catalog/"]')).filter(vbVisible)
            .filter(a=>a.getBoundingClientRect().top+window.scrollY<1200).forEach(a=>{const t=(a.innerText||'').trim();if(t)crumbs.push(t);});}
          const cats=crumbs.filter(c=>c&&c.toLowerCase()!==String(out.title).toLowerCase()&&!/^(vinted|accueil|home)$/i.test(c));
          out.category=cats.length?cats[cats.length-1]:'';
          const body=document.body.innerText||'';
          out.favorites=(body.match(/(\d+)\s*favori/i)||['',''])[1];out.views=(body.match(/(\d+)\s*vue/i)||['',''])[1];
          const id=(location.pathname.match(/\/items\/(\d+)/)||[])[1];
          Array.from(document.querySelectorAll('img')).forEach(i=>{
            const u=i.currentSrc||i.src;if(!u||!/vinted\.net/.test(u)||(i.naturalWidth||0)<400)return;
            const box=i.getBoundingClientRect();
            if(box.width<150||box.height<150)return; // avatar, icônes
            if(i.closest('a[href*="/member/"]'))return; // photo de profil
            const link=i.closest('a[href*="/items/"]');
            if(link&&id&&link.href.indexOf('/items/'+id)<0)return; // photo d'une autre annonce (suggestions)
            if(i.getBoundingClientRect().top+window.scrollY>2000)return; // bas de page : suggestions
            out.images.push(u);
          });
          out.images=Array.from(new Set(out.images.filter(Boolean))).slice(0,20);
          return JSON.stringify(out);
        })();
    """.trimIndent()

    /** Fait défiler jusqu'en bas (chargement des annonces suivantes) et renvoie le nombre d'annonces affichées. */
    val scrollAndCount: String = """
        (function(){
          window.scrollTo(0,document.body.scrollHeight);
          const ids=new Set(Array.from(document.querySelectorAll('a[href*="/items/"]')).map(a=>(a.href.match(/\/items\/(\d+)/)||[])[1]).filter(Boolean));
          return String(ids.size);
        })();
    """.trimIndent()

    /** Appuie sur le filtre du dressing dont le texte est exactement [label] (« vendus », « actifs »). */
    fun clickFilter(label: String): String = """
        (function(l){
          const els=Array.from(document.querySelectorAll('button,[role="button"],[role="tab"],a,label,span'));
          const e=els.find(x=>(x.innerText||'').trim().toLowerCase()===l);
          if(!e)return 'false';
          (e.closest('button,[role="button"],[role="tab"],a,label')||e).click();
          window.scrollTo(0,0);
          return 'true';
        })(${JSONObject.quote(label.lowercase())});
    """.trimIndent()

    /** evaluateJavascript renvoie la valeur encodée en JSON (une chaîne entre guillemets) : on la décode. */
    fun decode(result: String?): String? {
        if (result == null) return null
        return try {
            JSONTokener(result).nextValue() as? String
        } catch (e: Exception) {
            null
        }
    }

    /** Résumé lisible du remplissage. */
    fun fillSummary(decoded: String?): String {
        val json = try { JSONObject(decoded ?: return NOT_FOUND) } catch (e: Exception) { return NOT_FOUND }
        val done = listOf("title" to "titre", "description" to "description", "price" to "prix")
            .filter { json.optBoolean(it.first) }.map { it.second }
        return if (done.isEmpty()) NOT_FOUND else "Rempli : ${done.joinToString(", ")}. Vérifie avant de publier."
    }

    private const val NOT_FOUND =
        "Aucun champ reconnu sur cette page. Ouvre le formulaire de vente, ou lance le diagnostic et transmets-le."
}
