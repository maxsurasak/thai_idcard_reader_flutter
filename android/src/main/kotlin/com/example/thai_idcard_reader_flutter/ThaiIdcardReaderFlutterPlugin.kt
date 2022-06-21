package com.example.thai_idcard_reader_flutter

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.*
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import androidx.annotation.NonNull
import com.acs.smartcard.Reader
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import org.json.JSONObject
import java.nio.charset.*
import java.util.*

const val ACTION_USB_PERMISSION = "com.example.thai_idcard_reader_flutter.USB_PERMISSION"
const val ACTION_USB_ATTACHED = "android.hardware.usb.action.USB_DEVICE_ATTACHED"
const val ACTION_USB_DETACHED = "android.hardware.usb.action.USB_DEVICE_DETACHED"
const val ACTION_USB_GRANTED = "android.hardware.usb.action.EXTRA_PERMISSION_GRANTED"

private fun pendingPermissionIntent(context: Context) =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
    } else {
      PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION), 0)
    }

/** ThaiIdcardReaderFlutterPlugin */
class ThaiIdcardReaderFlutterPlugin : FlutterPlugin, MethodCallHandler, EventChannel.StreamHandler {

  private lateinit var channel: MethodChannel

  private var usbEventChannel: EventChannel? = null

  private var readerEventChannel: EventChannel? = null
  private var eventSink: EventChannel.EventSink? = null

  private var applicationContext: Context? = null
  private var usbManager: UsbManager? = null

  // acs
  private var mReader: Reader? = null
  private var device: UsbDevice? = null

  private var readerStreamHandler: ReaderStream? = null

  private val usbReceiver: BroadcastReceiver =
      object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
          val action = intent.action
          val reader = mReader
          var dev: HashMap<String, Any?>?
          device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
          if (action == ACTION_USB_ATTACHED) {
            if (usbManager!!.hasPermission(device)) {
              dev = serializeDevice(device)
              dev["isAttached"] = true
              dev["hasPermission"] = true
              eventSink?.success(dev)
            } else {
              context.registerReceiver(receiver, IntentFilter(ACTION_USB_PERMISSION))
              usbManager?.requestPermission(device, pendingPermissionIntent(context))
              dev = serializeDevice(device)
              dev["isAttached"] = true
              dev["hasPermission"] = false
              eventSink?.success(dev)
            }
          } else if (action == ACTION_USB_DETACHED) {
            reader?.close()
            dev = serializeDevice(device)
            dev["isAttached"] = false
            dev["hasPermission"] = false
            eventSink?.success(dev)
          } else if (action == ACTION_USB_PERMISSION) {
            if (usbManager!!.hasPermission(device)) {
              dev = serializeDevice(device)
              reader?.open(device)
              dev["isAttached"] = true
              dev["hasPermission"] = true
              eventSink?.success(dev)
              if (reader!!.isSupported(device)) {
                readerStreamHandler?.setReader(reader)
              }
            }
          }
        }
      }

  fun serializeDevice(device: UsbDevice?): HashMap<String, Any?> {
    val dev: HashMap<String, Any?> = HashMap()
    dev["identifier"] = device?.deviceName
    dev["vendorId"] = device?.vendorId
    dev["productId"] = device?.productId
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      dev["manufacturerName"] = device?.manufacturerName
      dev["productName"] = device?.productName
      dev["interfaceCount"] = device?.interfaceCount
    }
    dev["deviceId"] = device?.deviceId
    return dev
  }

  override fun onListen(arguments: Any?, eventSink: EventChannel.EventSink?) {
    this.eventSink = eventSink
  }

  override fun onCancel(arguments: Any?) {
    eventSink = null
    usbEventChannel = null
  }

  override fun onAttachedToEngine(
      @NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding
  ) {
    channel =
        MethodChannel(flutterPluginBinding.binaryMessenger, "thai_idcard_reader_flutter_channel")
    channel.setMethodCallHandler(this)
    applicationContext = flutterPluginBinding.applicationContext
    usbManager = applicationContext?.getSystemService(Context.USB_SERVICE) as UsbManager
    mReader = Reader(usbManager)

    val usbEventChannel = EventChannel(flutterPluginBinding.binaryMessenger, "usb_stream_channel")
    usbEventChannel?.setStreamHandler(this)

    val readerEventChannel =
        EventChannel(flutterPluginBinding.binaryMessenger, "reader_stream_channel")
    readerStreamHandler = ReaderStream()
    readerEventChannel?.setStreamHandler(readerStreamHandler)

    var filter = IntentFilter(ACTION_USB_PERMISSION)
    filter.addAction(ACTION_USB_DETACHED)
    filter.addAction(ACTION_USB_ATTACHED)
    applicationContext!!.registerReceiver(usbReceiver, filter)
  }

  override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
    channel.setMethodCallHandler(null)
    usbManager = null
    mReader = null
    applicationContext = null
    device = null
    usbEventChannel?.setStreamHandler(null)
    readerEventChannel?.setStreamHandler(null)
    readerStreamHandler = null
  }

  private val receiver =
      object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
          context.unregisterReceiver(this)
          val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
          val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
          if (!granted) {
            println("Permission denied: ${device?.deviceName}")
          }
        }
      }

  override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
    when (call.method) {
      "getPlatformVersion" -> {
        result.success("Android ${Build.VERSION.RELEASE}")
      }
      "readAll" -> {
        var apdu = ThaiADPU()
        val reader = mReader ?: return result.error("IllegalState", "mReader null", null)
        try {
          val res: Map<String, Any?> = apdu.readAll(reader)
          result.success(JSONObject(res).toString())
        } catch (e: Exception) {
          result.success("ERR ${e.toString()}")
        }
      }
      "read" -> {
        var apdu = ThaiADPU()
        val selected = call.argument<List<String>>("selected")
        val selectedArray: Array<String> = selected!!.toTypedArray()
        val reader = mReader ?: return result.error("IllegalState", "mReader null", null)
        try {
          val res: Map<String, Any?> = apdu.readSpecific(reader, selectedArray)

          result.success(JSONObject(res).toString())
        } catch (e: Exception) {
          result.success("ERR ${e.toString()}")
        }
      }
      "powerOn" -> {
        var apdu = ThaiADPU()
        val reader = mReader ?: return result.error("IllegalState", "mReader null", null)
        var slotNum = 0
        try {
          // power on
          var atr = reader.power(slotNum, Reader.CARD_WARM_RESET);
          Log.d("TAG", atr.toString() ?: "")

          // set state
          val protocol: Int = reader.setProtocol(slotNum, Reader.PROTOCOL_T0)
          Log.d("TAG", "Set protocol - $protocol")

          // get id card info
          val select = byteArrayOf(
            0x00.toByte(),
            0xA4.toByte(),
            0x04.toByte(),
            0x00.toByte(),
            0x08.toByte(),
            0xA0.toByte(),
            0x00.toByte(),
            0x00.toByte(),
            0x00.toByte(), 0x54.toByte(), 0x48.toByte(), 0x00.toByte(), 0x01.toByte()
          )
          val response = ByteArray(300)
          val responsLength: Int
          responsLength = reader.transmit(slotNum, select, select.size, response, response.size)
          Log.d("TAG", "Response byte - $responsLength")
          result.success(atr.toString())
        } catch (e: Exception) {
          result.success("ERR powerOn ${e.toString()}")
        }
      }
      "powerOff" -> {
        val reader = mReader ?: return result.error("IllegalState", "mReader null", null)
        try {
          // power on
          var atr = reader.close()
          Log.d("TAG", atr.toString() ?: "")
          result.success(atr.toString())
        } catch (e: Exception) {
          result.success("ERR powerOff ${e.toString()}")
        }
      }
      "openDevice" -> {
        val context =
          applicationContext
            ?: return result.error("IllegalState", "applicationContext null", null)
        val manager = usbManager ?: return result.error("IllegalState", "usbManager null", null)
        val reader = mReader ?: return result.error("IllegalState", "mReader null", null)
        try {
          // openDevice
          var list = manager.deviceList
          val identifier = call.argument<String>("identifier")
          val device = list[identifier]
          reader.open(device)
          var dev = serializeDevice(device)
          dev["isAttached"] = true
          dev["hasPermission"] = call.argument<Boolean>("hasPermission")
          Log.d("TAG", "open")
          result.success(dev)
        } catch (e: Exception) {
          result.success("ERR openDevice ${e.toString()}")
        }


      }
      "requestPermission" -> {
        val context =
            applicationContext
                ?: return result.error("IllegalState", "applicationContext null", null)
        val manager = usbManager ?: return result.error("IllegalState", "usbManager null", null)
        var list = manager.deviceList
        val identifier = call.argument<String>("identifier")

        val device = list[identifier]
        var dev = serializeDevice(device)
        Log.d("TAG", manager.hasPermission(device).toString() ?: "")
        if (!manager.hasPermission(device)) {
          context.registerReceiver(receiver, IntentFilter(ACTION_USB_PERMISSION))
          manager.requestPermission(device, pendingPermissionIntent(context))
        }else{
          dev["isAttached"] = false
          dev["hasPermission"] = true
        }
        result.success(dev)

      }
      "getDeviceList" -> {
        val context =
          applicationContext
            ?: return result.error("IllegalState", "applicationContext null", null)
        val manager = usbManager ?: return result.error("IllegalState", "usbManager null", null)
        var deviceList: ArrayList<HashMap<String, Any?>> = ArrayList()
        for (device in manager.deviceList.values) {
          var dev = serializeDevice(device)
          dev["isAttached"] = false
          dev["hasPermission"] = false
          deviceList.add(
            dev
          )
        }
        result.success(deviceList)
      }
      else -> result.notImplemented()
    }
  }
}
