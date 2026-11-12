package com.senzwh.deepharness

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// vc218 — Generator Dokumen/PPT: writer OOXML murni (Kotlin stdlib +
// java.util.zip saja, tanpa library docx/pptx eksternal). Format .docx /
// .pptx pada dasarnya ZIP + XML, jadi writer ini membangun part-part
// minimal yang valid langsung dari string XML.
object DocGen {

    data class DocTable(val headers: List<String>, val rows: List<List<String>>)
    data class DocSection(
        val heading: String,
        val level: Int,
        val paragraphs: List<String>,
        val bullets: List<String>,
        val numbered: List<String>,
        val table: DocTable?
    )
    data class PptSlide(val title: String, val bullets: List<String>)

    fun documentsDir(): File {
        val d = File("/sdcard/Harness/documents")
        try { d.mkdirs() } catch (e: Exception) { }
        return d
    }

    // Label sampul template v1: HANYA subjudul sampul, bukan kerangka isi
    // (supaya template tidak mengarang fakta). Template tak dikenal -> "".
    fun templateLabel(template: String): String {
        return when (template.trim().lowercase()) {
            "laporan" -> "Laporan"
            "proposal" -> "Proposal"
            "materi" -> "Materi Belajar"
            else -> ""
        }
    }

    fun sanitizeBase(raw: String, fallback: String): String {
        var s = raw.trim().replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('.', '-')
        while (s.contains("--")) s = s.replace("--", "-")
        if (s.isBlank()) s = fallback
        if (s.length > 80) s = s.substring(0, 80).trim('-')
        if (s.isBlank()) s = fallback
        return s
    }

    // Nama unik: tidak pernah menimpa — file yang ada digeser ke -2, -3, ...
    fun uniqueFile(dir: File, base: String, ext: String): File {
        var f = File(dir, base + "." + ext)
        if (!f.exists()) return f
        var i = 2
        while (true) {
            f = File(dir, base + "-" + i + "." + ext)
            if (!f.exists()) return f
            i++
        }
    }

    private fun esc(s: String): String {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    }

    private fun writeZip(path: File, parts: List<Pair<String, String>>) {
        FileOutputStream(path).use { fos ->
            ZipOutputStream(fos).use { zos ->
                for (part in parts) {
                    zos.putNextEntry(ZipEntry(part.first))
                    zos.write(part.second.toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                }
            }
        }
    }

    // ---------------- DOCX ----------------

    private fun docxPara(text: String): String {
        return "<w:p><w:r><w:t xml:space=\"preserve\">" + esc(text) + "</w:t></w:r></w:p>"
    }

    private fun docxHeading(text: String, level: Int): String {
        val lv = level.coerceIn(1, 3)
        return "<w:p><w:pPr><w:pStyle w:val=\"Heading" + lv + "\"/></w:pPr><w:r><w:t xml:space=\"preserve\">" + esc(text) + "</w:t></w:r></w:p>"
    }

    private fun docxListItem(text: String, numId: Int): String {
        return "<w:p><w:pPr><w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"" + numId + "\"/></w:numPr></w:pPr><w:r><w:t xml:space=\"preserve\">" + esc(text) + "</w:t></w:r></w:p>"
    }

    private fun docxTableCell(text: String, bold: Boolean): String {
        val b = if (bold) "<w:b/>" else ""
        val shd = if (bold) "<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"D9D9D9\"/>" else ""
        return "<w:tc><w:tcPr>" + shd + "</w:tcPr><w:p><w:r><w:rPr>" + b + "</w:rPr><w:t xml:space=\"preserve\">" + esc(text) + "</w:t></w:r></w:p></w:tc>"
    }

    private fun docxTable(t: DocTable): String {
        val sb = StringBuilder()
        sb.append("<w:tbl><w:tblPr><w:tblW w:w=\"0\" w:type=\"auto\"/>")
        sb.append("<w:tblBorders><w:top w:val=\"single\" w:sz=\"4\" w:color=\"808080\"/><w:left w:val=\"single\" w:sz=\"4\" w:color=\"808080\"/><w:bottom w:val=\"single\" w:sz=\"4\" w:color=\"808080\"/><w:right w:val=\"single\" w:sz=\"4\" w:color=\"808080\"/><w:insideH w:val=\"single\" w:sz=\"4\" w:color=\"808080\"/><w:insideV w:val=\"single\" w:sz=\"4\" w:color=\"808080\"/></w:tblBorders></w:tblPr>")
        val cols = t.headers.size
        // vc246 GELOMBANG 5 (FT-m11): lebar kolom otomatis dari isi
        // terpanjang per kolom (dulu lebar tetap 2000 sehingga tabel
        // meluber / kolom sempit). Satuan twips, dijepit 900..5000.
        val colW = IntArray(cols) { c ->
            var mx = if (c < t.headers.size) t.headers[c].length else 0
            for (row in t.rows) if (c < row.size) mx = maxOf(mx, row[c].length)
            (mx * 110).coerceIn(900, 5000)
        }
        sb.append("<w:tblGrid>")
        for (i in 0 until cols) sb.append("<w:gridCol w:w=\"" + colW[i] + "\"/>")
        sb.append("</w:tblGrid>")
        sb.append("<w:tr>")
        for (h in t.headers) sb.append(docxTableCell(h, true))
        sb.append("</w:tr>")
        for (row in t.rows) {
            sb.append("<w:tr>")
            for (c in 0 until cols) {
                val v = if (c < row.size) row[c] else ""
                sb.append(docxTableCell(v, false))
            }
            sb.append("</w:tr>")
        }
        sb.append("</w:tbl>")
        return sb.toString()
    }

    fun buildDocx(title: String, subtitle: String, sections: List<DocSection>): ByteArray {
        val body = StringBuilder()
        body.append("<w:p><w:pPr><w:pStyle w:val=\"Title\"/><w:jc w:val=\"center\"/></w:pPr><w:r><w:rPr><w:sz w:val=\"56\"/><w:b/></w:rPr><w:t xml:space=\"preserve\">" + esc(title) + "</w:t></w:r></w:p>")
        if (subtitle.isNotBlank()) {
            body.append("<w:p><w:pPr><w:jc w:val=\"center\"/></w:pPr><w:r><w:rPr><w:sz w:val=\"28\"/><w:i/><w:color w:val=\"595959\"/></w:rPr><w:t xml:space=\"preserve\">" + esc(subtitle) + "</w:t></w:r></w:p>")
        }
        for (sec in sections) {
            if (sec.heading.isNotBlank()) body.append(docxHeading(sec.heading, sec.level))
            for (p in sec.paragraphs) body.append(docxPara(p))
            for (b in sec.bullets) body.append(docxListItem(b, 1))
            for (n in sec.numbered) body.append(docxListItem(n, 2))
            val t = sec.table
            if (t != null && t.headers.isNotEmpty()) body.append(docxTable(t))
        }
        val document = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>" +
            body.toString() + "</w:body></w:document>"
        val styles = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
            "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\"/><w:sz w:val=\"22\"/></w:rPr></w:rPrDefault></w:docDefaults>" +
            "<w:style w:type=\"paragraph\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:qFormat/></w:style>" +
            "<w:style w:type=\"paragraph\" w:styleId=\"Title\"><w:name w:val=\"Title\"/><w:basedOn w:val=\"Normal\"/><w:qFormat/><w:pPr><w:jc w:val=\"center\"/></w:pPr><w:rPr><w:sz w:val=\"56\"/><w:b/></w:rPr></w:style>" +
            "<w:style w:type=\"paragraph\" w:styleId=\"Heading1\"><w:name w:val=\"heading 1\"/><w:basedOn w:val=\"Normal\"/><w:qFormat/><w:pPr><w:keepNext/><w:spacing w:before=\"240\" w:after=\"120\"/></w:pPr><w:rPr><w:sz w:val=\"32\"/><w:b/><w:color w:val=\"262626\"/></w:rPr></w:style>" +
            "<w:style w:type=\"paragraph\" w:styleId=\"Heading2\"><w:name w:val=\"heading 2\"/><w:basedOn w:val=\"Normal\"/><w:qFormat/><w:pPr><w:keepNext/><w:spacing w:before=\"200\" w:after=\"100\"/></w:pPr><w:rPr><w:sz w:val=\"28\"/><w:b/><w:color w:val=\"262626\"/></w:rPr></w:style>" +
            "<w:style w:type=\"paragraph\" w:styleId=\"Heading3\"><w:name w:val=\"heading 3\"/><w:basedOn w:val=\"Normal\"/><w:qFormat/><w:pPr><w:keepNext/><w:spacing w:before=\"160\" w:after=\"80\"/></w:pPr><w:rPr><w:sz w:val=\"24\"/><w:b/><w:color w:val=\"262626\"/></w:rPr></w:style>" +
            "</w:styles>"
        val numbering = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<w:numbering xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
            "<w:abstractNum w:abstractNumId=\"0\"><w:lvl w:ilvl=\"0\"><w:start w:val=\"1\"/><w:numFmt w:val=\"bullet\"/><w:lvlText w:val=\"\u2022\"/><w:lvlJc w:val=\"left\"/><w:pPr><w:ind w:left=\"720\" w:hanging=\"360\"/></w:pPr></w:lvl></w:abstractNum>" +
            "<w:abstractNum w:abstractNumId=\"1\"><w:lvl w:ilvl=\"0\"><w:start w:val=\"1\"/><w:numFmt w:val=\"decimal\"/><w:lvlText w:val=\"%1.\"/><w:lvlJc w:val=\"left\"/><w:pPr><w:ind w:left=\"720\" w:hanging=\"360\"/></w:pPr></w:lvl></w:abstractNum>" +
            "<w:num w:numId=\"1\"><w:abstractNumId w:val=\"0\"/></w:num>" +
            "<w:num w:numId=\"2\"><w:abstractNumId w:val=\"1\"/></w:num>" +
            "</w:numbering>"
        val contentTypes = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
            "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>" +
            "<Override PartName=\"/word/numbering.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml\"/>" +
            "</Types>"
        val rels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>" +
            "</Relationships>"
        val docRels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>" +
            "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/numbering\" Target=\"numbering.xml\"/>" +
            "</Relationships>"
        val tmp = File.createTempFile("docx", ".tmp")
        try {
            writeZip(tmp, listOf(
                "[Content_Types].xml" to contentTypes,
                "_rels/.rels" to rels,
                "word/document.xml" to document,
                "word/styles.xml" to styles,
                "word/numbering.xml" to numbering,
                "word/_rels/document.xml.rels" to docRels
            ))
            return tmp.readBytes()
        } finally {
            try { tmp.delete() } catch (e: Exception) { }
        }
    }

    // ---------------- PPTX ----------------

    private fun pptxTextRun(text: String, size: Int, bold: Boolean): String {
        val b = if (bold) " b=\"1\"" else ""
        return "<a:r><a:rPr lang=\"id-ID\" sz=\"" + (size * 100) + "\"" + b + "/><a:t>" + esc(text) + "</a:t></a:r>"
    }

    private fun pptxPara(text: String, size: Int, bold: Boolean, bullet: Boolean, align: String): String {
        val sb = StringBuilder()
        sb.append("<a:p><a:pPr algn=\"" + align + "\">")
        if (bullet) sb.append("<a:buChar char=\"\u2022\"/><a:buFont typeface=\"Arial\"/>")
        else sb.append("<a:buNone/>")
        sb.append("</a:pPr>")
        if (text.isNotEmpty()) sb.append(pptxTextRun(text, size, bold))
        sb.append("</a:p>")
        return sb.toString()
    }

    private fun pptxShape(id: Int, name: String, x: Int, y: Int, w: Int, h: Int, paras: String): String {
        return "<p:sp><p:nvSpPr><p:cNvPr id=\"" + id + "\" name=\"" + name + "\"/><p:cNvSpPr txBox=\"1\"/><p:nvPr/></p:nvSpPr>" +
            "<p:spPr><a:xfrm><a:off x=\"" + x + "\" y=\"" + y + "\"/><a:ext cx=\"" + w + "\" cy=\"" + h + "\"/></a:xfrm>" +
            "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr>" +
            "<p:txBody><a:bodyPr wrap=\"square\"/><a:lstStyle/>" + paras + "</p:txBody></p:sp>"
    }

    private fun pptxSlide(title: String, subtitle: String, bullets: List<String>): String {
        val titleParas = pptxPara(title, 32, true, false, "l") +
            if (subtitle.isNotBlank()) pptxPara(subtitle, 20, false, false, "l") else ""
        val bodyParas = StringBuilder()
        if (bullets.isEmpty()) bodyParas.append("<a:p/>")
        for (b in bullets) bodyParas.append(pptxPara(b, 20, false, true, "l"))
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<p:sld xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\">" +
            "<p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>" +
            "<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>" +
            pptxShape(2, "Title", 457200, 274638, 11277600, 1097280, titleParas) +
            pptxShape(3, "Body", 457200, 1600200, 11277600, 4572000, bodyParas.toString()) +
            "</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>"
    }

    fun buildPptx(title: String, subtitle: String, slides: List<PptSlide>, closing: String): ByteArray {
        val parts = ArrayList<Pair<String, String>>()
        val slideCount = 1 + slides.size + if (closing.isNotBlank()) 1 else 0
        val ct = StringBuilder()
        ct.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
        ct.append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
        ct.append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
        ct.append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
        ct.append("<Override PartName=\"/ppt/presentation.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml\"/>")
        for (i in 1..slideCount) ct.append("<Override PartName=\"/ppt/slides/slide" + i + ".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>")
        ct.append("<Override PartName=\"/ppt/slideLayouts/slideLayout1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml\"/>")
        ct.append("<Override PartName=\"/ppt/slideMasters/slideMaster1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml\"/>")
        ct.append("<Override PartName=\"/ppt/theme/theme1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.theme+xml\"/>")
        ct.append("</Types>")
        parts.add("[Content_Types].xml" to ct.toString())
        parts.add("_rels/.rels" to "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"ppt/presentation.xml\"/>" +
            "</Relationships>")
        val pres = StringBuilder()
        pres.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
        pres.append("<p:presentation xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\">")
        pres.append("<p:sldMasterIdLst><p:sldMasterId id=\"2147483648\" r:id=\"rId1\"/></p:sldMasterIdLst>")
        pres.append("<p:sldIdLst>")
        for (i in 1..slideCount) pres.append("<p:sldId id=\"" + (255 + i) + "\" r:id=\"rId" + (1 + i) + "\"/>")
        pres.append("</p:sldIdLst>")
        pres.append("<p:sldSz cx=\"12192000\" cy=\"6858000\"/><p:notesSz cx=\"6858000\" cy=\"9144000\"/></p:presentation>")
        parts.add("ppt/presentation.xml" to pres.toString())
        val presRels = StringBuilder()
        presRels.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
        presRels.append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
        presRels.append("<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster\" Target=\"slideMasters/slideMaster1.xml\"/>")
        for (i in 1..slideCount) presRels.append("<Relationship Id=\"rId" + (1 + i) + "\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide\" Target=\"slides/slide" + i + ".xml\"/>")
        presRels.append("</Relationships>")
        parts.add("ppt/_rels/presentation.xml.rels" to presRels.toString())
        var idx = 0
        idx++
        parts.add("ppt/slides/slide" + idx + ".xml" to pptxSlide(title, subtitle, emptyList()))
        parts.add("ppt/slides/_rels/slide" + idx + ".xml.rels" to "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/>" +
            "</Relationships>")
        for (s in slides) {
            idx++
            parts.add("ppt/slides/slide" + idx + ".xml" to pptxSlide(s.title, "", s.bullets))
            parts.add("ppt/slides/_rels/slide" + idx + ".xml.rels" to "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/>" +
                "</Relationships>")
        }
        if (closing.isNotBlank()) {
            idx++
            parts.add("ppt/slides/slide" + idx + ".xml" to pptxSlide(closing, "", emptyList()))
            parts.add("ppt/slides/_rels/slide" + idx + ".xml.rels" to "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/>" +
                "</Relationships>")
        }
        parts.add("ppt/slideLayouts/slideLayout1.xml" to "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<p:sldLayout xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\" type=\"blank\">" +
            "<p:cSld name=\"Blank\"><p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>" +
            "<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr></p:spTree></p:cSld>" +
            "<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>")
        parts.add("ppt/slideLayouts/_rels/slideLayout1.xml.rels" to "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster\" Target=\"../slideMasters/slideMaster1.xml\"/>" +
            "</Relationships>")
        parts.add("ppt/slideMasters/slideMaster1.xml" to "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<p:sldMaster xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\">" +
            "<p:cSld><p:bg><p:bgPr><a:solidFill><a:srgbClr val=\"FFFFFF\"/></a:solidFill><a:effectLst/></p:bgPr></p:bg>" +
            "<p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>" +
            "<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr></p:spTree></p:cSld>" +
            "<p:clrMap bg1=\"lt1\" tx1=\"dk1\" bg2=\"lt2\" tx2=\"dk2\" accent1=\"accent1\" accent2=\"accent2\" accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" hlink=\"hlink\" folHlink=\"folHlink\"/>" +
            "<p:sldLayoutIdLst><p:sldLayoutId id=\"2147483649\" r:id=\"rId1\"/></p:sldLayoutIdLst>" +
            "<p:txStyles><p:titleStyle><a:lvl1pPr algn=\"l\"><a:defRPr sz=\"3200\"/></a:lvl1pPr></p:titleStyle>" +
            "<p:bodyStyle><a:lvl1pPr marL=\"342900\" indent=\"-342900\"><a:buChar char=\"\u2022\"/></a:lvl1pPr></p:bodyStyle>" +
            "<p:otherStyle><a:lvl1pPr><a:defRPr sz=\"1800\"/></a:lvl1pPr></p:otherStyle></p:txStyles></p:sldMaster>")
        parts.add("ppt/slideMasters/_rels/slideMaster1.xml.rels" to "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/>" +
            "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme\" Target=\"../theme/theme1.xml\"/>" +
            "</Relationships>")
        parts.add("ppt/theme/theme1.xml" to "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<a:theme xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" name=\"Harness\">" +
            "<a:themeElements><a:clrScheme name=\"Harness\">" +
            "<a:dk1><a:sysClr val=\"windowText\" lastClr=\"000000\"/></a:dk1><a:lt1><a:sysClr val=\"window\" lastClr=\"FFFFFF\"/></a:lt1>" +
            "<a:dk2><a:srgbClr val=\"1F1F1F\"/></a:dk2><a:lt2><a:srgbClr val=\"F2F2F2\"/></a:lt2>" +
            "<a:accent1><a:srgbClr val=\"4472C4\"/></a:accent1><a:accent2><a:srgbClr val=\"ED7D31\"/></a:accent2>" +
            "<a:accent3><a:srgbClr val=\"A5A5A5\"/></a:accent3><a:accent4><a:srgbClr val=\"FFC000\"/></a:accent4>" +
            "<a:accent5><a:srgbClr val=\"5B9BD5\"/></a:accent5><a:accent6><a:srgbClr val=\"70AD47\"/></a:accent6>" +
            "<a:hlink><a:srgbClr val=\"0563C1\"/></a:hlink><a:folHlink><a:srgbClr val=\"954F72\"/></a:folHlink></a:clrScheme>" +
            "<a:fontScheme name=\"Harness\"><a:majorFont><a:latin typeface=\"Calibri\"/></a:majorFont><a:minorFont><a:latin typeface=\"Calibri\"/></a:minorFont></a:fontScheme>" +
            "<a:fmtScheme name=\"Harness\"><a:fillStyleLst><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:fillStyleLst>" +
            "<a:lnStyleLst><a:ln w=\"19050\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:ln></a:lnStyleLst>" +
            "<a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst>" +
            "<a:bgFillStyleLst><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:bgFillStyleLst></a:fmtScheme>" +
            "</a:themeElements></a:theme>")
        val tmp = File.createTempFile("pptx", ".tmp")
        try {
            writeZip(tmp, parts)
            return tmp.readBytes()
        } finally {
            try { tmp.delete() } catch (e: Exception) { }
        }
    }
}
