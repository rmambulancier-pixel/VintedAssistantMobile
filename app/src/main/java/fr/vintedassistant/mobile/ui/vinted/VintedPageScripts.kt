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

    /** evaluateJavascript renvoie la valeur encodée en JSON (une chaîne entre guillemets) : on la décode. */
    fun decode(result: String?): String? = try {
        (JSONTokener(result ?: return null).nextValue() as? String)
    } catch (e: Exception) {
        null
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
