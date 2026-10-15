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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SignatureData(
    val strokes: List<List<Pair<Float, Float>>>,
    val width: Float,
    val height: Float
)

object PdfExporter {
    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private val teal = Color.rgb(0, 92, 82)
    private val paleYellow = Color.rgb(255, 247, 176)

    fun createAndSave(
        context: Context,
        report: ReportEntity,
        supply: SupplyCriteriaEntity?,
        rows: List<DataRecordRowEntity>,
        signature: SignatureData
    ): Pair<Uri, String> {
        val usableRows = rows.filterNot { it.isBlank() }
        val filename = buildFilename(report)
        val pdf = PdfDocument()

        val firstCapacity = 14
        val laterCapacity = 20
        var consumed = 0
        var pageNo = 1

        do {
            val first = pageNo == 1
            val capacity = if (first) firstCapacity else laterCapacity
            val pageRows = usableRows.drop(consumed).take(capacity)
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
            val canvas = page.canvas
            drawPageHeader(canvas, report, pageNo)

            var y = 92f
            if (first) {
                y = drawReportDetails(canvas, report, y)
                y = drawSupplyDetails(canvas, supply, y + 6f)
            }
            y += 10f
            y = drawDataTable(canvas, pageRows, y)

            val isLast = consumed + pageRows.size >= usableRows.size
            if (isLast) {
                drawSignature(canvas, signature, y + 22f, report.engineer.ifBlank { supply?.engineer.orEmpty() })
            }
            drawFooter(canvas, pageNo)
            pdf.finishPage(page)

            consumed += pageRows.size
            pageNo++
        } while (consumed < usableRows.size || (usableRows.isEmpty() && pageNo == 2).not() && consumed == 0)

        val uri = savePdf(context, pdf, filename)
        pdf.close()
        return uri to filename
    }

    private fun drawPageHeader(canvas: android.graphics.Canvas, report: ReportEntity, pageNo: Int) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = teal; style = Paint.Style.FILL }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 21f; isFakeBoldText = true }
        canvas.drawRect(0f, 0f, PAGE_W.toFloat(), 56f, fill)
        canvas.drawText("GMES", 24f, 35f, text)
        text.textSize = 10f
        text.isFakeBoldText = false
        canvas.drawText("Electrical Inspection & Test Report", 94f, 33f, text)
        text.textAlign = Paint.Align.RIGHT
        canvas.drawText("Page $pageNo", PAGE_W - 22f, 33f, text)
        text.textAlign = Paint.Align.LEFT
        val rule = Paint().apply { color = Color.rgb(255, 211, 0); strokeWidth = 5f }
        canvas.drawLine(0f, 58f, PAGE_W.toFloat(), 58f, rule)
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = 13f; isFakeBoldText = true }
        canvas.drawText(report.project.ifBlank { "Report" }, 24f, 80f, title)
    }

    private fun drawReportDetails(canvas: android.graphics.Canvas, report: ReportEntity, startY: Float): Float {
        var y = startY
        val items = listOf(
            "Project" to report.project,
            "Job Number" to report.jobNumber,
            "Address" to report.address,
            "Date" to report.date,
            "Client" to report.client,
            "Engineer" to report.engineer,
            "Item Ref" to report.itemRef
        )
        canvas.drawRect(20f, y - 16f, PAGE_W - 20f, y + 6f + items.size * 19f, Paint().apply { color = Color.rgb(247,247,247) })
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = teal; textSize = 9f; isFakeBoldText = true }
        val value = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = 9f }
        items.forEach { (l, v) ->
            canvas.drawText("$l:", 32f, y, label)
            canvas.drawText(v.ifBlank { "—" }, 118f, y, value)
            y += 19f
        }
        return y + 2f
    }

    private fun drawSupplyDetails(canvas: android.graphics.Canvas, supply: SupplyCriteriaEntity?, startY: Float): Float {
        var y = startY
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = teal; textSize = 11f; isFakeBoldText = true }
        canvas.drawText("Supply Criteria", 24f, y, title)
        y += 17f
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = 8.5f }
        val values = listOf(
            "Client" to supply?.client.orEmpty(),
            "Engineer" to supply?.engineer.orEmpty(),
            "Test Instrument" to supply?.testInstrument.orEmpty(),
            "CAL. CERT. NO." to supply?.calCertNo.orEmpty()
        )
        values.chunked(2).forEach { pair ->
            pair.forEachIndexed { index, item ->
                val x = if (index == 0) 28f else 300f
                canvas.drawText("${item.first}: ${item.second.ifBlank { "—" }}", x, y, label)
            }
            y += 16f
        }
        return y
    }

    private fun drawDataTable(canvas: android.graphics.Canvas, rows: List<DataRecordRowEntity>, startY: Float): Float {
        val widths = floatArrayOf(32f, 178f, 58f, 82f, 78f, 127f)
        val headers = listOf("No.", "Test Item", "Unit", "Measured", "Inspect Item", "Comment")
        val left = 20f
        val rowH = 24f
        var y = startY
        val headerFill = Paint().apply { color = teal }
        canvas.drawRect(left, y, PAGE_W - 20f, y + rowH, headerFill)
        val whiteText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 7.8f; isFakeBoldText = true }
        var x = left
        headers.forEachIndexed { i, h ->
            canvas.drawText(h, x + 4f, y + 15f, whiteText)
            x += widths[i]
        }
        y += rowH
        val border = Paint().apply { color = Color.LTGRAY; style = Paint.Style.STROKE; strokeWidth = 0.6f }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = 7.6f }
        rows.forEach { row ->
            x = left
            val values = listOf(
                row.rowNumber.toString(), row.testItem, row.unit, row.measuredValue,
                row.inspectionItemNumber, row.comment
            )
            values.forEachIndexed { i, v ->
                canvas.drawRect(x, y, x + widths[i], y + rowH, border)
                canvas.save()
                canvas.clipRect(x + 2f, y + 2f, x + widths[i] - 2f, y + rowH - 2f)
                canvas.drawText(v, x + 4f, y + 15f, text)
                canvas.restore()
                x += widths[i]
            }
            y += rowH
        }
        return y
    }

    private fun drawSignature(canvas: android.graphics.Canvas, signature: SignatureData, top: Float, engineer: String) {
        val y = top.coerceAtMost(PAGE_H - 125f)
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = teal; textSize = 10f; isFakeBoldText = true }
        canvas.drawText("Engineer Signature", 24f, y, title)
        val boxLeft = 24f
        val boxTop = y + 8f
        val boxW = 250f
        val boxH = 70f
        canvas.drawRect(boxLeft, boxTop, boxLeft + boxW, boxTop + boxH, Paint().apply { color = Color.LTGRAY; style = Paint.Style.STROKE })
        val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 1.4f; strokeCap = Paint.Cap.ROUND }
        val sx = if (signature.width > 0f) boxW / signature.width else 1f
        val sy = if (signature.height > 0f) boxH / signature.height else 1f
        signature.strokes.forEach { stroke ->
            if (stroke.size > 1) {
                val path = Path()
                path.moveTo(boxLeft + stroke.first().first * sx, boxTop + stroke.first().second * sy)
                stroke.drop(1).forEach { p -> path.lineTo(boxLeft + p.first * sx, boxTop + p.second * sy) }
                canvas.drawPath(path, pen)
            }
        }
        val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = 8.5f }
        canvas.drawText("Engineer: ${engineer.ifBlank { "—" }}", 292f, boxTop + 22f, txt)
        canvas.drawText("Signed at export", 292f, boxTop + 39f, txt)
    }

    private fun drawFooter(canvas: android.graphics.Canvas, pageNo: Int) {
        val line = Paint().apply { color = teal; strokeWidth = 1f }
        canvas.drawLine(20f, PAGE_H - 28f, PAGE_W - 20f, PAGE_H - 28f, line)
        val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.GRAY; textSize = 7f }
        canvas.drawText("Group Management Electrical Services", 22f, PAGE_H - 15f, txt)
        txt.textAlign = Paint.Align.RIGHT
        canvas.drawText("Generated by GMES Report App · $pageNo", PAGE_W - 22f, PAGE_H - 15f, txt)
    }

    private fun buildFilename(report: ReportEntity): String {
        val parsed = runCatching {
            SimpleDateFormat("dd/MM/yyyy", Locale.UK).parse(report.date)
        }.getOrNull() ?: Date()
        val date = SimpleDateFormat("yyMMdd", Locale.UK).format(parsed)
        val job = sanitize(report.jobNumber).ifBlank { "NoJob" }
        val project = sanitize(report.project).ifBlank { "Project" }
        return "$date-$job-$project.pdf"
    }

    private fun sanitize(text: String): String = text.trim().replace(Regex("[^A-Za-z0-9 _.-]"), "").replace(Regex("\\s+"), "_")

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
