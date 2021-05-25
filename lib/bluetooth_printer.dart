
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

  Future<dynamic> start() => _channel.invokeMethod('start');

  Future<dynamic> print() => _channel.invokeMethod('print');

  Future<dynamic> printNewLine() => _channel.invokeMethod('printNewLine');

  Future<dynamic> writeLine(String message, String algin) =>
      _channel.invokeMethod('writeLine', {'message': message, "align": algin});

  Future<dynamic> writeTaggedText(String message) =>
      _channel.invokeMethod('writeTaggedText', {'message': message});

  Future<dynamic> writeBoldLine(String message, String algin) =>
      _channel.invokeMethod('writeBoldLine', {'message': message, "align": algin});

  Future<dynamic> writeItalicLine(String message, String algin) =>
      _channel.invokeMethod('writeItalicLine', {'message': message, "align": algin});

  Future<dynamic> writeUnderlinecLine(String message, String algin) =>
      _channel.invokeMethod('writeUndelineLine', {'message': message, "align": algin});

  Future<dynamic> writeCustomLine(String message, bool bold,
      bool underline, bool italic, String fontSize, String algin) =>
      _channel.invokeMethod('writeCustomLine', {
        "message": message,
        "bold": bold,
        "underline": underline,
        "italic": italic,
        "fontSize": fontSize,
        "align": algin
      });

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
