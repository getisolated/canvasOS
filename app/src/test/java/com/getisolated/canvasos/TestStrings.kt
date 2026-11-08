package com.getisolated.canvasos

import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** Local unit tests see android.jar's stubs, not Resources: [str] and [plural] load this object and read the French values/strings.xml. */
object TestStrings {
    private val strings = HashMap<String, String>()
    private val plurals = HashMap<String, Map<String, String>>()

    init {
        val file = listOf("src/main/res/values/strings.xml", "app/src/main/res/values/strings.xml").map(::File).first { it.isFile }
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val s = doc.getElementsByTagName("string")
        for (i in 0 until s.length) (s.item(i) as Element).let { strings[it.getAttribute("name")] = unescape(it.textContent) }
        val p = doc.getElementsByTagName("plurals")
        for (i in 0 until p.length) {
            val e = p.item(i) as Element
            val items = e.getElementsByTagName("item")
            plurals[e.getAttribute("name")] = (0 until items.length).associate {
                (items.item(it) as Element).let { item -> item.getAttribute("quantity") to unescape(item.textContent) }
            }
        }
        Strings.stub = { id, count, args ->
            val text = if (count == null) strings.getValue(name("string", id))
            else plurals.getValue(name("plurals", id)).let { it[if (count <= 1) "one" else "other"] ?: it.getValue("other") }
            if (args.isEmpty()) text else String.format(Locale.FRANCE, text, *args)
        }
    }

    private fun name(type: String, id: Int) = Class.forName("com.getisolated.canvasos.R\$$type").fields.first { it.getInt(null) == id }.name

    private fun unescape(s: String) = s.trim('"').replace("\\'", "'").replace("\\\"", "\"").replace("\\n", "\n").replace("\\@", "@").replace("\\?", "?")
}
