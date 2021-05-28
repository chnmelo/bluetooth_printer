import 'dart:typed_data';
import 'dart:async';

import 'package:flutter/services.dart';

class BluetoothPrinter {

  static const String namespace = 'bluetooth_printer_datecs';

  static const MethodChannel _channel =
  const MethodChannel('$namespace/methods');

  static const EventChannel _readChannel =
  const EventChannel('$namespace/read');

  static const EventChannel _stateChannel =
  const EventChannel('$namespace/state');

  final StreamController<MethodCall> _methodStreamController =
  new StreamController.broadcast();

  //Stream<MethodCall> get _methodStream => _methodStreamController.stream;

  BluetoothPrinter._() {
    _channel.setMethodCallHandler((MethodCall call) async {
      _methodStreamController.add(call);
    });
  }

  static BluetoothPrinter _instance = new BluetoothPrinter._();

  static BluetoothPrinter get instance => _instance;

  Future<bool> get isConnected async =>
      await _channel.invokeMethod('isConnected');

  Future<dynamic> connect(String addr) =>
      _channel.invokeMethod('connect', {'address': addr});

  Future<dynamic> printTaggedText(String message) =>
      _channel.invokeMethod('printTaggedText', {'message': message});

  Future<dynamic> printImage(Uint8List bytes, int width, int height, String align,
      bool dither, bool crop) =>
      _channel.invokeMethod('printImage', {
        'bytes': bytes,
        'width': width,
        'height': height,
        'align': align,
        'dither': dither,
        'crop': crop
      });

  Future<dynamic> printImageBytes(Uint8List bytes) =>
      _channel.invokeMethod('printImageBytes', {'bytes': bytes});

  Future<dynamic> printCompressedImage(Uint8List bytes, int width, int height, String align,
      bool dither, bool crop) =>
      _channel.invokeMethod('printCompressedImage', {
        'bytes': bytes,
        'width': width,
        'height': height,
        'align': align,
        'dither': dither,
        'crop': crop
      });

  Future<dynamic> printText(String message) =>
      _channel.invokeMethod('printText', {'message': message});

  Future<dynamic> setAlign(String algin) =>
      _channel.invokeMethod('setAlign', {"align": algin});

  Future<dynamic> feedPaper(int lines) =>
      _channel.invokeMethod('feedPaper', {"lines": lines});

  Future<dynamic> reset() =>
      _channel.invokeMethod('reset');

  Future<Int32List> getMaxPageWidth() =>
      _channel.invokeMethod('getMaxPageWidth');

  Future<String> getNamePrinter() =>
      _channel.invokeMethod('getName');

  Future<dynamic> flush() =>
      _channel.invokeMethod('flush');

  Future<List<BluetoothDevice>> getBondedDevices() async {
    final List list = await (_channel.invokeMethod('getBondedDevices'));
    return list.map((map) => BluetoothDevice.fromMap(map)).toList();
  }
}

class BluetoothDevice {
  final String name;
  final String address;
  final int type = 0;
  bool connected = false;

  BluetoothDevice(this.name, this.address);

  BluetoothDevice.fromMap(Map map)
      : name = map['name'],
        address = map['address'];

  Map<String, dynamic> toMap() => {
    'name': this.name,
    'address': this.address,
    'type': this.type,
    'connected': this.connected,
  };

  operator ==(Object other) {
    return other is BluetoothDevice && other.address == this.address;
  }

  @override
  int get hashCode => address.hashCode;
}
