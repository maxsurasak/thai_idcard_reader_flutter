import 'dart:ffi';

import 'package:flutter/services.dart';
import 'common.dart';
import 'dart:core';

class ThaiIdcardReaderFlutter {
  static const MethodChannel _channel =
      MethodChannel('thai_idcard_reader_flutter_channel');

  static const EventChannel _usbStreamChannel =
      EventChannel('usb_stream_channel');
  static const EventChannel _readerStreamChannel =
      EventChannel('reader_stream_channel');

  static Stream<UsbDevice> get deviceHandlerStream {
    return _usbStreamChannel
        .receiveBroadcastStream()
        .distinct()
        .map((dynamic event) => UsbDevice.fromMap(event));
  }

  static Stream<IDCardReader> get cardHandlerStream {
    return _readerStreamChannel
        .receiveBroadcastStream()
        .distinct()
        .map((dynamic event) => IDCardReader.fromMap(event));
  }

  static Future<ThaiIDCard> read({List<String> only = const []}) async {
    final String res = only.isNotEmpty
        ? await _channel.invokeMethod('read', {'selected': only})
        : await _channel.invokeMethod('readAll');
    return ThaiIDCard.fromJson(res);
  }
  static Future<String> powerOn() async {
    var res = await _channel.invokeMethod('powerOn');
    return res;
  }
  static Future<UsbDevice> openDevice(UsbDevice usbDevice) async {
    var res = await _channel.invokeMethod('openDevice',usbDevice.toMap());
    return UsbDevice.fromMap(res);
  }
  static Future<String> powerOff() async {
    var res = await _channel.invokeMethod('powerOff');
    return res;
  }

  static Future<List<UsbDevice>> getDeviceList({List<String> only = const []}) async {
    List<dynamic> res = await _channel.invokeMethod('getDeviceList');
    List<UsbDevice> data  = [];
    if (res.isNotEmpty) {
      res.asMap().forEach((key, value) {
        data.add(UsbDevice.fromMap(value));
      });
    }
    return data;
  }

  static Future<UsbDevice> requestPermission(UsbDevice usbDevice) async {
    var res = await _channel.invokeMethod('requestPermission',usbDevice.toMap());
    return UsbDevice.fromMap(res);
  }
}
