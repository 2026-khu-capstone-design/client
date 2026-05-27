package com.example.capstone_design

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.capstone_design.ui.theme.Capstone_designTheme
import com.google.android.gms.location.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class AppScreen { SENSOR, MONITOR }

class MainActivity : ComponentActivity() {
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        enableEdgeToEdge()
        setContent {
            Capstone_designTheme {
                var currentScreen by remember { mutableStateOf(AppScreen.SENSOR) }
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                selected = currentScreen == AppScreen.SENSOR,
                                onClick = { currentScreen = AppScreen.SENSOR },
                                icon = { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null) },
                                label = { Text("센서 전송") }
                            )
                            NavigationBarItem(
                                selected = currentScreen == AppScreen.MONITOR,
                                onClick = { currentScreen = AppScreen.MONITOR },
                                icon = { Icon(Icons.Default.Notifications, contentDescription = null) },
                                label = { Text("실시간 수신") }
                            )
                        }
                    }
                ) { innerPadding ->
                    when (currentScreen) {
                        AppScreen.SENSOR -> MainScreen(
                            fusedLocationClient = fusedLocationClient,
                            modifier = Modifier.padding(innerPadding)
                        )
                        AppScreen.MONITOR -> WebSocketScreen(
                            modifier = Modifier.padding(innerPadding)
                        )
                    }
                }
            }
        }
    }
}

@SuppressLint("MissingPermission", "HardwareIds")
@Composable
fun MainScreen(
    fusedLocationClient: FusedLocationProviderClient,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val sensorManager = remember { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    val deviceId = remember {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    }
    val scope = rememberCoroutineScope()

    // GPS 상태
    var latitude by remember { mutableDoubleStateOf(0.0) }
    var longitude by remember { mutableDoubleStateOf(0.0) }
    var accuracy by remember { mutableFloatStateOf(0f) }
    var altitude by remember { mutableDoubleStateOf(0.0) }
    var speed by remember { mutableFloatStateOf(0f) }
    var bearing by remember { mutableFloatStateOf(0f) }
    var isTracking by remember { mutableStateOf(false) }
    var isGpsLoaded by remember { mutableStateOf(false) }
    var hasPermission by remember { mutableStateOf(false) }

    // 가속도계 (m/s²)
    var accX by remember { mutableFloatStateOf(0f) }
    var accY by remember { mutableFloatStateOf(0f) }
    var accZ by remember { mutableFloatStateOf(0f) }

    // 자이로스코프 (rad/s)
    var gyroX by remember { mutableFloatStateOf(0f) }
    var gyroY by remember { mutableFloatStateOf(0f) }
    var gyroZ by remember { mutableFloatStateOf(0f) }

    // 배터리
    var batteryLevel by remember { mutableIntStateOf(0) }
    var batteryVoltage by remember { mutableIntStateOf(0) }
    var batteryCurrent by remember { mutableFloatStateOf(0f) }
    var batteryPower by remember { mutableFloatStateOf(0f) }

    // 배터리 누적 그래프
    val batteryHistory = remember { androidx.compose.runtime.mutableStateListOf<BatteryDataPoint>() }
    var trackingStartTime by remember { mutableLongStateOf(0L) }

    // gRPC 설정
    var grpcHost by remember { mutableStateOf("223.130.139.89") }
    var grpcPort by remember { mutableStateOf("9090") }
    var grpcStatus by remember { mutableStateOf("미연결") }
    var grpcIntervalMs by remember { mutableLongStateOf(500L) }
    var grpcIntervalInput by remember { mutableStateOf("500") }

    // 센서 샘플링 설정
    var sensorDelayMode by remember { mutableIntStateOf(SensorManager.SENSOR_DELAY_FASTEST) }

    // 30초 테스트
    var timedTestCountdown by remember { mutableIntStateOf(-1) }
    var countdownJob by remember { mutableStateOf<Job?>(null) }
    var lastTestSummary by remember { mutableStateOf("") }
    var lastTestPath by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        while (true) {
            val intent = context.registerReceiver(null, IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            batteryLevel   = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            batteryVoltage = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
            val currentMicroAmps = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW).toFloat()
            batteryCurrent = currentMicroAmps / 1000f
            batteryPower = batteryVoltage.toFloat() * currentMicroAmps / 1_000_000f

            if (isTracking) {
                val elapsed = (System.currentTimeMillis() - trackingStartTime) / 1000f
                batteryHistory.add(
                    BatteryDataPoint(
                        elapsedSeconds = elapsed,
                        level = batteryLevel,
                        currentMa = kotlin.math.abs(batteryCurrent),
                        powerMw = kotlin.math.abs(batteryPower)
                    )
                )
            }
            delay(500L)
        }
    }

    // 센서 리스너
    val sensorListener = remember {
        object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> {
                        accX = event.values[0]
                        accY = event.values[1]
                        accZ = event.values[2]
                    }
                    Sensor.TYPE_GYROSCOPE -> {
                        gyroX = event.values[0]
                        gyroY = event.values[1]
                        gyroZ = event.values[2]
                    }
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
    }

    // GPS 콜백
    val locationCallback = remember {
        object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    latitude = location.latitude
                    longitude = location.longitude
                    accuracy = location.accuracy
                    altitude = location.altitude
                    speed = location.speed
                    bearing = location.bearing
                    isGpsLoaded = true
                }
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasPermission = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }

    // 센서 등록/해제
    DisposableEffect(isTracking, sensorDelayMode) {
        if (isTracking) {
            sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                sensorManager.registerListener(sensorListener, it, sensorDelayMode)
            }
            sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
                sensorManager.registerListener(sensorListener, it, sensorDelayMode)
            }
        } else {
            sensorManager.unregisterListener(sensorListener)
        }
        onDispose { sensorManager.unregisterListener(sensorListener) }
    }

    // GPS 등록/해제
    DisposableEffect(isTracking, hasPermission) {
        if (isTracking && hasPermission) {
            val request = LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY, 500L
            ).setMinUpdateIntervalMillis(200L).build()
            fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        } else {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
        onDispose { fusedLocationClient.removeLocationUpdates(locationCallback) }
    }

    // gRPC 스트리밍: isTracking 이 true 이면 500ms 마다 SensorRequest 전송
    var grpcClient by remember { mutableStateOf<SensorGrpcClient?>(null) }

    DisposableEffect(isTracking) {
        if (isTracking) {
            val port = grpcPort.toIntOrNull() ?: 50051
            val client = SensorGrpcClient(grpcHost, port)
            grpcClient = client
            grpcStatus = "연결 중..."

            val job = scope.launch {
                try {
                    val requestFlow = flow {
                        grpcStatus = "스트리밍 중..."
                        while (true) {
                            emit(
                                buildSensorRequest(
                                    deviceId = deviceId,
                                    accX = accX, accY = accY, accZ = accZ,
                                    gyroX = gyroX, gyroY = gyroY, gyroZ = gyroZ,
                                    latitude = latitude, longitude = longitude
                                )
                            )
                            delay(grpcIntervalMs)
                        }
                    }
                    val status = client.streamSensorData(requestFlow)
                    grpcStatus = "완료 (status=$status)"
                } catch (e: Exception) {
                    grpcStatus = "오류: ${e.message}"
                }
            }

            onDispose {
                job.cancel()
                client.shutdown()
                grpcClient = null
                grpcStatus = "미연결"
            }
        } else {
            onDispose { }
        }
    }

    // UI
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("센서 데이터", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))

        // gRPC 서버 설정 카드
        SensorCard(title = "gRPC 서버 설정") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = grpcHost,
                    onValueChange = { grpcHost = it },
                    label = { Text("Host") },
                    modifier = Modifier.weight(1f),
                    enabled = !isTracking,
                    singleLine = true
                )
                OutlinedTextField(
                    value = grpcPort,
                    onValueChange = { grpcPort = it },
                    label = { Text("Port") },
                    modifier = Modifier.width(100.dp),
                    enabled = !isTracking,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("전송 주기", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(60.dp))
                OutlinedTextField(
                    value = grpcIntervalInput,
                    onValueChange = {
                        grpcIntervalInput = it
                        it.toLongOrNull()?.let { v -> if (v > 0) grpcIntervalMs = v }
                    },
                    label = { Text("ms") },
                    modifier = Modifier.width(100.dp),
                    enabled = !isTracking,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text("센서 샘플링", style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(4.dp))
            val sensorDelayOptions = listOf(
                "최고속" to SensorManager.SENSOR_DELAY_FASTEST,
                "게임" to SensorManager.SENSOR_DELAY_GAME,
                "UI" to SensorManager.SENSOR_DELAY_UI,
                "보통" to SensorManager.SENSOR_DELAY_NORMAL
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                sensorDelayOptions.forEach { (label, mode) ->
                    val selected = sensorDelayMode == mode
                    if (selected) {
                        Button(
                            onClick = { sensorDelayMode = mode },
                            modifier = Modifier.weight(1f),
                            enabled = !isTracking,
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                        ) { Text(label, style = MaterialTheme.typography.labelSmall) }
                    } else {
                        OutlinedButton(
                            onClick = { sensorDelayMode = mode },
                            modifier = Modifier.weight(1f),
                            enabled = !isTracking,
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                        ) { Text(label, style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            DataRow("상태", grpcStatus)
        }

        Spacer(modifier = Modifier.height(12.dp))

        // GPS 카드
        SensorCard(title = "GPS") {
            if (isTracking && !isGpsLoaded) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("GPS 신호 수신 중...", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else {
                DataRow("위도", "${"%.6f".format(latitude)}")
                DataRow("경도", "${"%.6f".format(longitude)}")
                DataRow("고도", "${"%.1f".format(altitude)} m")
                DataRow("정확도", "${"%.1f".format(accuracy)} m")
                DataRow("속도", "${"%.1f".format(speed * 3.6f)} km/h")
                DataRow("방위각", "${"%.1f".format(bearing)} °")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 가속도계 카드
        SensorCard(title = "가속도계  (m/s²)") {
            DataRow("X", "%.1f".format(accX).replace("-0.0", "0.0"))
            DataRow("Y", "%.1f".format(accY).replace("-0.0", "0.0"))
            DataRow("Z", "%.1f".format(accZ).replace("-0.0", "0.0"))
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 자이로스코프 카드
        SensorCard(title = "자이로스코프  (rad/s)") {
            DataRow("X", "%.1f".format(gyroX).replace("-0.0", "0.0"))
            DataRow("Y", "%.1f".format(gyroY).replace("-0.0", "0.0"))
            DataRow("Z", "%.1f".format(gyroZ).replace("-0.0", "0.0"))
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 배터리 카드
        SensorCard(title = "배터리") {
            DataRow("잔량", "$batteryLevel %")
            DataRow("전압", "$batteryVoltage mV")
            DataRow("전류", "${"%.2f".format(kotlin.math.abs(batteryCurrent))} mA")
            DataRow("전력 소모", "${"%.2f".format(kotlin.math.abs(batteryPower))} mW")
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 30초 테스트 카운트다운
        if (timedTestCountdown >= 0) {
            SensorCard(title = "30초 배터리 테스트") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("측정 중...", style = MaterialTheme.typography.bodyMedium)
                    Text("${timedTestCountdown}초", style = MaterialTheme.typography.headlineMedium)
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        // 테스트 결과
        if (lastTestSummary.isNotEmpty()) {
            SensorCard(title = "마지막 테스트 결과") {
                Text(lastTestSummary, style = MaterialTheme.typography.bodySmall)
                if (lastTestPath.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "저장: $lastTestPath",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        // 배터리 소모 누적 그래프
        SensorCard(title = "배터리 소모 그래프") {
            BatteryGraph(dataPoints = batteryHistory.toList())
            if (batteryHistory.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                androidx.compose.material3.TextButton(
                    onClick = { batteryHistory.clear() },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("그래프 초기화", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            }) {
                Text("권한 요청")
            }

            Button(
                onClick = {
                    if (!isTracking) {
                        batteryHistory.clear()
                        trackingStartTime = System.currentTimeMillis()
                    }
                    isTracking = !isTracking
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isTracking) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                )
            ) {
                Text(if (isTracking) "중지" else "시작")
            }

            Button(
                onClick = {
                    countdownJob?.cancel()
                    batteryHistory.clear()
                    lastTestSummary = ""
                    lastTestPath = ""
                    trackingStartTime = System.currentTimeMillis()
                    isTracking = true
                    timedTestCountdown = 30
                    countdownJob = scope.launch {
                        for (i in 30 downTo 1) {
                            timedTestCountdown = i
                            delay(1000L)
                        }
                        isTracking = false
                        timedTestCountdown = -1
                        val (summary, path) = saveBatteryTestResult(
                            context, batteryHistory.toList(), grpcIntervalMs, sensorDelayMode
                        )
                        lastTestSummary = summary
                        lastTestPath = path
                    }
                },
                enabled = !isTracking,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.tertiary
                )
            ) {
                Text("30초 테스트")
            }
        }
    }
}

fun saveBatteryTestResult(
    context: Context,
    dataPoints: List<BatteryDataPoint>,
    grpcIntervalMs: Long,
    sensorDelayMode: Int
): Pair<String, String> {
    if (dataPoints.isEmpty()) return Pair("데이터 없음", "")

    val delayLabel = when (sensorDelayMode) {
        SensorManager.SENSOR_DELAY_FASTEST -> "fastest"
        SensorManager.SENSOR_DELAY_GAME -> "game"
        SensorManager.SENSOR_DELAY_UI -> "ui"
        SensorManager.SENSOR_DELAY_NORMAL -> "normal"
        else -> "custom"
    }

    val avgCurrent = dataPoints.map { it.currentMa.toDouble() }.average().toFloat()
    val avgPower = dataPoints.map { it.powerMw.toDouble() }.average().toFloat()
    val startLevel = dataPoints.first().level
    val endLevel = dataPoints.last().level
    val duration = dataPoints.last().elapsedSeconds

    val summary = buildString {
        appendLine("기간: ${"%.1f".format(duration)}초")
        appendLine("평균 전류: ${"%.2f".format(avgCurrent)} mA")
        appendLine("평균 전력: ${"%.2f".format(avgPower)} mW")
        appendLine("배터리: $startLevel% → $endLevel% (${endLevel - startLevel}%)")
        append("gRPC 주기: ${grpcIntervalMs}ms | 센서: $delayLabel")
    }

    val dir = File(context.filesDir, "battery_tests")
    dir.mkdirs()
    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
    val filename = "battery_grpc${grpcIntervalMs}ms_sensor${delayLabel}_$timestamp.csv"
    val file = File(dir, filename)

    file.bufferedWriter().use { writer ->
        writer.write("elapsed_sec,battery_pct,current_ma,power_mw\n")
        dataPoints.forEach { point ->
            writer.write("${point.elapsedSeconds},${point.level},${point.currentMa},${point.powerMw}\n")
        }
    }

    return Pair(summary, file.absolutePath)
}

@Composable
fun SensorCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
fun DataRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
    Spacer(modifier = Modifier.height(4.dp))
}
