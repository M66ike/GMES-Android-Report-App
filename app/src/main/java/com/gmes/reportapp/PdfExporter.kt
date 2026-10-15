package com.gmes.reportapp

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

object PdfExporter {
    private const val PORTRAIT_W = 595
    private const val PORTRAIT_H = 842
    private const val LANDSCAPE_W = 842
    private const val LANDSCAPE_H = 595
    private const val BLOCK_SIZE = 37
    private const val INSPECTION_PAGE_ROWS = 20

    private val teal = Color.rgb(0, 105, 92)
    private val darkTeal = Color.rgb(0, 92, 82)
    private val lightGrey = Color.rgb(224, 224, 224)
    private val midGrey = Color.rgb(178, 178, 178)
    private val textGrey = Color.rgb(40, 40, 40)
    private val red = Color.rgb(220, 35, 35)
    private val decimal = DecimalFormat("0.###")

    private const val NOTICE = "NOTICE - The specialist testing of the earth installation was undertaken by Group Management. Should any alteration or addition be made to the installation, the testing will require to be repeated to verify that the installation remains compliant with standards. For conductor sizes, please refer to the EBB chart."
    private const val PERIODIC = "Periodic Inspection and Testing - Annual measurements should be carried out to verify that the resistance of the supplementary equipotential bonding is within the limits as required by BS 7671."
    private const val CONTACT = "Group Management Electrical Surveys Ltd can be contacted at   T: 0845 226 7484     F: 0845 266 7485    W: groupmanagement.com"

    fun createAndSave(
        context: Context,
        report: ReportEntity,
        details: SupplyCriteriaEntity?,
        rows: List<DataRecordRowEntity>,
        inspections: List<InspectionRowEntity>
    ): Pair<Uri, String> {
        val data = rows.filterNot { it.isBlank() }
        val defects = inspections.filterNot { it.isBlank() }
        val filename = buildFilename(report)
        val pdf = PdfDocument()
        var pageNo = 1

        val blocks = if (data.isEmpty()) listOf(emptyList()) else data.chunked(BLOCK_SIZE)

        blocks.forEachIndexed { index, block ->
            val page = pdf.startPage(
                PdfDocument.PageInfo.Builder(PORTRAIT_W, PORTRAIT_H, pageNo++).create()
            )
            if (index == 0) {
                drawDataRecordPage(page.canvas, report, details, block, 1)
            } else {
                drawContinuationPage(page.canvas, report, block, index * BLOCK_SIZE + 1)
            }
            pdf.finishPage(page)
        }

        blocks.forEachIndexed { index, block ->
            val page = pdf.startPage(
                PdfDocument.PageInfo.Builder(LANDSCAPE_W, LANDSCAPE_H, pageNo++).create()
            )
            drawMatrixPage(page.canvas, report, block, index * BLOCK_SIZE + 1)
            pdf.finishPage(page)
        }

        val inspectionPages = if (defects.isEmpty()) listOf(emptyList()) else defects.chunked(INSPECTION_PAGE_ROWS)
        inspectionPages.forEachIndexed { index, block ->
            val page = pdf.startPage(
                PdfDocument.PageInfo.Builder(PORTRAIT_W, PORTRAIT_H, pageNo++).create()
            )
            drawInspectionPage(page.canvas, report, block, index * INSPECTION_PAGE_ROWS + 1)
            pdf.finishPage(page)
        }

        val uri = savePdf(context, pdf, filename)
        pdf.close()
        return uri to filename
    }

    private fun drawDataRecordPage(
        canvas: android.graphics.Canvas,
        report: ReportEntity,
        details: SupplyCriteriaEntity?,
        rows: List<DataRecordRowEntity>,
        startNumber: Int
    ) {
        drawOuterBorder(canvas, PORTRAIT_W, PORTRAIT_H)
        drawHeader(canvas, PORTRAIT_W, "Medical Location Data Record Sheet", report)

        val left = 10f
        val right = PORTRAIT_W - 10f
        val line = strokePaint(Color.BLACK, 0.8f)
        val label = textPaint(7.0f, bold = true)
        val value = textPaint(7.0f)

        var y = 112f
        drawCell(canvas, left, y, 145f, 17f, "Test Equipment", label, line, align = Paint.Align.LEFT)
        drawCell(canvas, 155f, y, right - 155f, 17f, details?.testEquipment.orEmpty(), value, line, align = Paint.Align.LEFT)
        y += 17f
        drawCell(canvas, left, y, 145f, 17f, "ERB Location", label, line, align = Paint.Align.LEFT)
        drawCell(canvas, 155f, y, right - 155f, 17f, details?.erbLocation.orEmpty(), value, line, align = Paint.Align.LEFT)
        y += 20f

        val testWidths = floatArrayOf(155f, 155f, right - left - 310f)
        val testHeaders = listOf("TEST DETAILS", "DATE OF TEST", "REQUESTED BY")
        val testValues = listOf(
            details?.testDetails.orEmpty(),
            details?.dateOfTest.orEmpty().ifBlank { report.date },
            details?.requestedBy.orEmpty()
        )
        var x = left
        for (i in testWidths.indices) {
            drawCell(canvas, x, y, testWidths[i], 17f, testHeaders[i], label, line, align = Paint.Align.CENTER)
            drawCell(canvas, x, y + 17f, testWidths[i], 17f, testValues[i], value, line, align = Paint.Align.CENTER)
            x += testWidths[i]
        }
        y += 39f

        val circuitWidths = floatArrayOf(142f, 142f, 142f, 78f, right - left - 504f)
        val circuitHeaders = listOf("TN-S Circuits", "DC Sensitive Device Yes / No", "UPS/IPS Circuits", "MCB Type", "MCB Size")
        val circuitValues = listOf(
            details?.tnSCircuits.orEmpty(),
            details?.dcSensitiveDevice.orEmpty(),
            details?.upsIpsCircuits.orEmpty(),
            details?.mcbType.orEmpty(),
            details?.mcbSize.orEmpty()
        )
        x = left
        for (i in circuitWidths.indices) {
            drawCell(canvas, x, y, circuitWidths[i], 17f, circuitHeaders[i], label, line, align = Paint.Align.CENTER)
            drawCell(canvas, x, y + 17f, circuitWidths[i], 17f, circuitValues[i], value, line, align = Paint.Align.CENTER)
            for (blank in 0 until 4) {
                drawCell(canvas, x, y + 34f + blank * 13f, circuitWidths[i], 13f, "", value, line)
            }
            x += circuitWidths[i]
        }
        y += 91f

        drawDataTable(canvas, rows, startNumber, y, PORTRAIT_H - 72f)
        drawFooter(canvas, PORTRAIT_W, PORTRAIT_H)
    }

    private fun drawContinuationPage(
        canvas: android.graphics.Canvas,
        report: ReportEntity,
        rows: List<DataRecordRowEntity>,
        startNumber: Int
    ) {
        drawOuterBorder(canvas, PORTRAIT_W, PORTRAIT_H)
        drawHeader(canvas, PORTRAIT_W, "Medical Location Data Record Sheet", report)
        drawDataTable(canvas, rows, startNumber, 126f, PORTRAIT_H - 72f)
        drawFooter(canvas, PORTRAIT_W, PORTRAIT_H)
    }

    private fun drawDataTable(
        canvas: android.graphics.Canvas,
        rows: List<DataRecordRowEntity>,
        startNumber: Int,
        top: Float,
        bottom: Float
    ) {
        val left = 10f
        val widths = floatArrayOf(45f, 360f, 65f, PORTRAIT_W - 20f - 470f)
        val headerH = 31f
        val rowH = (bottom - top - headerH) / BLOCK_SIZE
        val line = strokePaint(Color.BLACK, 0.7f)
        val header = textPaint(7.4f, bold = true)
        val body = textPaint(6.6f)

        var x = left
        val headers = listOf(
            "Item",
            "Test Item Recorded Clockwise from Double Door",
            "Connected\nY/N",
            "Continuity Reading to ERB (Ω)\nI = Insulated >500MΩ"
        )
        widths.forEachIndexed { index, width ->
            drawMultilineCell(canvas, x, top, width, headerH, headers[index], header, line, Paint.Align.CENTER)
            x += width
        }

        for (i in 0 until BLOCK_SIZE) {
            val rowTop = top + headerH + i * rowH
            val row = rows.getOrNull(i)
            val number = startNumber + i
            x = left
            val values = listOf(
                number.toString(),
                row?.testItem.orEmpty(),
                row?.connected.orEmpty(),
                row?.continuityReading.orEmpty()
            )
            widths.forEachIndexed { index, width ->
                drawCell(
                    canvas,
                    x,
                    rowTop,
                    width,
                    rowH,
                    values[index],
                    body,
                    line,
                    align = if (index == 1) Paint.Align.LEFT else Paint.Align.CENTER,
                    padding = if (index == 1) 3f else 0f
                )
                x += width
            }
        }
    }

    private fun drawMatrixPage(
        canvas: android.graphics.Canvas,
        report: ReportEntity,
        rows: List<DataRecordRowEntity>,
        startNumber: Int
    ) {
        drawOuterBorder(canvas, LANDSCAPE_W, LANDSCAPE_H)
        drawHeader(canvas, LANDSCAPE_W, "Medical Location Data Record Sheet", report, compact = true)

        val legendPaint = Paint().apply { color = teal; style = Paint.Style.FILL }
        canvas.drawRect(40f, 89f, 205f, 111f, legendPaint)
        val legendText = textPaint(9f, Color.WHITE)
        legendText.textAlign = Paint.Align.CENTER
        canvas.drawText("I = Insulated >500MΩ", 122.5f, 104f, legendText)

        val left = 40f
        val top = 122f
        val cols = BLOCK_SIZE + 1
        val gridW = LANDSCAPE_W - 80f
        val gridH = LANDSCAPE_H - top - 64f
        val cellW = gridW / cols
        val cellH = gridH / cols
        val line = strokePaint(Color.BLACK, 0.45f)
        val headerFill = Paint().apply { color = teal; style = Paint.Style.FILL }
        val lowerFill = Paint().apply { color = midGrey; style = Paint.Style.FILL }
        val headerText = textPaint(5.3f, Color.WHITE, bold = true)
        val bodyText = textPaint(5.2f)

        for (r in 0 until cols) {
            for (c in 0 until cols) {
                val x = left + c * cellW
                val y = top + r * cellH
                when {
                    r == 0 || c == 0 -> canvas.drawRect(x, y, x + cellW, y + cellH, headerFill)
                    c <= r -> canvas.drawRect(x, y, x + cellW, y + cellH, lowerFill)
                }
                canvas.drawRect(x, y, x + cellW, y + cellH, line)

                val text = when {
                    r == 0 && c == 0 -> "REF"
                    r == 0 -> (startNumber + c - 1).toString()
                    c == 0 -> (startNumber + r - 1).toString()
                    c <= r -> ""
                    else -> matrixValue(rows.getOrNull(r - 1), rows.getOrNull(c - 1))
                }
                if (text.isNotBlank()) {
                    val p = if (r == 0 || c == 0) headerText else bodyText
                    p.textAlign = Paint.Align.CENTER
                    canvas.drawText(text, x + cellW / 2f, y + cellH * 0.68f, p)
                }
            }
        }
        drawFooter(canvas, LANDSCAPE_W, LANDSCAPE_H)
    }

    private fun drawInspectionPage(
        canvas: android.graphics.Canvas,
        report: ReportEntity,
        rows: List<InspectionRowEntity>,
        startNumber: Int
    ) {
        drawOuterBorder(canvas, PORTRAIT_W, PORTRAIT_H)
        drawHeader(canvas, PORTRAIT_W, "Medical Location Inspection Report", report)

        val left = 10f
        val top = 128f
        val bottom = PORTRAIT_H - 72f
        val widths = floatArrayOf(46f, 356f, 43f, 43f, 48f, PORTRAIT_W - 20f - 536f)
        val headerH = 34f
        val rowH = (bottom - top - headerH) / INSPECTION_PAGE_ROWS
        val line = strokePaint(Color.BLACK, 0.7f)
        val headerText = textPaint(7.2f, bold = true)
        val whiteHeader = textPaint(6.7f, Color.WHITE, bold = true)
        val bodyText = textPaint(6.5f)
        var x = left
        val headers = listOf("Item", "COMMENTS - ONLY DEFECTS TO BE REPORTED BACK", "Note", "Defect", "Existing\nDefect", "Resolved")

        widths.forEachIndexed { index, width ->
            if (index in 2..4) {
                canvas.drawRect(x, top, x + width, top + headerH, Paint().apply { color = teal; style = Paint.Style.FILL })
                drawMultilineCell(canvas, x, top, width, headerH, headers[index], whiteHeader, line, Paint.Align.CENTER, drawBackground = false)
            } else {
                drawMultilineCell(canvas, x, top, width, headerH, headers[index], headerText, line, Paint.Align.CENTER)
            }
            x += width
        }

        for (i in 0 until INSPECTION_PAGE_ROWS) {
            val rowTop = top + headerH + i * rowH
            val row = rows.getOrNull(i)
            val number = startNumber + i
            x = left
            val status = row?.status.orEmpty()
            val values = listOf(
                number.toString(),
                row?.comment.orEmpty(),
                if (status == InspectionStatus.NOTE) "X" else "",
                if (status == InspectionStatus.DEFECT) "X" else "",
                if (status == InspectionStatus.EXISTING_DEFECT) "X" else "",
                if (status == InspectionStatus.RESOLVED) "X" else ""
            )
            widths.forEachIndexed { index, width ->
                drawCell(
                    canvas,
                    x,
                    rowTop,
                    width,
                    rowH,
                    values[index],
                    bodyText,
                    line,
                    align = if (index == 1) Paint.Align.LEFT else Paint.Align.CENTER,
                    padding = if (index == 1) 3f else 0f
                )
                x += width
            }
        }
        drawFooter(canvas, PORTRAIT_W, PORTRAIT_H)
    }

    private fun drawHeader(
        canvas: android.graphics.Canvas,
        pageWidth: Int,
        title: String,
        report: ReportEntity,
        compact: Boolean = false
    ) {
        drawLogo(canvas, 17f, 18f, if (compact) 92f else 80f)
        val titlePaint = textPaint(if (compact) 11.5f else 13.5f, bold = true)
        titlePaint.textAlign = Paint.Align.CENTER
        canvas.drawText(title, pageWidth * 0.48f, if (compact) 35f else 48f, titlePaint)
        val underlineW = titlePaint.measureText(title)
        canvas.drawLine(pageWidth * 0.48f - underlineW / 2f, if (compact) 38f else 51f, pageWidth * 0.48f + underlineW / 2f, if (compact) 38f else 51f, strokePaint(Color.BLACK, 0.8f))

        val right = pageWidth - 10f
        val metaW = if (compact) 290f else 224f
        val metaX = right - metaW
        val top = 18f
        val metaLine = strokePaint(Color.BLACK, 0.8f)
        drawCell(canvas, metaX, top, 88f, 31f, "Item Ref", textPaint(8f, bold = true), metaLine, Paint.Align.CENTER)
        drawCell(canvas, metaX + 88f, top, metaW - 88f, 31f, report.itemRef, textPaint(8f), metaLine, Paint.Align.CENTER)

        val detailTop = if (compact) 50f else 57f
        val detailLeft = if (compact) 120f else 84f
        val detailRight = right
        val detailW = detailRight - detailLeft
        val split = detailLeft + detailW * 0.68f
        val rowH = if (compact) 12f else 15f
        val labelPaint = textPaint(if (compact) 6.5f else 7.4f, bold = true)
        val valuePaint = textPaint(if (compact) 6.5f else 7.4f)

        val leftRows = listOf("Project:" to report.project, "Address:" to report.address, "Client:" to report.client)
        val rightRows = listOf("Job No:" to report.jobNumber, "Date:" to report.date, "Engineer:" to report.engineer)
        for (i in 0..2) {
            val y = detailTop + i * rowH
            canvas.drawRect(detailLeft, y, split, y + rowH, metaLine)
            canvas.drawRect(split, y, detailRight, y + rowH, metaLine)
            drawLabelValue(canvas, detailLeft + 5f, y + rowH * 0.70f, leftRows[i].first, leftRows[i].second, labelPaint, valuePaint, split - detailLeft - 10f)
            drawLabelValue(canvas, split + 5f, y + rowH * 0.70f, rightRows[i].first, rightRows[i].second, labelPaint, valuePaint, detailRight - split - 10f)
        }
    }

    private fun drawLogo(canvas: android.graphics.Canvas, x: Float, y: Float, width: Float) {
        val radius = width * 0.16f
        val cx = x + radius
        val cy = y + radius
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(58, 93, 88); style = Paint.Style.STROKE; strokeWidth = 3f }
        canvas.drawCircle(cx, cy, radius - 2f, ring)
        val check = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = teal; style = Paint.Style.STROKE; strokeWidth = 5f; strokeCap = Paint.Cap.ROUND }
        val path = Path()
        path.moveTo(cx - radius * 0.55f, cy)
        path.lineTo(cx - radius * 0.12f, cy + radius * 0.40f)
        path.lineTo(cx + radius * 0.62f, cy - radius * 0.50f)
        canvas.drawPath(path, check)
        val gmes = textPaint(width * 0.25f, teal, bold = true)
        canvas.drawText("GMES", x + radius * 2.1f, y + radius * 1.23f, gmes)
        val small = textPaint(width * 0.055f, Color.DKGRAY, bold = true)
        canvas.drawText("GROUP MANAGEMENT ELECTRICAL SURVEYS", x, y + radius * 2.55f, small)
    }

    private fun drawOuterBorder(canvas: android.graphics.Canvas, width: Int, height: Int) {
        canvas.drawRect(7f, 7f, width - 7f, height - 7f, strokePaint(darkTeal, 2.1f))
    }

    private fun drawFooter(canvas: android.graphics.Canvas, width: Int, height: Int) {
        val left = 10f
        val right = width - 10f
        val top = height - 66f
        val line = strokePaint(Color.BLACK, 0.55f)
        val fill = Paint().apply { color = lightGrey; style = Paint.Style.FILL }
        canvas.drawRect(left, top, right, top + 27f, fill)
        canvas.drawRect(left, top + 27f, right, top + 45f, fill)
        canvas.drawRect(left, top + 45f, right, top + 60f, fill)
        canvas.drawRect(left, top, right, top + 27f, line)
        canvas.drawRect(left, top + 27f, right, top + 45f, line)
        canvas.drawRect(left, top + 45f, right, top + 60f, line)

        drawWrappedCentered(canvas, NOTICE, left + 6f, top + 4f, right - left - 12f, 19f, textPaint(if (width > 600) 4.7f else 5.0f, bold = true))
        drawWrappedCentered(canvas, PERIODIC, left + 6f, top + 30f, right - left - 12f, 12f, textPaint(if (width > 600) 4.8f else 5.2f, bold = true))
        val contact = textPaint(if (width > 600) 4.8f else 5.0f, teal, bold = true)
        contact.textAlign = Paint.Align.CENTER
        canvas.drawText(CONTACT, width / 2f, top + 55f, contact)
        val ref = textPaint(4.6f, red)
        ref.textAlign = Paint.Align.RIGHT
        canvas.drawText("FM_041_2", right - 4f, height - 9f, ref)
    }

    private fun drawCell(
        canvas: android.graphics.Canvas,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        text: String,
        paint: Paint,
        border: Paint,
        align: Paint.Align = Paint.Align.LEFT,
        padding: Float = 2f
    ) {
        canvas.drawRect(x, y, x + w, y + h, border)
        val oldAlign = paint.textAlign
        paint.textAlign = align
        val tx = when (align) {
            Paint.Align.CENTER -> x + w / 2f
            Paint.Align.RIGHT -> x + w - padding
            else -> x + padding
        }
        canvas.save()
        canvas.clipRect(x + 1f, y + 1f, x + w - 1f, y + h - 1f)
        canvas.drawText(text, tx, y + h * 0.69f, paint)
        canvas.restore()
        paint.textAlign = oldAlign
    }

    private fun drawMultilineCell(
        canvas: android.graphics.Canvas,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        text: String,
        paint: Paint,
        border: Paint,
        align: Paint.Align,
        drawBackground: Boolean = true
    ) {
        if (drawBackground) canvas.drawRect(x, y, x + w, y + h, border) else canvas.drawRect(x, y, x + w, y + h, border)
        val lines = text.split("\n")
        val old = paint.textAlign
        paint.textAlign = align
        val lineH = min(h / (lines.size + 0.6f), paint.textSize * 1.35f)
        var yy = y + (h - lineH * lines.size) / 2f + lineH * 0.78f
        lines.forEach { lineText ->
            val tx = when (align) {
                Paint.Align.CENTER -> x + w / 2f
                Paint.Align.RIGHT -> x + w - 2f
                else -> x + 2f
            }
            canvas.drawText(lineText, tx, yy, paint)
            yy += lineH
        }
        paint.textAlign = old
    }

    private fun drawLabelValue(
        canvas: android.graphics.Canvas,
        x: Float,
        baseline: Float,
        label: String,
        value: String,
        labelPaint: Paint,
        valuePaint: Paint,
        maxWidth: Float
    ) {
        canvas.save()
        canvas.clipRect(x, baseline - labelPaint.textSize, x + maxWidth, baseline + 3f)
        canvas.drawText(label, x, baseline, labelPaint)
        canvas.drawText(value, x + labelPaint.measureText(label) + 3f, baseline, valuePaint)
        canvas.restore()
    }

    private fun drawWrappedCentered(
        canvas: android.graphics.Canvas,
        text: String,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        paint: Paint
    ) {
        val words = text.split(" ")
        val lines = mutableListOf<String>()
        var current = ""
        words.forEach { word ->
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (paint.measureText(candidate) <= width) current = candidate
            else {
                if (current.isNotEmpty()) lines += current
                current = word
            }
        }
        if (current.isNotEmpty()) lines += current
        val maxLines = 2
        val shown = lines.take(maxLines)
        val old = paint.textAlign
        paint.textAlign = Paint.Align.CENTER
        val lineH = height / maxLines
        shown.forEachIndexed { index, lineText ->
            canvas.drawText(lineText, x + width / 2f, y + lineH * (index + 0.78f), paint)
        }
        paint.textAlign = old
    }

    private fun matrixValue(a: DataRecordRowEntity?, b: DataRecordRowEntity?): String {
        val av = a?.continuityReading?.trim().orEmpty()
        val bv = b?.continuityReading?.trim().orEmpty()
        if (av.isBlank() || bv.isBlank()) return ""
        if (av.equals("I", true) || bv.equals("I", true)) return "I"
        val ad = parseReading(av) ?: return ""
        val bd = parseReading(bv) ?: return ""
        return "≤ ${decimal.format(ad + bd)}"
    }

    private fun parseReading(value: String): Double? {
        val clean = value.uppercase(Locale.UK)
            .replace("≤", "")
            .replace("OHMS", "")
            .replace("OHM", "")
            .replace("Ω", "")
            .trim()
        return clean.toDoubleOrNull()
    }

    private fun textPaint(size: Float, color: Int = textGrey, bold: Boolean = false): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = size
            isFakeBoldText = bold
        }

    private fun strokePaint(color: Int, width: Float): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = width
    }

    private fun buildFilename(report: ReportEntity): String {
        val parsed = runCatching {
            SimpleDateFormat("dd/MM/yyyy", Locale.UK).parse(report.date)
        }.getOrNull() ?: Date()
        val date = SimpleDateFormat("yyMMdd", Locale.UK).format(parsed)
        val job = sanitize(report.jobNumber).ifBlank { "NoJob" }
        val project = sanitize(report.project).ifBlank { "Medical_Location" }
        return "$date-$job-$project.pdf"
    }

    private fun sanitize(text: String): String = text.trim()
        .replace(Regex("[^A-Za-z0-9 _.-]"), "")
        .replace(Regex("\\s+"), "_")

    private fun savePdf(context: Context, pdf: PdfDocument, filename: String): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/GMES Reports")
            }
            val uri = requireNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
            context.contentResolver.openOutputStream(uri)!!.use { pdf.writeTo(it) }
            uri
        } else {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "GMES Reports").apply { mkdirs() }
            val file = File(dir, filename)
            file.outputStream().use { pdf.writeTo(it) }
            FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        }
    }

    fun shareIntent(uri: Uri, filename: String): Intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, filename.removeSuffix(".pdf"))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
