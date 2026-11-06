package fr.vintedassistant.mobile.ui.vinted

import org.json.JSONObject
import org.json.JSONTokener

/**
 * Scripts exécutés dans la page Vinted ouverte par l'utilisateur, uniquement quand il appuie
 * sur un bouton. Ils ne lisent ni mot de passe ni cookie, ne cliquent sur rien et ne publient rien :
 * ils remplissent des champs visibles, ou décrivent la structure de la page (sans les valeurs saisies).
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
          const out={url:location.href,title:'',price:'',description:'',brand:'',size:'',condition:'',color:'',favorites:'',views:'',images:[]};
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
          const body=document.body.innerText||'';
          out.favorites=(body.match(/(\d+)\s*favori/i)||['',''])[1];out.views=(body.match(/(\d+)\s*vue/i)||['',''])[1];
          const id=(location.pathname.match(/\/items\/(\d+)/)||[])[1];
          Array.from(document.querySelectorAll('img')).forEach(i=>{
            const u=i.currentSrc||i.src;if(!u||!/vinted\.net/.test(u)||(i.naturalWidth||0)<400)return;
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
