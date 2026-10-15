package com.gmes.reportapp

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val GmesTeal = Color(0xFF005C52)
private val GmesYellow = Color(0xFFFFD300)

sealed interface Screen {
    data object Home : Screen
    data class Editor(val reportId: Long) : Screen
    data object Settings : Screen
    data class Library(val title: String, val type: String) : Screen
}

enum class ReportSection { HEADER, SUPPLY, DATA_RECORD, REVIEW }

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
                        Screen.Home -> HomeScreen(
                            db = db,
                            onOpen = { screen = Screen.Editor(it) },
                            onSettings = { screen = Screen.Settings }
                        )
                        is Screen.Editor -> EditorScreen(
                            db = db,
                            reportId = current.reportId,
                            onClose = { screen = Screen.Home },
                            onShare = { uri, filename ->
                                startActivity(Intent.createChooser(PdfExporter.shareIntent(uri, filename), "Share report"))
                            }
                        )
                        Screen.Settings -> SettingsScreen(
                            onBack = { screen = Screen.Home },
                            onOpenLibrary = { title, type -> screen = Screen.Library(title, type) }
                        )
                        is Screen.Library -> LibraryScreen(
                            db = db,
                            title = current.title,
                            type = current.type,
                            onBack = { screen = Screen.Settings }
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
private fun HomeScreen(db: AppDatabase, onOpen: (Long) -> Unit, onSettings: () -> Unit) {
    val reports by db.reportDao().observeAll().collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        AppHeader("Reports") {
            TextButton(onClick = onSettings) { Text("Settings", color = Color.White) }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    scope.launch {
                        val date = SimpleDateFormat("dd/MM/yyyy", Locale.UK).format(Date())
                        val id = db.reportDao().insert(ReportEntity(date = date))
                        db.supplyCriteriaDao().insert(SupplyCriteriaEntity(reportId = id))
                        db.dataRecordRowDao().ensureTrailingBlank(id)
                        onOpen(id)
                    }
                },
                modifier = Modifier.weight(1f)
            ) { Text("New Report") }
            OutlinedButton(onClick = onSettings) { Text("Libraries") }
        }
        if (reports.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No reports yet. Tap New Report to begin.", color = Color.Gray)
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(reports, key = { it.id }) { report ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text(report.project.ifBlank { "Untitled Report" }, fontWeight = FontWeight.Bold)
                            Text(listOf(report.jobNumber, report.itemRef).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { report.date })
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
        AppHeader("Report Editor") {
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
                    onNext = { section = ReportSection.SUPPLY }
                )
                ReportSection.SUPPLY -> SupplySection(
                    db = db,
                    reportId = reportId,
                    onBack = { section = ReportSection.HEADER },
                    onNext = { section = ReportSection.DATA_RECORD }
                )
                ReportSection.DATA_RECORD -> DataRecordSection(
                    db = db,
                    reportId = reportId,
                    onBack = { section = ReportSection.SUPPLY },
                    onNext = { section = ReportSection.REVIEW }
                )
                ReportSection.REVIEW -> ReviewSection(
                    db = db,
                    reportId = reportId,
                    onBack = { section = ReportSection.DATA_RECORD },
                    onShare = onShare
                )
            }
        }
    }
}

@Composable
private fun SectionStrip(section: ReportSection, onSelect: (ReportSection) -> Unit) {
    val names = listOf(
        ReportSection.HEADER to "Header",
        ReportSection.SUPPLY to "Supply",
        ReportSection.DATA_RECORD to "Data",
        ReportSection.REVIEW to "Review"
    )
    Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        names.forEach { (s, label) ->
            if (section == s) Button(onClick = { onSelect(s) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) { Text(label) }
            else OutlinedButton(onClick = { onSelect(s) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) { Text(label) }
        }
    }
}

@Composable
private fun HeaderSection(report: ReportEntity, onSave: suspend (ReportEntity) -> Unit, onBack: () -> Unit, onNext: () -> Unit) {
    var project by remember(report.id) { mutableStateOf(report.project) }
    var job by remember(report.id) { mutableStateOf(report.jobNumber) }
    var address by remember(report.id) { mutableStateOf(report.address) }
    var date by remember(report.id) { mutableStateOf(report.date) }
    var client by remember(report.id) { mutableStateOf(report.client) }
    var engineer by remember(report.id) { mutableStateOf(report.engineer) }
    var itemRef by remember(report.id) { mutableStateOf(report.itemRef) }
    val scope = rememberCoroutineScope()

    FormColumn("Report Header") {
        Field("Project", project) { project = it }
        Field("Job Number", job) { job = it }
        Field("Address", address, singleLine = false) { address = it }
        Field("Date (dd/MM/yyyy)", date) { date = it }
        Field("Client", client) { client = it }
        Field("Engineer", engineer) { engineer = it }
        Field("Item Ref", itemRef) { itemRef = it }
        NavigationRow(
            onBack = onBack,
            onNext = {
                scope.launch {
                    onSave(report.copy(project = project, jobNumber = job, address = address, date = date, client = client, engineer = engineer, itemRef = itemRef))
                    onNext()
                }
            }
        )
    }
}

@Composable
private fun SupplySection(db: AppDatabase, reportId: Long, onBack: () -> Unit, onNext: () -> Unit) {
    val supply by db.supplyCriteriaDao().observe(reportId).collectAsStateWithLifecycle(initialValue = null)
    val item = supply ?: return Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    var client by remember(item.id) { mutableStateOf(item.client) }
    var engineer by remember(item.id) { mutableStateOf(item.engineer) }
    var instrument by remember(item.id) { mutableStateOf(item.testInstrument) }
    var cert by remember(item.id) { mutableStateOf(item.calCertNo) }
    val clients by db.libraryItemDao().observeByType(LibraryTypes.CLIENT).collectAsStateWithLifecycle(emptyList())
    val engineers by db.libraryItemDao().observeByType(LibraryTypes.ENGINEER).collectAsStateWithLifecycle(emptyList())
    val instruments by db.libraryItemDao().observeByType(LibraryTypes.TEST_INSTRUMENT).collectAsStateWithLifecycle(emptyList())
    val certs by db.libraryItemDao().observeByType(LibraryTypes.CAL_CERT).collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()

    FormColumn("Supply Criteria") {
        LibraryEntryField("Client", client, { client = it }, clients.map { it.value }) {
            scope.launch { saveLibrary(db, LibraryTypes.CLIENT, client) }
        }
        LibraryEntryField("Engineer", engineer, { engineer = it }, engineers.map { it.value }) {
            scope.launch { saveLibrary(db, LibraryTypes.ENGINEER, engineer) }
        }
        LibraryEntryField("Test Instrument", instrument, { instrument = it }, instruments.map { it.value }) {
            scope.launch { saveLibrary(db, LibraryTypes.TEST_INSTRUMENT, instrument) }
        }
        LibraryEntryField("CAL. CERT. NO.", cert, { cert = it }, certs.map { it.value }) {
            scope.launch { saveLibrary(db, LibraryTypes.CAL_CERT, cert) }
        }
        NavigationRow(
            onBack = {
                scope.launch { db.supplyCriteriaDao().upsert(item.copy(client = client, engineer = engineer, testInstrument = instrument, calCertNo = cert)); onBack() }
            },
            onNext = {
                scope.launch { db.supplyCriteriaDao().upsert(item.copy(client = client, engineer = engineer, testInstrument = instrument, calCertNo = cert)); onNext() }
            }
        )
    }
}

private suspend fun saveLibrary(db: AppDatabase, type: String, value: String) {
    val clean = value.trim()
    if (clean.isNotEmpty()) db.libraryItemDao().insert(LibraryItemEntity(libraryType = type, value = clean))
}

@Composable
private fun DataRecordSection(db: AppDatabase, reportId: Long, onBack: () -> Unit, onNext: () -> Unit) {
    val dao = db.dataRecordRowDao()
    val rows by dao.observe(reportId).collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    LaunchedEffect(reportId, rows.size, rows.lastOrNull()?.isBlank()) { dao.ensureTrailingBlank(reportId) }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Text("Data Record", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp))
        Text("A new row is created automatically when the last row is used.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
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
            Field("Test Item", row.testItem) { onChange(row.copy(testItem = it)) }
            Text("Unit", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Ω", "MΩ").forEach { unit ->
                    if (row.unit == unit) Button(onClick = { onChange(row.copy(unit = unit)) }) { Text(unit) }
                    else OutlinedButton(onClick = { onChange(row.copy(unit = unit)) }) { Text(unit) }
                }
                if (row.unit.isNotBlank()) TextButton(onClick = { onChange(row.copy(unit = "")) }) { Text("Clear") }
            }
            OutlinedTextField(
                value = row.measuredValue,
                onValueChange = { onChange(row.copy(measuredValue = it)) },
                label = { Text("Measured Value") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true
            )
            Field("Inspection Item No. (optional)", row.inspectionItemNumber) { onChange(row.copy(inspectionItemNumber = it)) }
            Field("Comment (optional)", row.comment, singleLine = false) { onChange(row.copy(comment = it)) }
        }
    }
}

@Composable
private fun ReviewSection(db: AppDatabase, reportId: Long, onBack: () -> Unit, onShare: (android.net.Uri, String) -> Unit) {
    val report by db.reportDao().observe(reportId).collectAsStateWithLifecycle(initialValue = null)
    val supply by db.supplyCriteriaDao().observe(reportId).collectAsStateWithLifecycle(initialValue = null)
    val rows by db.dataRecordRowDao().observe(reportId).collectAsStateWithLifecycle(emptyList())
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var showSignature by remember { mutableStateOf(false) }
    val current = report ?: return
    val warnings = remember(current, supply, rows) { buildWarnings(current, supply, rows) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Review & Export", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        if (warnings.isEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9))) {
                Text("No missing-field warnings detected.", Modifier.padding(12.dp))
            }
        } else {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3CD))) {
                Column(Modifier.padding(12.dp)) {
                    Text("Warnings — export is still allowed", fontWeight = FontWeight.Bold)
                    warnings.forEach { Text("• $it") }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Text("${rows.count { !it.isBlank() }} data record item(s)")
        Text("PDF will be saved locally and then opened in the Android share sheet.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("Back") }
            Button(onClick = { showSignature = true }, modifier = Modifier.weight(1f)) { Text("Sign & Export PDF") }
        }
    }

    if (showSignature) {
        SignatureDialog(
            onDismiss = { showSignature = false },
            onSigned = { signature ->
                showSignature = false
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) { PdfExporter.createAndSave(context, current, supply, rows, signature) }
                    }.onSuccess { (uri, filename) ->
                        Toast.makeText(context, "Saved: $filename", Toast.LENGTH_LONG).show()
                        onShare(uri, filename)
                    }.onFailure {
                        Toast.makeText(context, "PDF export failed: ${it.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        )
    }
}

private fun buildWarnings(report: ReportEntity, supply: SupplyCriteriaEntity?, rows: List<DataRecordRowEntity>): List<String> {
    val out = mutableListOf<String>()
    if (report.project.isBlank()) out += "Project is blank"
    if (report.jobNumber.isBlank()) out += "Job Number is blank"
    if (report.address.isBlank()) out += "Address is blank"
    if (report.date.isBlank()) out += "Date is blank"
    if (report.engineer.isBlank() && supply?.engineer.isNullOrBlank()) out += "Engineer is blank"
    if (supply?.testInstrument.isNullOrBlank()) out += "Test Instrument is blank"
    if (supply?.calCertNo.isNullOrBlank()) out += "CAL. CERT. NO. is blank"
    rows.filterNot { it.isBlank() }.forEach { row ->
        if (row.testItem.isBlank()) out += "Data item ${row.rowNumber}: Test Item is blank"
        if (row.measuredValue.isBlank()) out += "Data item ${row.rowNumber}: Measured Value is blank"
        if (row.unit.isBlank()) out += "Data item ${row.rowNumber}: Unit is blank"
    }
    if (rows.none { !it.isBlank() }) out += "No Data Record rows have been completed"
    return out
}

@Composable
private fun SignatureDialog(onDismiss: () -> Unit, onSigned: (SignatureData) -> Unit) {
    var strokes by remember { mutableStateOf<List<List<Offset>>>(emptyList()) }
    var currentStroke by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Engineer Signature") },
        text = {
            Column {
                Text("Sign in the box below. A signature is required before export.")
                Spacer(Modifier.height(10.dp))
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .background(Color.White)
                        .onSizeChanged { canvasSize = it }
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { currentStroke = listOf(it) },
                                onDrag = { change, _ -> currentStroke = currentStroke + change.position },
                                onDragEnd = {
                                    if (currentStroke.isNotEmpty()) strokes = strokes + listOf(currentStroke)
                                    currentStroke = emptyList()
                                },
                                onDragCancel = { currentStroke = emptyList() }
                            )
                        }
                ) {
                    fun drawStroke(points: List<Offset>) {
                        points.zipWithNext().forEach { (a, b) -> drawLine(Color.Black, a, b, strokeWidth = 3f, cap = StrokeCap.Round) }
                    }
                    strokes.forEach(::drawStroke)
                    drawStroke(currentStroke)
                }
                TextButton(onClick = { strokes = emptyList(); currentStroke = emptyList() }) { Text("Clear signature") }
            }
        },
        confirmButton = {
            Button(
                enabled = strokes.isNotEmpty() || currentStroke.isNotEmpty(),
                onClick = {
                    val all = if (currentStroke.isNotEmpty()) strokes + listOf(currentStroke) else strokes
                    onSigned(SignatureData(
                        strokes = all.map { stroke -> stroke.map { it.x to it.y } },
                        width = canvasSize.width.toFloat(),
                        height = canvasSize.height.toFloat()
                    ))
                }
            ) { Text("Export") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun SettingsScreen(onBack: () -> Unit, onOpenLibrary: (String, String) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        AppHeader("Settings")
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { onOpenLibrary("Client Library", LibraryTypes.CLIENT) }, modifier = Modifier.fillMaxWidth()) { Text("Client Library") }
            Button(onClick = { onOpenLibrary("Engineer Library", LibraryTypes.ENGINEER) }, modifier = Modifier.fillMaxWidth()) { Text("Engineer Library") }
            Button(onClick = { onOpenLibrary("Test Instrument Library", LibraryTypes.TEST_INSTRUMENT) }, modifier = Modifier.fillMaxWidth()) { Text("Test Instrument Library") }
            Button(onClick = { onOpenLibrary("CAL. CERT. NO. Library", LibraryTypes.CAL_CERT) }, modifier = Modifier.fillMaxWidth()) { Text("CAL. CERT. NO. Library") }
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
        }
    }
}

@Composable
private fun LibraryScreen(db: AppDatabase, title: String, type: String, onBack: () -> Unit) {
    val items by db.libraryItemDao().observeByType(type).collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var newValue by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        AppHeader(title)
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(newValue, { newValue = it }, label = { Text("New value") }, modifier = Modifier.weight(1f), singleLine = true)
            Button(onClick = { scope.launch { saveLibrary(db, type, newValue); newValue = "" } }, enabled = newValue.isNotBlank()) { Text("Add") }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(items, key = { it.id }) { item ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(item.value, modifier = Modifier.weight(1f))
                        TextButton(onClick = { scope.launch { db.libraryItemDao().delete(item) } }) { Text("Delete") }
                    }
                }
            }
        }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth().padding(12.dp)) { Text("Back") }
    }
}

@Composable
private fun FormColumn(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        content()
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
private fun LibraryEntryField(label: String, value: String, onValueChange: (String) -> Unit, options: List<String>, onSave: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text(label) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                trailingIcon = { TextButton(onClick = { expanded = true }) { Text("▼") } }
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(text = { Text(option) }, onClick = { onValueChange(option); expanded = false })
                }
                if (options.isEmpty()) DropdownMenuItem(text = { Text("No saved values") }, onClick = { expanded = false }, enabled = false)
            }
        }
        TextButton(onClick = onSave, enabled = value.isNotBlank() && value !in options) { Text("Save '$label' to global library") }
    }
}

@Composable
private fun NavigationRow(onBack: () -> Unit, onNext: () -> Unit) {
    Spacer(Modifier.height(4.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("Back") }
        Button(onClick = onNext, modifier = Modifier.weight(1f)) { Text("Next") }
    }
}
