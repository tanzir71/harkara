package io.github.tanzir71.harkara.mesh

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import io.github.tanzir71.harkara.core.mesh.GattEvents
import io.github.tanzir71.harkara.core.mesh.GattLayer
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Android's Bluetooth LE stack behind [GattLayer]: one GATT server with the Harkara service, an advertiser
 * (service UUID; the 4-byte token as scan-response service data, no device name), a filtered scanner, and
 * GATT clients for the links this phone dials. Callers make sure the Bluetooth permissions are granted
 * ([MeshPermissions]); any SecurityException turns the radio off instead of crashing. Unverified on hardware.
 */
@SuppressLint("MissingPermission")
class AndroidGattLayer(private val context: Context) : GattLayer {
    private val manager: BluetoothManager? = context.getSystemService(BluetoothManager::class.java)
    @Volatile private var events: GattEvents? = null
    private var server: BluetoothGattServer? = null
    private var serverCharacteristic: BluetoothGattCharacteristic? = null
    private val clients = ConcurrentHashMap<String, BluetoothGatt>()
    private val centrals = ConcurrentHashMap<String, BluetoothDevice>()
    private val preparedWrites = ConcurrentHashMap<String, ByteArrayOutputStream>()
    private var scanning = false

    /** Bluetooth LE exists on this phone and is switched on. */
    val available: Boolean
        get() = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) && manager?.adapter?.isEnabled == true

    override fun open(events: GattEvents): Boolean {
        if (!available) return false
        this.events = events
        return runCatching {
            val server = manager!!.openGattServer(context, serverCallback) ?: return false
            val characteristic = BluetoothGattCharacteristic(
                CHARACTERISTIC_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_WRITE,
            )
            characteristic.addDescriptor(
                BluetoothGattDescriptor(CCCD_UUID, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE),
            )
            server.addService(BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply { addCharacteristic(characteristic) })
            this.server = server
            serverCharacteristic = characteristic
            true
        }.getOrElse {
            this.events = null
            false
        }
    }

    override fun close() {
        runCatching { scan(false) }
        runCatching { manager?.adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
        clients.values.forEach { g -> runCatching { g.disconnect() }; runCatching { g.close() } }
        clients.clear()
        centrals.clear()
        preparedWrites.clear()
        runCatching { server?.close() }
        server = null
        events = null
    }

    override fun advertise(token: ByteArray) {
        val advertiser = manager?.adapter?.bluetoothLeAdvertiser ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder().setIncludeDeviceName(false).addServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        val response = AdvertiseData.Builder().setIncludeDeviceName(false).addServiceData(ParcelUuid(SERVICE_UUID), token).build()
        runCatching { advertiser.startAdvertising(settings, data, response, advertiseCallback) }
    }

    @Synchronized
    override fun scan(on: Boolean) {
        if (on == scanning) return
        val scanner = manager?.adapter?.bluetoothLeScanner ?: return
        runCatching {
            if (on) {
                val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
                val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build()
                scanner.startScan(listOf(filter), settings, scanCallback)
            } else {
                scanner.stopScan(scanCallback)
            }
            scanning = on
        }
    }

    override fun connect(address: String) {
        val device = runCatching { manager?.adapter?.getRemoteDevice(address) }.getOrNull() ?: return
        runCatching {
            device.connectGatt(context, false, ClientCallback(address), BluetoothDevice.TRANSPORT_LE)?.let { clients[address] = it }
        }
    }

    override fun disconnect(address: String) {
        clients.remove(address)?.let { g -> runCatching { g.disconnect() }; runCatching { g.close() } }
    }

    override fun requestMtu(address: String, mtu: Int) {
        clients[address]?.let { runCatching { it.requestMtu(mtu) } }
    }

    override fun subscribe(address: String) {
        clients[address]?.let { runCatching { it.discoverServices() } }
    }

    @Suppress("DEPRECATION")
    override fun write(address: String, bytes: ByteArray): Boolean {
        val gatt = clients[address] ?: return false
        val characteristic = gatt.getService(SERVICE_UUID)?.getCharacteristic(CHARACTERISTIC_UUID) ?: return false
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(characteristic, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothStatusCodes.SUCCESS
            } else {
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                characteristic.value = bytes
                gatt.writeCharacteristic(characteristic)
            }
        }.getOrDefault(false)
    }

    @Suppress("DEPRECATION")
    override fun notify(address: String, bytes: ByteArray): Boolean {
        val server = server ?: return false
        val characteristic = serverCharacteristic ?: return false
        val device = centrals[address] ?: return false
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                server.notifyCharacteristicChanged(device, characteristic, false, bytes) == BluetoothStatusCodes.SUCCESS
            } else {
                characteristic.value = bytes
                server.notifyCharacteristicChanged(device, characteristic, false)
            }
        }.getOrDefault(false)
    }

    private val advertiseCallback = object : AdvertiseCallback() {}

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val token = result.scanRecord?.getServiceData(ParcelUuid(SERVICE_UUID))
            events?.onScanResult(result.device.address, token, result.rssi)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach { onScanResult(0, it) }
    }

    private inner class ClientCallback(private val address: String) : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> events?.onConnected(address)
                BluetoothProfile.STATE_DISCONNECTED -> {
                    clients.remove(address)
                    runCatching { gatt.close() }
                    events?.onDisconnected(address)
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            events?.onMtuChanged(address, if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23)
        }

        @Suppress("DEPRECATION")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val characteristic = gatt.getService(SERVICE_UUID)?.getCharacteristic(CHARACTERISTIC_UUID)
            val descriptor = characteristic?.getDescriptor(CCCD_UUID)
            if (status != BluetoothGatt.GATT_SUCCESS || descriptor == null) {
                runCatching { gatt.disconnect() }
                return
            }
            runCatching {
                gatt.setCharacteristicNotification(characteristic, true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(descriptor)
                }
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid == CCCD_UUID && status == BluetoothGatt.GATT_SUCCESS) events?.onSubscribed(address) else runCatching { gatt.disconnect() }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            events?.onNotification(address, value)
        }

        @Deprecated("Android 12 and below")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) characteristic.value?.let { events?.onNotification(address, it.copyOf()) }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            events?.onWriteDone(address)
        }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                centrals.remove(device.address)
                preparedWrites.remove(device.address)
                events?.onCentralDisconnected(device.address)
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            events?.onCentralMtu(device.address, mtu)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            val subscribe = descriptor.uuid == CCCD_UUID && value?.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == true
            if (responseNeeded) respond(device, requestId, offset, value)
            if (subscribe) {
                centrals[device.address] = device
                events?.onCentralSubscribed(device.address)
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            if (characteristic.uuid != CHARACTERISTIC_UUID || value == null || value.size > 512 ||
                offset < 0 || offset.toLong() + value.size > 512) {
                if (responseNeeded) runCatching { server?.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH, offset, null) }
                return
            }
            if (preparedWrite && (preparedWrites[device.address]?.size() ?: 0) != offset) {
                if (responseNeeded) runCatching { server?.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null) }
                return
            }
            if (preparedWrite && !preparedWrites.containsKey(device.address) && preparedWrites.size >= 8) {
                if (responseNeeded) runCatching { server?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null) }
                return
            }
            if (responseNeeded) respond(device, requestId, offset, value)
            val bytes = value
            if (preparedWrite) {
                preparedWrites.getOrPut(device.address) { ByteArrayOutputStream() }.write(bytes)
            } else {
                events?.onCentralWrite(device.address, bytes)
            }
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            respond(device, requestId, 0, null)
            val buffered = preparedWrites.remove(device.address) ?: return
            if (execute) events?.onCentralWrite(device.address, buffered.toByteArray())
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            events?.onNotificationSent(device.address)
        }
    }

    private fun respond(device: BluetoothDevice, requestId: Int, offset: Int, value: ByteArray?) {
        runCatching { server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value) }
    }

    companion object {
        /** Harkara's mesh service and its one characteristic (random UUIDs, fixed forever). */
        val SERVICE_UUID: UUID = UUID.fromString("6a4e0b30-8c2f-4d3e-9b51-7f2a0c1d4e57")
        val CHARACTERISTIC_UUID: UUID = UUID.fromString("6a4e0b31-8c2f-4d3e-9b51-7f2a0c1d4e57")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
