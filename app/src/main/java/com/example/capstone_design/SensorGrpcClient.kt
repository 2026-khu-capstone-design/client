package com.example.capstone_design

import demo.kafka.grpc.SensorRequest
import demo.kafka.grpc.SensorServiceGrpcKt
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.TimeUnit

class SensorGrpcClient(host: String, port: Int) {

    private val channel: ManagedChannel = ManagedChannelBuilder
        .forAddress(host, port)
        .usePlaintext()
        .build()

    private val stub = SensorServiceGrpcKt.SensorServiceCoroutineStub(channel)

    /**
     * client-streaming RPC: SensorRequest 를 Flow 로 계속 전송하고,
     * 스트림이 닫히면 서버로부터 SensorResponse.status 를 반환받습니다.
     */
    suspend fun streamSensorData(requests: Flow<SensorRequest>): Int {
        val response = stub.collectSensorData(requests)
        return response.status
    }

    fun shutdown() {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS)
    }
}

/** SensorRequest 빌더 헬퍼 */
fun buildSensorRequest(
    deviceId: String,
    accX: Float, accY: Float, accZ: Float,
    gyroX: Float, gyroY: Float, gyroZ: Float,
    latitude: Double, longitude: Double
): SensorRequest = SensorRequest.newBuilder()
    .setDeviceId(deviceId)
    .setAccX(accX.toDouble())
    .setAccY(accY.toDouble())
    .setAccZ(accZ.toDouble())
    .setGyroX(gyroX.toDouble())
    .setGyroY(gyroY.toDouble())
    .setGyroZ(gyroZ.toDouble())
    .setLatitude(latitude)
    .setLongitude(longitude)
    .setTimestamp(System.currentTimeMillis())
    .build()
