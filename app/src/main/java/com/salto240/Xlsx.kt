package com.salto240

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Escritura y lectura mínimas de archivos de Excel (.xlsx), sin bibliotecas externas. */
object Xlsx {

    class Sheet(val name: String, val rows: List<List<Any?>>, val widths: List<Int> = emptyList())

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        .filter { it == '\n' || it == '\t' || it >= ' ' }

    private fun col(i: Int): String {
        var n = i + 1; val sb = StringBuilder()
        while (n > 0) { val r = (n - 1) % 26; sb.insert(0, ('A' + r)); n = (n - 1) / 26 }
        return sb.toString()
    }

    /** Escribe el libro. La primera fila de cada hoja va en negrita e inmovilizada. */
    fun write(out: OutputStream, sheets: List<Sheet>) {
        ZipOutputStream(out.buffered()).use { z ->
            fun put(name: String, body: String) {
                z.putNextEntry(ZipEntry(name)); z.write(body.toByteArray(Charsets.UTF_8)); z.closeEntry()
            }
            put("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
${sheets.indices.joinToString("\n") { """<Override PartName="/xl/worksheets/sheet${it + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""" }}
</Types>""")
            put("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>""")
            put("xl/workbook.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<sheets>${sheets.withIndex().joinToString("") { (i, s) -> """<sheet name="${esc(s.name.replace(Regex("[\\\\/?*\\[\\]:]"), " ").take(31))}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""" }}</sheets>
</workbook>""")
            put("xl/_rels/workbook.xml.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
${sheets.indices.joinToString("\n") { """<Relationship Id="rId${it + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${it + 1}.xml"/>""" }}
<Relationship Id="rId${sheets.size + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>""")
            put("xl/styles.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="Calibri"/></font></fonts>
<fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF1E4F8C"/></patternFill></fill></fills>
<borders count="1"><border/></borders>
<cellStyleXfs count="1"><xf/></cellStyleXfs>
<cellXfs count="2"><xf xfId="0"/><xf xfId="0" fontId="1" fillId="2" applyFont="1" applyFill="1"/></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
</styleSheet>""")
            sheets.forEachIndexed { si, s ->
                val sb = StringBuilder("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>""")
                val nCols = s.rows.maxOfOrNull { it.size } ?: 0
                if (nCols > 0) {
                    sb.append("<cols>")
                    for (c in 0 until nCols) sb.append("""<col min="${c + 1}" max="${c + 1}" width="${s.widths.getOrNull(c) ?: 14}" customWidth="1"/>""")
                    sb.append("</cols>")
                }
                sb.append("<sheetData>")
                s.rows.forEachIndexed { r, row ->
                    sb.append("""<row r="${r + 1}">""")
                    row.forEachIndexed { c, v ->
                        val ref = col(c) + (r + 1)
                        val style = if (r == 0) " s=\"1\"" else ""
                        when (v) {
                            null -> {}
                            is Number -> if (v.toDouble().isFinite()) sb.append("""<c r="$ref"$style><v>$v</v></c>""")
                            else -> sb.append("""<c r="$ref" t="inlineStr"$style><is><t xml:space="preserve">${esc(v.toString())}</t></is></c>""")
                        }
                    }
                    sb.append("</row>")
                }
                sb.append("</sheetData></worksheet>")
                put("xl/worksheets/sheet${si + 1}.xml", sb.toString())
            }
        }
    }

    /** Lee la primera hoja como filas de texto. Los números se devuelven como texto ("72.5"). */
    fun readFirstSheet(inp: InputStream): List<List<String>> {
        val files = HashMap<String, ByteArray>()
        ZipInputStream(inp.buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.name == "xl/sharedStrings.xml" || e.name.startsWith("xl/worksheets/sheet") || e.name == "xl/workbook.xml" ||
                    e.name == "xl/_rels/workbook.xml.rels") files[e.name] = z.readBytes()
            }
        }
        val shared = ArrayList<String>()
        files["xl/sharedStrings.xml"]?.let { b ->
            val p = parser(b); var cur: StringBuilder? = null; var inT = false
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                when (p.eventType) {
                    XmlPullParser.START_TAG -> when (p.name) { "si" -> cur = StringBuilder(); "t" -> inT = true }
                    XmlPullParser.TEXT -> if (inT) cur?.append(p.text)
                    XmlPullParser.END_TAG -> when (p.name) { "t" -> inT = false; "si" -> { shared.add(cur?.toString() ?: ""); cur = null } }
                }
            }
        }
        // Primera hoja del libro (por orden), o sheet1.xml.
        var target = "xl/worksheets/sheet1.xml"
        try {
            val wb = files["xl/workbook.xml"]; val rels = files["xl/_rels/workbook.xml.rels"]
            if (wb != null && rels != null) {
                var rid: String? = null
                val p = parser(wb)
                while (p.next() != XmlPullParser.END_DOCUMENT && rid == null)
                    if (p.eventType == XmlPullParser.START_TAG && p.name == "sheet")
                        rid = (0 until p.attributeCount).firstOrNull { p.getAttributeName(it).endsWith("id") && p.getAttributeName(it) != "sheetId" }?.let { p.getAttributeValue(it) }
                val q = parser(rels)
                while (q.next() != XmlPullParser.END_DOCUMENT)
                    if (q.eventType == XmlPullParser.START_TAG && q.name == "Relationship" && q.getAttributeValue(null, "Id") == rid)
                        target = "xl/" + q.getAttributeValue(null, "Target").removePrefix("/xl/").removePrefix("./")
            }
        } catch (_: Exception) {}
        val sheet = files[target] ?: files.keys.filter { it.startsWith("xl/worksheets/sheet") }.minOrNull()?.let { files[it] } ?: return emptyList()
        val rows = ArrayList<List<String>>()
        val p = parser(sheet)
        var row: MutableMap<Int, String>? = null
        var ref = ""; var type = ""; var value = StringBuilder(); var inV = false
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "row" -> row = HashMap()
                    "c" -> { ref = p.getAttributeValue(null, "r") ?: ""; type = p.getAttributeValue(null, "t") ?: ""; value = StringBuilder() }
                    "v", "t" -> inV = true
                }
                XmlPullParser.TEXT -> if (inV) value.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "v", "t" -> inV = false
                    "c" -> {
                        val raw = value.toString()
                        val v = if (type == "s") shared.getOrNull(raw.toIntOrNull() ?: -1) ?: "" else raw
                        val letters = ref.takeWhile { it.isLetter() }
                        var idx = 0; for (ch in letters) idx = idx * 26 + (ch.uppercaseChar() - 'A' + 1)
                        row?.put(if (letters.isEmpty()) (row?.size ?: 0) else idx - 1, v)
                    }
                    "row" -> {
                        val r = row ?: continue
                        val n = (r.keys.maxOrNull() ?: -1) + 1
                        rows.add((0 until n).map { r[it] ?: "" })
                        row = null
                    }
                }
            }
        }
        return rows
    }

    private fun parser(b: ByteArray): XmlPullParser = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        setInput(ByteArrayInputStream(b), "UTF-8")
    }
}
