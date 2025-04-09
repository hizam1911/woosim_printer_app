package com.example.woosim_printer_flutter

import io.flutter.embedding.android.FlutterActivity
import androidx.annotation.NonNull
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import androidx.core.content.FileProvider
import java.io.File

//woosim related
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.woosim_printer_flutter.BluetoothPrintService
import com.example.woosim_printer_flutter.MethodChannelManager
import com.woosim.printer.WoosimCmd
import com.woosim.printer.WoosimImage
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.ArrayList

class MainActivity : FlutterActivity() {
    private val CHANNEL_APK = "com.example.woosim_printer_flutter/apk_uri"
    private val APK_FILE_NAME = "update.apk"
    private val CHANNEL_BLUETOOTH = "bluetooth_channel"
    private val REQUEST_CODE_BLUETOOTH = 1001
    private val TAG = "BluetoothDebug"
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var mHandler: Handler? = null
    private var mPrintService: BluetoothPrintService? = null
    private var connectedMacAddress: String? = null

    companion object {
        const val MESSAGE_DEVICE_NAME = 1
        const val MESSAGE_TOAST = 2
        const val MESSAGE_READ = 3
    }

    override fun configureFlutterEngine(@NonNull flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        mHandler = Handler(Looper.getMainLooper())
        mPrintService = BluetoothPrintService(mHandler!!)

        // Register MethodChannelManager
        MethodChannelManager.setMethodChannel(MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL_BLUETOOTH))

        // channel to handle apk update
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL_APK).setMethodCallHandler { call, result ->
            if (call.method == "getApkUri") {
                getApkUri(result)
            } else {
                result.notImplemented()
            }
        }

        // channel to handle bluetooth printer
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL_BLUETOOTH).setMethodCallHandler { call, result ->
            Log.d(TAG, "Flutter called: ${call.method}")

            when (call.method) {
                "sendTestPrint" -> {
                    sendTestPrint()
                    result.success("Test print sent.")
                }

                "printPDF" -> {
                    val filePath: String? = call.argument("filePath")
                    if (filePath != null) {
                        val success = printSelectedPDF(filePath)
                        result.success(success)
                    } else {
                        result.error("INVALID_FILE", "File path is null", null)
                    }
                }

                "downloadAndPrintPDF" -> {
                    val url: String? = call.argument("url")
                    if (url != null) {
                        downloadAndPrintPDF(url, result)
                    } else {
                        result.error("INVALID_URL", "URL is null", null)
                    }
                }

                "disconnectDevice" -> {
                    val isDisconnected = disconnectDevice()
                    if (isDisconnected) {
                        mPrintService?.stop()
                        result.success("Disconnected from printer")
                    } else {
                        result.error("DISCONNECTION_FAILED", "Failed to disconnect", null)
                    }
                }

                "connectToDeviceWithHandling" -> {
                    val macAddress: String? = call.argument("macAddress")
                    if (macAddress != null) {
                        connectToDeviceWithHandling(macAddress, result)
                    } else {
                        result.error("INVALID_MAC", "MAC Address is null", null)
                    }
                }

                "checkPrinterStatus" -> {
                    val isConnected = (mPrintService?.state == BluetoothPrintService.STATE_CONNECTED)
                    result.success(if (isConnected) "CONNECTED" else "DISCONNECTED")
                }

                "connectToDevice" -> {
                    val macAddress: String? = call.argument("macAddress")
                    if (macAddress != null && connectToDevice(macAddress)) {
                        if (mPrintService?.state == BluetoothPrintService.STATE_NONE) {
                            mPrintService?.start()
                        }
                        result.success("Connected to $macAddress")
                    } else {
                        result.error("CONNECTION_FAILED", "Could not connect to device", null)
                    }
                }

                "getPairedDevices" -> result.success(getPairedDevices())

                "initPrinter" -> {
                    try {
                        val byteStream = ByteArrayOutputStream(512)
                        byteStream.write(WoosimCmd.initPrinter())
                        Log.d(TAG, "Printer initialized")
                        result.success(true)
                    } catch (e: IOException) {
                        e.printStackTrace()
                        result.error("IO_ERROR", "Failed to write to byteStream", e.message)
                    }
                }

                "isBluetoothEnabled" -> {
                    val isEnabled = getBluetoothAdapter()?.isEnabled ?: false
                    result.success(isEnabled)
                }

                "enableBluetooth" -> {
                    if (checkBluetoothPermission()) {
                        bluetoothAdapter?.enable()
                        result.success(true)
                    } else {
                        requestBluetoothPermission()
                        result.error("PERMISSION_DENIED", "Bluetooth permission required", null)
                    }
                }

                "disableBluetooth" -> {
                    if (checkBluetoothPermission()) {
                        bluetoothAdapter?.disable()
                        result.success(true)
                    } else {
                        requestBluetoothPermission()
                        result.error("PERMISSION_DENIED", "Bluetooth permission required", null)
                    }
                }

                else -> result.notImplemented()
            }
        }
    }

    private fun getApkUri(result: MethodChannel.Result) {
        val apkFile = File(getExternalFilesDir("Download/APKs"), APK_FILE_NAME)
        if (apkFile.exists()) { // Check if file exists
            try {
                val apkUri = FileProvider.getUriForFile(this, applicationContext.packageName + ".fileprovider", apkFile)
                result.success(apkUri.toString())
            } catch (e: IllegalArgumentException) {
                result.error("FILE_PROVIDER_ERROR", "Failed to get APK URI", e.message)
            }
        } else {
            result.error("FILE_NOT_FOUND", "APK file not found", null)
        }
    }

    private fun checkBluetoothPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun requestBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(
                this, arrayOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN
                ), REQUEST_CODE_BLUETOOTH
            )
        }
    }

    private fun getBluetoothAdapter(): BluetoothAdapter? {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?
        return bluetoothManager?.adapter
    }

    private fun getPairedDevices(): List<String> {
        val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
        val deviceList: MutableList<String> = java.util.ArrayList<String>()

        if (bluetoothAdapter == null) {
            return deviceList // ✅ Return empty list if Bluetooth is not available
        }

        val pairedDevices: Set<BluetoothDevice>? = bluetoothAdapter.getBondedDevices()
        if (pairedDevices != null) {
            for (device: BluetoothDevice in pairedDevices) {
                deviceList.add(device.getName() + " - " + device.getAddress())
            }
        }

        return deviceList
    }

    private fun connectToDevice(macAddress: String): Boolean {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        val device = bluetoothAdapter?.getRemoteDevice(macAddress) ?: return false
        mPrintService?.connect(device)
        return true
    }

    private fun disconnectDevice(): Boolean {
        return try {
            mPrintService?.stop()
            true
        } catch (e: IOException) {
            Log.e(TAG, "Disconnection failed: ${e.message}")
            false
        }
    }

    private fun downloadAndPrintPDF(fileUrl: String, result: MethodChannel.Result) {
        Thread {
            try {
                val pdfFile = downloadPDF(fileUrl)
                if (pdfFile != null) {
                    printPDF(pdfFile)
                    result.success("Berjaya mencetak dokumen.")
                } else {
                    result.error("DOWNLOAD_FAILED", "Gagal mencetak dokumen.", null)
                }
            } catch (e: Exception) {
                result.error("PRINT_FAILED", "Error printing PDF: ${e.message}", null)
            }
        }.start()
    }

    private fun downloadPDF(fileUrl: String): File? {
        return try {
            val url = URL(fileUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connect()

            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null

            val file = File(cacheDir, "downloaded.pdf")
            FileOutputStream(file).use { outputStream ->
                connection.inputStream.use { input ->
                    input.copyTo(outputStream)
                }
            }
            file
        } catch (e: Exception) {
            Log.e("Bluetooth", "Error downloading PDF: ${e.message}")
            null
        }
    }

    private fun printPDF(pdfFile: File) {
        try {
            val pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)

            if (mPrintService == null) {
                Log.e("Bluetooth", "Print service is not initialized.")
                return
            }

            mPrintService?.write(WoosimCmd.initPrinter())
            Log.d("Bluetooth", "Initialized printer")

            PdfRenderer(pfd).use { renderer ->
                for (i in 0 until renderer.pageCount) {
                    // Original page is resized to fit 2 inch roll paper width (384 dot).
                    // It can be changed to 576 and 832 for 3 and 4 inch roll paper respectively.
                    renderer.openPage(i).use { page ->
                        val printerWidth = 832
                        val scaledHeight = (page.height * printerWidth) / page.width

                        val bitmap = Bitmap.createBitmap(printerWidth, scaledHeight, Bitmap.Config.ARGB_8888)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)

                        val printData = WoosimImage.printStdModeBitmap(bitmap)
                        if (printData != null && printData.isNotEmpty()) {
                            mPrintService?.write(printData)
                        } else {
                            Log.e("Bluetooth", "Failed to convert bitmap to print format.")
                        }
                    }
                }
            }

            mPrintService?.write(WoosimCmd.printLineFeed(2))
            Log.d("Bluetooth", "Finished printing document")

        } catch (e: IOException) {
            Log.e("Bluetooth", "Error printing PDF: ${e.message}")
        }
    }

    private fun sendTestPrint() {
        mPrintService?.write("Hello, Woosim Printer!\n".toByteArray())
        mPrintService?.write(WoosimCmd.printLineFeed(2))
    }

    private fun connectToDeviceWithHandling(macAddress: String, result: MethodChannel.Result) {
        if (mPrintService?.state == BluetoothPrintService.STATE_CONNECTED && connectedMacAddress == macAddress) {
            // ✅ Only return "ALREADY_CONNECTED" if the device is actually still connected
            result.success("Already connected to $macAddress")
            return
        }

        // ✅ If previously connected, stop and reset before connecting again
        if (connectedMacAddress != null) {
            mPrintService?.stop()
            connectedMacAddress = null
            MethodChannelManager.sendStatusToFlutter("DISCONNECTED") // ✅ Notify Flutter
        }

        // Try connecting to new device
        if (connectToDevice(macAddress)) {
            connectedMacAddress = macAddress
            MethodChannelManager.sendStatusToFlutter("CONNECTED", macAddress)
            result.success("Connected to $macAddress")
        } else {
            result.error("CONNECTION_FAILED", "Could not connect", null)
        }
    }

    private fun printSelectedPDF(filePath: String): Boolean {
        try {
            val pdfFile = File(filePath)
            val pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)

            if (mPrintService == null || mPrintService?.state != BluetoothPrintService.STATE_CONNECTED) {
                Log.e("Bluetooth", "Printer not connected.")
                return false
            }

            mPrintService?.write(WoosimCmd.initPrinter()) // Initialize printer
            Log.d("Bluetooth", "Initialized printer")

            PdfRenderer(pfd).use { renderer ->
                for (i in 0 until renderer.pageCount) {
                    // Original page is resized to fit 2 inch roll paper width (384 dot).
                    // It can be changed to 576 and 832 for 3 and 4 inch roll paper respectively.
                    renderer.openPage(i).use { page ->
                        val printerWidth = 832 // ⚡ Ensure correct Woosim i450 printer width
                        val scaledHeight = (page.height * printerWidth) / page.width

                        // ⚡ Create a grayscale bitmap (prevents all-black printing)
                        val bitmap = Bitmap.createBitmap(printerWidth, scaledHeight, Bitmap.Config.ARGB_8888)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)

                        // Convert to proper black & white using dithering
                        val processedBitmap = convertToBlackAndWhite(bitmap)

                        // Convert processed bitmap to Woosim print data
                        val printData = WoosimImage.printStdModeBitmap(processedBitmap)
                        if (printData != null && printData.isNotEmpty()) {
                            mPrintService?.write(printData)
                        } else {
                            Log.e("Bluetooth", "Failed to convert bitmap to print format.")
                        }
                    }
                }
            }

            mPrintService?.write(WoosimCmd.printLineFeed(3)) // Feed paper
            Log.d("Bluetooth", "Finished printing PDF")

            return true
        } catch (e: IOException) {
            Log.e("Bluetooth", "Error printing PDF: ${e.message}")
            return false
        }
    }

    private fun convertToBlackAndWhite(original: Bitmap): Bitmap {
        val width = original.width
        val height = original.height
        val bwBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        for (x in 0 until width) {
            for (y in 0 until height) {
                val pixel = original.getPixel(x, y)
                val red = (pixel shr 16) and 0xFF
                val green = (pixel shr 8) and 0xFF
                val blue = pixel and 0xFF

                // Convert to grayscale
                val gray = (red * 0.3 + green * 0.59 + blue * 0.11).toInt()

                // Apply threshold for black/white conversion
                val bwColor = if (gray > 128) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()

                bwBitmap.setPixel(x, y, bwColor)
            }
        }

        return bwBitmap
    }

}