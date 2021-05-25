import 'dart:typed_data';
import 'package:fluttertoast/fluttertoast.dart';
import 'package:bluetooth_printer/bluetooth_printer.dart';


class TestPrint {
  BluetoothPrinter bluetooth = BluetoothPrinter.instance;

  sample() async {
    //SIZE
    // 0- normal size text
    // 1- only bold text
    // 2- bold with medium text
    // 3- bold with large text
    //ALIGN
    // 0- ESC_ALIGN_LEFT
    // 1- ESC_ALIGN_CENTER
    // 2- ESC_ALIGN_RIGHT

//     var response = await http.get("IMAGE_URL");
//     Uint8List bytes = response.bodyBytes;
    bluetooth.isConnected.then((isConnected) {
      if (isConnected) {
        bluetooth.start();
        bluetooth.printNewLine();
        bluetooth.printNewLine();
        bluetooth.writeTaggedText("{reset}{center}{h}{b}TITULO{br}");
        bluetooth.writeTaggedText("{reset}{left}{s}{u}OLA{br}");
        bluetooth.writeTaggedText("{reset}{center}{w}{i}MEU{br}");
        bluetooth.writeTaggedText("{reset}{right}{h}{b}MUNDO{br}");
        bluetooth.printNewLine();
        bluetooth.printNewLine();
        bluetooth.print();
      }
    });
  }
}