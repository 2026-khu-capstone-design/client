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
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.capstone_design.ui.theme.Capstone_designTheme
import com.google.android.gms.location.*

class MainActivity : ComponentActivity() {
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        enableEdgeToEdge()
        setContent {
            Capstone_designTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(
                        fusedLocationClient = fusedLocationClient,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun MainScreen(
    fusedLocationClient: FusedLocationProviderClient,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val sensorManager = remember { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }

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
    var batteryVoltage by remember { mutableIntStateOf(0) }    // mV
    var batteryCurrent by remember { mutableFloatStateOf(0f) } // mA (소수점 포함)
    var batteryPower by remember { mutableFloatStateOf(0f) }   // mW (소수점 포함)

    LaunchedEffect(Unit) {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        while (true) {
            val intent = context.registerReceiver(null, IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            batteryLevel   = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            batteryVoltage = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
            val currentMicroAmps = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW).toFloat()
            batteryCurrent = currentMicroAmps / 1000f  // µA → mA
            // mV × µA / 1,000,000 = mW (정밀도 유지)
            batteryPower = batteryVoltage.toFloat() * currentMicroAmps / 1_000_000f
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

    // 센서 등록/해제 (SENSOR_DELAY_FASTEST = ~5ms)
    DisposableEffect(isTracking) {
        if (isTracking) {
            sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_FASTEST)
            }
            sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
                sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_FASTEST)
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("센서 데이터", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))

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
                onClick = { isTracking = !isTracking },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isTracking) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                )
            ) {
                Text(if (isTracking) "중지" else "시작")
            }
        }
    }
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
