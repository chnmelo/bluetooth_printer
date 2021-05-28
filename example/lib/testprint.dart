import 'dart:typed_data';
import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart' show NetworkAssetBundle, rootBundle;
import 'package:bluetooth_printer/bluetooth_printer.dart';


class TestPrint {
  BluetoothPrinter bluetooth = BluetoothPrinter.instance;

  int printDPP(String){

  }

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
    //
    //Uint8List bytes = (await NetworkAssetBundle(Uri.parse("https://miopet.com.br/wp-content/uploads/2021/02/slide2.png"))
    //    .load("https://miopet.com.br/wp-content/uploads/2021/02/slide2.png"))
    //    .buffer
    //    .asUint8List();
    ByteData by = await rootBundle.load("assets/images/logo.png");
    ByteBuffer buffer = by.buffer;
    Uint8List bytes = Uint8List.view(buffer);
    bluetooth.isConnected.then((isConnected) async{
      if (isConnected) {
        //bluetooth.feedPaper(110);
        bluetooth.printImage(bytes,200,200,"CENTER",true,true);
        //bluetooth.printImageBytes(bytes);
        await bluetooth.getMaxPageWidth().then((value) => bluetooth.printTaggedText("{center}${"*"*value[0]}"));
        bluetooth.printTaggedText("{reset}{center}{b}TESTE DE *{br}");
        await bluetooth.getMaxPageWidth().then((value) => bluetooth.printTaggedText("{center}${"*"*value[0]}"));
        bluetooth.feedPaper(55);
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