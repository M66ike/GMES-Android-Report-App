package com.gmes.reportapp

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val GmesTeal = Color(0xFF00695C)
private val GmesYellow = Color(0xFFFFD300)

sealed interface Screen {
    data object Home : Screen
    data class Editor(val reportId: Long) : Screen
}

enum class ReportSection { HEADER, DETAILS, DATA_RECORD, INSPECTION, REVIEW }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = AppDatabase.get(this)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = GmesTeal,
                    secondary = GmesYellow,
                    tertiary = GmesTeal
                )
            ) {
                var screen by remember { mutableStateOf<Screen>(Screen.Home) }
                Surface(Modifier.fillMaxSize()) {
                    when (val current = screen) {
                        Screen.Home -> HomeScreen(db = db, onOpen = { screen = Screen.Editor(it) })
                        is Screen.Editor -> EditorScreen(
                            db = db,
                            reportId = current.reportId,
                            onClose = { screen = Screen.Home },
                            onShare = { uri, filename ->
                                startActivity(Intent.createChooser(PdfExporter.shareIntent(uri, filename), "Share report"))
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppHeader(title: String, action: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().background(GmesTeal).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("GMES", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.width(14.dp))
        Text(title, color = Color.White, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        action?.invoke()
    }
}

@Composable
private fun HomeScreen(db: AppDatabase, onOpen: (Long) -> Unit) {
    val reports by db.reportDao().observeAll().collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        AppHeader("Medical Location Reports")
        Button(
            onClick = {
                scope.launch {
                    val date = SimpleDateFormat("dd/MM/yyyy", Locale.UK).format(Date())
                    val id = db.reportDao().insert(ReportEntity(date = date))
                    db.supplyCriteriaDao().insert(SupplyCriteriaEntity(reportId = id, dateOfTest = date))
                    db.dataRecordRowDao().ensureTrailingBlank(id)
                    db.inspectionRowDao().ensureTrailingBlank(id)
                    onOpen(id)
                }
            },
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) { Text("New Medical Location Report") }

        if (reports.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No reports yet.", color = Color.Gray)
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(reports, key = { it.id }) { report ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text(report.project.ifBlank { "Untitled Medical Location Report" }, fontWeight = FontWeight.Bold)
                            Text(
                                listOf(report.jobNumber, report.itemRef).filter { it.isNotBlank() }.joinToString(" · ")
                                    .ifBlank { report.date }
                            )
                            if (report.address.isNotBlank()) Text(report.address, style = MaterialTheme.typography.bodySmall)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { onOpen(report.id) }) { Text("Open") }
                                TextButton(onClick = { scope.launch { db.reportDao().delete(report) } }) { Text("Delete") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorScreen(
    db: AppDatabase,
    reportId: Long,
    onClose: () -> Unit,
    onShare: (android.net.Uri, String) -> Unit
) {
    val report by db.reportDao().observe(reportId).collectAsStateWithLifecycle(initialValue = null)
    var section by rememberSaveable(reportId) { mutableStateOf(ReportSection.HEADER) }

    Column(Modifier.fillMaxSize()) {
        AppHeader("Medical Location Report") {
            TextButton(onClick = onClose) { Text("Close", color = Color.White) }
        }
        SectionStrip(section = section, onSelect = { section = it })
        val current = report
        if (current == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            when (section) {
                ReportSection.HEADER -> HeaderSection(
                    report = current,
                    onSave = { db.reportDao().update(it.copy(updatedAt = System.currentTimeMillis())) },
                    onBack = onClose,
                    onNext = { section = ReportSection.DETAILS }
                )
                ReportSection.DETAILS -> DetailsSection(
                    db = db,
                    reportId = reportId,
                    onBack = { section = ReportSection.HEADER },
                    onNext = { section = ReportSection.DATA_RECORD }
                )
                ReportSection.DATA_RECORD -> DataRecordSection(
                    db = db,
                    reportId = reportId,
                    onBack = { section = ReportSection.DETAILS },
                    onNext = { section = ReportSection.INSPECTION }
                )
                ReportSection.INSPECTION -> InspectionSection(
                    db = db,
                    reportId = reportId,
                    onBack = { section = ReportSection.DATA_RECORD },
                    onNext = { section = ReportSection.REVIEW }
                )
                ReportSection.REVIEW -> ReviewSection(
                    db = db,
                    reportId = reportId,
                    onBack = { section = ReportSection.INSPECTION },
                    onShare = onShare
                )
            }
        }
    }
}

@Composable
private fun SectionStrip(section: ReportSection, onSelect: (ReportSection) -> Unit) {
    val sections = listOf(
        ReportSection.HEADER to "Header",
        ReportSection.DETAILS to "Details",
        ReportSection.DATA_RECORD to "Data",
        ReportSection.INSPECTION to "Defects",
        ReportSection.REVIEW to "PDF"
    )
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        sections.forEach { (s, label) ->
            if (s == section) {
                Button(
                    onClick = { onSelect(s) },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp)
                ) { Text(label, style = MaterialTheme.typography.labelSmall) }
            } else {
                OutlinedButton(
                    onClick = { onSelect(s) },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp)
                ) { Text(label, style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}

@Composable
private fun HeaderSection(
    report: ReportEntity,
    onSave: suspend (ReportEntity) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit
) {
    var project by remember(report.id) { mutableStateOf(report.project) }
    var jobNumber by remember(report.id) { mutableStateOf(report.jobNumber) }
    var address by remember(report.id) { mutableStateOf(report.address) }
    var date by remember(report.id) { mutableStateOf(report.date) }
    var client by remember(report.id) { mutableStateOf(report.client) }
    var engineer by remember(report.id) { mutableStateOf(report.engineer) }
    var itemRef by remember(report.id) { mutableStateOf(report.itemRef) }
    val scope = rememberCoroutineScope()

    fun updated() = report.copy(
        project = project,
        jobNumber = jobNumber,
        address = address,
        date = date,
        client = client,
        engineer = engineer,
        itemRef = itemRef
    )

    FormColumn("Report Header") {
        Text("Matches the header shown on every page of the Excel/PDF template.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        Field("Project", project) { project = it }
        Field("Address", address, singleLine = false) { address = it }
        Field("Client", client) { client = it }
        Field("Item Ref", itemRef) { itemRef = it }
        Field("Job No", jobNumber) { jobNumber = it }
        Field("Date (dd/MM/yyyy)", date) { date = it }
        Field("Engineer", engineer) { engineer = it }
        NavigationRow(
            backText = "Save & Close",
            onBack = { scope.launch { onSave(updated()); onBack() } },
            onNext = { scope.launch { onSave(updated()); onNext() } }
        )
    }
}

@Composable
private fun DetailsSection(db: AppDatabase, reportId: Long, onBack: () -> Unit, onNext: () -> Unit) {
    val details by db.supplyCriteriaDao().observe(reportId).collectAsStateWithLifecycle(initialValue = null)
    val item = details
    if (item == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    var testEquipment by remember(item.id) { mutableStateOf(item.testEquipment) }
    var erbLocation by remember(item.id) { mutableStateOf(item.erbLocation) }
    var testDetails by remember(item.id) { mutableStateOf(item.testDetails) }
    var dateOfTest by remember(item.id) { mutableStateOf(item.dateOfTest) }
    var requestedBy by remember(item.id) { mutableStateOf(item.requestedBy) }
    var tnSCircuits by remember(item.id) { mutableStateOf(item.tnSCircuits) }
    var dcSensitive by remember(item.id) { mutableStateOf(item.dcSensitiveDevice) }
    var upsIps by remember(item.id) { mutableStateOf(item.upsIpsCircuits) }
    var mcbType by remember(item.id) { mutableStateOf(item.mcbType) }
    var mcbSize by remember(item.id) { mutableStateOf(item.mcbSize) }
    val scope = rememberCoroutineScope()

    fun updated() = item.copy(
        testEquipment = testEquipment,
        erbLocation = erbLocation,
        testDetails = testDetails,
        dateOfTest = dateOfTest,
        requestedBy = requestedBy,
        tnSCircuits = tnSCircuits,
        dcSensitiveDevice = dcSensitive,
        upsIpsCircuits = upsIps,
        mcbType = mcbType,
        mcbSize = mcbSize
    )

    FormColumn("Test Details") {
        Field("Test Equipment", testEquipment) { testEquipment = it }
        Field("ERB Location", erbLocation) { erbLocation = it }
        Field("Test Details", testDetails) { testDetails = it }
        Field("Date of Test", dateOfTest) { dateOfTest = it }
        Field("Requested By", requestedBy) { requestedBy = it }
        Text("Circuit / protective device information", fontWeight = FontWeight.Bold)
        Field("TN-S Circuits", tnSCircuits) { tnSCircuits = it }
        Field("DC Sensitive Device Yes / No", dcSensitive) { dcSensitive = it }
        Field("UPS/IPS Circuits", upsIps) { upsIps = it }
        Field("MCB Type", mcbType) { mcbType = it }
        Field("MCB Size", mcbSize) { mcbSize = it }
        NavigationRow(
            onBack = { scope.launch { db.supplyCriteriaDao().upsert(updated()); onBack() } },
            onNext = { scope.launch { db.supplyCriteriaDao().upsert(updated()); onNext() } }
        )
    }
}

@Composable
private fun DataRecordSection(db: AppDatabase, reportId: Long, onBack: () -> Unit, onNext: () -> Unit) {
    val dao = db.dataRecordRowDao()
    val rows by dao.observe(reportId).collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    LaunchedEffect(reportId, rows.size, rows.lastOrNull()?.isBlank()) { dao.ensureTrailingBlank(reportId) }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Text("Medical Location Data Record", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp))
        Text("The item number is automatic. Enter I for an insulated item (>500MΩ).", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(rows, key = { it.id }) { row ->
                DataRowCard(
                    row = row,
                    onChange = { updated ->
                        scope.launch {
                            dao.update(updated.copy(updatedAt = System.currentTimeMillis()))
                            dao.ensureTrailingBlank(reportId)
                        }
                    },
                    onDelete = { scope.launch { dao.deleteAndRenumber(row) } }
                )
            }
        }
        NavigationRow(onBack = onBack, onNext = onNext)
    }
}

@Composable
private fun DataRowCard(row: DataRecordRowEntity, onChange: (DataRecordRowEntity) -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Item ${row.rowNumber}", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (!row.isBlank()) TextButton(onClick = onDelete) { Text("Delete") }
            }
            Field("Test Item Recorded Clockwise from Double Door", row.testItem) { onChange(row.copy(testItem = it)) }
            Text("Connected Y/N", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Y", "N").forEach { value ->
                    if (row.connected == value) Button(onClick = { onChange(row.copy(connected = value)) }) { Text(value) }
                    else OutlinedButton(onClick = { onChange(row.copy(connected = value)) }) { Text(value) }
                }
                if (row.connected.isNotBlank()) TextButton(onClick = { onChange(row.copy(connected = "")) }) { Text("Clear") }
            }
            Field("Continuity Reading to ERB (Ω) - or I", row.continuityReading) { onChange(row.copy(continuityReading = it)) }
        }
    }
}

@Composable
private fun InspectionSection(db: AppDatabase, reportId: Long, onBack: () -> Unit, onNext: () -> Unit) {
    val dao = db.inspectionRowDao()
    val rows by dao.observe(reportId).collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    LaunchedEffect(reportId, rows.size, rows.lastOrNull()?.isBlank()) { dao.ensureTrailingBlank(reportId) }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Text("Medical Location Inspection Report", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp))
        Text("Only defects / notes that need reporting are entered here.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(rows, key = { it.id }) { row ->
                InspectionRowCard(
                    row = row,
                    onChange = { updated ->
                        scope.launch {
                            dao.update(updated.copy(updatedAt = System.currentTimeMillis()))
                            dao.ensureTrailingBlank(reportId)
                        }
                    },
                    onDelete = { scope.launch { dao.deleteAndRenumber(row) } }
                )
            }
        }
        NavigationRow(onBack = onBack, onNext = onNext)
    }
}

@Composable
private fun InspectionRowCard(row: InspectionRowEntity, onChange: (InspectionRowEntity) -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Item ${row.rowNumber}", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (!row.isBlank()) TextButton(onClick = onDelete) { Text("Delete") }
            }
            Field("Comment / Defect", row.comment, singleLine = false) { onChange(row.copy(comment = it)) }
            Text("Classification", style = MaterialTheme.typography.labelMedium)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    InspectionStatus.NOTE,
                    InspectionStatus.DEFECT,
                    InspectionStatus.EXISTING_DEFECT,
                    InspectionStatus.RESOLVED
                ).chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { status ->
                            if (row.status == status) {
                                Button(onClick = { onChange(row.copy(status = status)) }, modifier = Modifier.weight(1f)) { Text(status) }
                            } else {
                                OutlinedButton(onClick = { onChange(row.copy(status = status)) }, modifier = Modifier.weight(1f)) { Text(status) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewSection(
    db: AppDatabase,
    reportId: Long,
    onBack: () -> Unit,
    onShare: (android.net.Uri, String) -> Unit
) {
    val report by db.reportDao().observe(reportId).collectAsStateWithLifecycle(initialValue = null)
    val details by db.supplyCriteriaDao().observe(reportId).collectAsStateWithLifecycle(initialValue = null)
    val dataRows by db.dataRecordRowDao().observe(reportId).collectAsStateWithLifecycle(emptyList())
    val inspectionRows by db.inspectionRowDao().observe(reportId).collectAsStateWithLifecycle(emptyList())
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    val current = report ?: return
    val usableData = dataRows.filterNot { it.isBlank() }
    val usableInspection = inspectionRows.filterNot { it.isBlank() }

    FormColumn("PDF Preview / Export") {
        Text("The generated PDF now follows the supplied FM_041_2 style: portrait data record pages, landscape 37-point matrix pages and portrait inspection report pages.")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${usableData.size} data record item(s)", fontWeight = FontWeight.Bold)
                Text("${usableInspection.size} inspection item(s)")
                Text("Matrix pages are generated automatically from the continuity readings.")
            }
        }
        if (current.project.isBlank() || current.jobNumber.isBlank() || current.itemRef.isBlank()) {
            Text("Complete Project, Job No and Item Ref before issuing the final PDF.", color = MaterialTheme.colorScheme.error)
        }
        Button(
            onClick = {
                exporting = true
                scope.launch {
                    try {
                        val saved = withContext(Dispatchers.IO) {
                            PdfExporter.createAndSave(
                                context = context,
                                report = current,
                                details = details,
                                rows = dataRows,
                                inspections = inspectionRows
                            )
                        }
                        Toast.makeText(context, "PDF saved to Downloads/GMES Reports", Toast.LENGTH_LONG).show()
                        onShare(saved.first, saved.second)
                    } catch (e: Exception) {
                        Toast.makeText(context, "PDF export failed: ${e.message}", Toast.LENGTH_LONG).show()
                    } finally {
                        exporting = false
                    }
                }
            },
            enabled = !exporting,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (exporting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Text("Generate & Share PDF")
        }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
    }
}

@Composable
private fun FormColumn(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        content()
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun Field(label: String, value: String, singleLine: Boolean = true, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 2
    )
}

@Composable
private fun NavigationRow(
    onBack: () -> Unit,
    onNext: () -> Unit,
    backText: String = "Back",
    nextText: String = "Next"
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text(backText) }
        Button(onClick = onNext, modifier = Modifier.weight(1f)) { Text(nextText) }
    }
}
