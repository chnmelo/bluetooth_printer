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
        bluetooth.feedPaper(110);
        bluetooth.printTaggedText("{reset}{center}{h}{b}FALA TU{br}");
        bluetooth.printTaggedText("{reset}{left}{s}{u}OLA{br}");
        bluetooth.printTaggedText("{reset}{center}{w}{i}MEU{br}");
        bluetooth.printTaggedText("{reset}{right}{h}{b}MUNDO{br}");
        bluetooth.feedPaper(110);
        bluetooth.flush();
      }
    });
  }
}