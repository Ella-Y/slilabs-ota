package com.sensemore.slilabs.ota

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.provider.Settings
import android.provider.Settings.SettingNotFoundException
import android.text.TextUtils
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.IOException
import java.util.Arrays
import java.util.Locale
import java.util.Timer
import java.util.TimerTask
import java.util.UUID

@SuppressLint("MissingPermission")
class OtaActivity : AppCompatActivity() {
    private var browseFileButton: Button? = null
    private var startOtaButton: Button? = null
    private var macAddressTextView: TextView? = null
    private var fileNameTextView: TextView? = null
    private var mBluetoothAdapter: BluetoothAdapter? = null
    private var macAddress: String? = null
    private var connectionTimeout: Timer? = null
    private var device: BluetoothDevice? = null
    private var deviceName: String? = null
    private var handler: Handler? = null
    private val MTU = 247
    private var firmwareFile: ByteArray? = null
    private var index = 0

    internal object State {
        const val Connecting = "Connecting"
        const val ResetDFU = "ResetDFU"
        const val Reconnecting = "Reconnecting"
        const val OtaBegin = "Ota Begin"
        const val OtaUpload = "OtaUpload"
        const val OtaEnd = "OtaEnd"
        const val Disconnecting = "Disconnecting"
        const val Ready = "Ready"
    }

    private val progressMap = HashMap<String, ProgressBar?>()
    private val launcherFileChooser =
        registerForActivityResult<Intent, ActivityResult>(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
            if (result.resultCode == RESULT_OK) {
                val i = result.data
                if (i != null && i.data != null) {
                    PrepareFile(i.data)
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ota)
        handler = Handler()
        browseFileButton = findViewById(R.id.browseFile)
        startOtaButton = findViewById(R.id.startOta)
        macAddressTextView = findViewById(R.id.macAddress)
        fileNameTextView = findViewById(R.id.fileName)
        progressMap[State.Connecting] = findViewById(R.id.connectingProgress)
        progressMap[State.ResetDFU] = findViewById(R.id.resetDFUProgress)
        progressMap[State.Reconnecting] = findViewById(R.id.reconnectingProgress)
        progressMap[State.OtaBegin] = findViewById(R.id.otaBeginProgress)
        progressMap[State.OtaUpload] = findViewById(R.id.otaUploadProgress)
        progressMap[State.OtaEnd] = findViewById(R.id.otaEndProgress)
        progressMap[State.Disconnecting] = findViewById(R.id.disconnectingProgress)
        SetProgress(State.Ready)

        browseFileButton!!.setOnClickListener {
            val chooseFile = Intent(Intent.ACTION_OPEN_DOCUMENT)
            chooseFile.setType("*/*")
            chooseFile.addCategory(Intent.CATEGORY_OPENABLE)
            launcherFileChooser.launch(chooseFile)
        }

        startOtaButton!!.setOnClickListener {
            if (CheckBlePermissions() && CheckBluetoothEnabled() && CheckLocationEnabled() && CheckMacAddress()) {
                Toast.makeText(applicationContext, "BEGIN", Toast.LENGTH_SHORT).show()
                ConnectDevice()
            }
        }
    }


    private fun ConnectDevice() {
        SetProgress(State.Connecting)
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        mBluetoothAdapter = bluetoothManager.adapter
        connectionTimeout = Timer()

        //create timer for connection timeout
        connectionTimeout!!.schedule(object : TimerTask() {
            override fun run() {
                ToastMessage("Connection timeout, make sure you write mac address correct and ble device is discoverable")
            }
        }, CONNECT_TIMEOUT)
        device = mBluetoothAdapter!!.getRemoteDevice(macAddress)

        // Here we are connecting to target device
        device.connectGatt(applicationContext, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                Log.i("OTA", "state $newState")
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.i(
                        "OTA",
                        "Connected " + gatt.device.getName() + " address: " + gatt.device.getAddress()
                    )
                    deviceName = gatt.device.getName()
                    connectionTimeout!!.cancel()
                    connectionTimeout!!.purge()
                    gatt.discoverServices() // Directly discovering services
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.i("OTA", "Disconnecting ")
                    gatt.close()
                    gatt.disconnect()
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i("OTA", "onServicesDiscovered deviceName: $deviceName")
                    //We have connected to device and discovered services
                    //if OTA_SERVICE has OTA_DATA_CHARACTERISTIC target device already in dfu mode
                    if (gatt.getService(OTA_SERVICE)
                            .getCharacteristic(OTA_DATA_CHARACTERISTIC) != null
                    ) {
                        ConnectOtaDevice(gatt)
                    } else {
                        ResetDFU(gatt)
                    }
                }
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                Log.i("OTA", "onCharacteristicWrite " + characteristic.uuid.toString())
                if (characteristic.uuid == OTA_CONTROL_CHARACTERISTIC && characteristic.value[0].toInt() == 0x00) {
                    //target device ´rebooting into OTA
                    ConnectDelayedForOTA(gatt) //reconnect
                }
            }
        })
    }

    private fun ResetDFU(gatt: BluetoothGatt) {
        SetProgress(State.ResetDFU)
        //Writing 0x00 to control characteristic to reboot target device into DFU mode
        handler!!.post {
            Log.i("OTA", "OTA RESET INTO DFU")
            val service = gatt.getService(OTA_SERVICE)
            val characteristic = service.getCharacteristic(OTA_CONTROL_CHARACTERISTIC)
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            characteristic.setValue(byteArrayOf(0x00))
            gatt.writeCharacteristic(characteristic) // result will be handled in onCharacteristicWrite callback of gatt.
        }
    }


    private fun RequestMTU(gatt: BluetoothGatt) {
        gatt.requestMtu(MTU + 3)
    } //I dunno why but we neet to request 3 more for what required :/

    private fun OtaBegin(gatt: BluetoothGatt) {
        SetProgress(State.OtaBegin)

        //Writing 0x00 to control characteristic to DFU mode  target device begins OTA process
        handler!!.postDelayed({
            val service = gatt.getService(OTA_SERVICE)
            val characteristic = service.getCharacteristic(OTA_CONTROL_CHARACTERISTIC)
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            characteristic.setValue(byteArrayOf(0x00))
            gatt.writeCharacteristic(characteristic)
        }, 500)
    }

    private fun OtaUpload(gatt: BluetoothGatt) {
        SetProgress(State.OtaUpload)
        ToastMessage("Uploading!")
        index = 0
        Thread {
            var last = false
            var packageCount = 0
            while (!last) {
                var payload: ByteArray? = ByteArray(MTU)
                if (index + MTU >= firmwareFile.size) {
                    val restSize = firmwareFile.size - index
                    System.arraycopy(firmwareFile, index, payload, 0, restSize) //copy rest bytes
                    last = true
                } else {
                    payload = Arrays.copyOfRange(firmwareFile, index, index + MTU)
                }
                val service = gatt.getService(OTA_SERVICE)
                val characteristic = service.getCharacteristic(OTA_DATA_CHARACTERISTIC)
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                characteristic.setValue(payload)
                Log.d("OTA", "index :" + index + " firmware lenght:" + firmwareFile.size)
                while (!gatt.writeCharacteristic(characteristic)) { // attempt to write until getting success
                    try {
                        Thread.sleep(5)
                    } catch (e: InterruptedException) {
                        e.printStackTrace()
                    }
                }
                packageCount = packageCount + 1
                index = index + MTU
            }
            Log.i("OTA", "OTA UPLOAD SEND DONE")
            OtaEnd(gatt)
        }.start()
    }

    private fun OtaEnd(gatt: BluetoothGatt) {
        SetProgress(State.OtaEnd)
        handler!!.postDelayed({
            Log.i("OTA", "OTA END")
            val endCharacteristic = gatt.getService(OTA_SERVICE).getCharacteristic(
                OTA_CONTROL_CHARACTERISTIC
            )
            endCharacteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            endCharacteristic.setValue(byteArrayOf(0x03))
            var i = 0
            while (!gatt.writeCharacteristic(endCharacteristic)) {
                i++
                Log.i("OTA", "Failed to write end 0x03 retry:$i")
            }
        }, 1500)
    }



    private fun ConnectOtaDevice(gatt: BluetoothGatt?) {
        if (gatt != null) {
            gatt.close()
            gatt.disconnect()
        }
        device = mBluetoothAdapter!!.getRemoteDevice(macAddress)
        device.connectGatt(this@OtaActivity, false, object : BluetoothGattCallback() {
            // This is OTA devices callback
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    //Here, we are connected to target device which is in DFU mode
                    deviceName = device.getName()
                    gatt.discoverServices() // Directly discovering services
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.i("OTA", "Disconnecting ")
                    gatt.close()
                    gatt.disconnect()
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i("OTA", "onServicesDiscovered deviceName: $deviceName")
                    //We have connected to device and discovered services
                    OtaBegin(gatt)
                }
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                super.onCharacteristicRead(gatt, characteristic, status)
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                Log.i("OTA", "onCharacteristicWrite " + characteristic.uuid.toString())
                if (characteristic.uuid == OTA_CONTROL_CHARACTERISTIC && characteristic.value[0].toInt() == 0x00) {
                    //OTA Begin written
                    RequestMTU(gatt) // will be handled in onMtuChanged callback of gatt
                } else if (characteristic.uuid == OTA_CONTROL_CHARACTERISTIC && characteristic.value[0].toInt() == 0x03) {
                    //OTA End written
                    SetProgress(State.Disconnecting)
                    ToastMessage("Upload Done!") // will be handled in onMtuChanged callback of gatt
                    RebootTargetDevice(gatt)
                } else if (characteristic.uuid == OTA_CONTROL_CHARACTERISTIC && characteristic.value[0].toInt() == 0x04) {
                    //OTA End written
                    SetProgress(State.Ready)
                    ToastMessage("Upload Done!") // will be handled in onMtuChanged callback of gatt
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                super.onMtuChanged(gatt, mtu, status)
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i("OTA", "onMtuChanged mtu: $mtu")
                    //We have successfully request MTU we can start upload process
                    OtaUpload(gatt)
                }
            }
        })
    }

    private fun RebootTargetDevice(gatt: BluetoothGatt) {
        handler!!.postDelayed({
            val service = gatt.getService(OTA_SERVICE)
            val characteristic = service.getCharacteristic(OTA_CONTROL_CHARACTERISTIC)
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            characteristic.setValue(byteArrayOf(0x04))
            gatt.writeCharacteristic(characteristic)
        }, 500)
    }

    private fun ConnectDelayedForOTA(gatt: BluetoothGatt) {
        SetProgress(State.Reconnecting)

        //after writing 0x00 to target device device will reboot into DFU mode
        //We are waiting a little bit just to be sure
        handler!!.postDelayed({
            Log.i("OTA", "CONNECTING FOR OTA")
            ConnectOtaDevice(gatt)
        }, 5000)
    }

    private fun ToastMessage(message: String?) {
        runOnUiThread { Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show() }
    }

    private fun SetProgress(state: String) {
        runOnUiThread {
            for (bar in progressMap.values) {
                bar!!.visibility = View.INVISIBLE
            }
            if (progressMap[state] != null) {
                progressMap[state]!!.visibility = View.VISIBLE
            }
        }
    }

    private fun CheckMacAddress(): Boolean {
        macAddress =
            macAddressTextView!!.text.toString().trim().uppercase(Locale.US)
        return if (BluetoothAdapter.checkBluetoothAddress(macAddress)) {
            true
        } else {
            ToastMessage("Mac Address not valid")
            false
        }
    }

    private fun CheckBluetoothEnabled(): Boolean {
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        return if (mBluetoothAdapter == null) {
            ToastMessage("Doesnt support bluetooth")
            false
        } else if (!mBluetoothAdapter!!.isEnabled) {
            ToastMessage("Please enable your bluetooth")
            false
        } else {
            true
        }
    }

    private fun CheckBlePermissions(): Boolean {
        val permissions = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val notGranted = getNotGranted(permissions)
        return notGranted.size == 0
    }

    private fun getNotGranted(permissions: List<String>): Array<String> {
        val list = ArrayList<String>()
        for (item in permissions) {
            if (ContextCompat.checkSelfPermission(this, item) == PackageManager.PERMISSION_DENIED) {
                list.add(item)
            }
        }
        return list.toTypedArray<String>()
    }


    fun CheckLocationEnabled(): Boolean {
        var locationMode = 0
        locationMode = try {
            Settings.Secure.getInt(this.contentResolver, Settings.Secure.LOCATION_MODE)
        } catch (e: SettingNotFoundException) {
            e.printStackTrace()
            ToastMessage("Doesn't support location")
            return false
        }
        if (locationMode == Settings.Secure.LOCATION_MODE_OFF) {
            ToastMessage("Please enable location")
            return false
        } else {
            return true
        }

    }

    private fun PrepareFile(uri: Uri?) {
        ToastMessage(uri!!.lastPathSegment)
        fileNameTextView!!.text = uri.lastPathSegment
        startOtaButton!!.isEnabled = true
        try {
            val `in` = contentResolver.openInputStream(uri)
            firmwareFile = ByteArray(`in`!!.available())
            `in`.read(firmwareFile, 0, `in`.available())
            `in`.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )
        if (requestCode == READ_EXTERNAL_STORAGE_REQUESTCODE) {
            if (grantResults.size > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {
                browseFileButton!!.callOnClick()
            } else {
                ToastMessage("You should give permission for read storage to continue")
            }
        } else if (requestCode == BLE_PERMISSIO_REQUSETCODE) {
            if (Arrays.stream(grantResults)
                    .allMatch { x: Int -> x == PackageManager.PERMISSION_GRANTED }
            ) {
                startOtaButton!!.callOnClick()
            } else {
                ToastMessage("You should give Location and Bluetooth to continue")
            }
        }
    }

    companion object {
        private val OTA_CONTROL_CHARACTERISTIC =
            UUID.fromString("F7BF3564-FB6D-4E53-88A4-5E37E0326063")
        private val OTA_DATA_CHARACTERISTIC =
            UUID.fromString("984227F3-34FC-4045-A5D0-2C581F81A153")
        private val OTA_SERVICE = UUID.fromString("1d14d6ee-fd63-4fa1-bfa4-8f47b42119f0")
        private const val PICKFILE_REQUESTCODE = 1
        private const val BLE_PERMISSIO_REQUSETCODE = 3
        private const val READ_EXTERNAL_STORAGE_REQUESTCODE = 2
        private const val CONNECT_TIMEOUT: Long = 10000
    }
}
