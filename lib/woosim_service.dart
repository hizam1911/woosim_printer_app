import 'package:flutter/services.dart';

class WoosimService {
  static const MethodChannel _channel = MethodChannel('bluetooth_channel');

  static Future<bool> isBluetoothEnabled() async {
    try {
      final bool result = await _channel.invokeMethod('isBluetoothEnabled');
      return result;
    } catch (e) {
      print("Error checking Bluetooth status: $e");
      return false;
    }
  }

  static Future<void> enableBluetooth() async {
    try {
      await _channel.invokeMethod('enableBluetooth');
    } catch (e) {
      print("Error enabling Bluetooth: $e");
    }
  }

  static Future<void> disableBluetooth() async {
    try {
      await _channel.invokeMethod('disableBluetooth');
    } catch (e) {
      print("Error disabling Bluetooth: $e");
    }
  }

  static Future<void> initPrinter() async {
    try {
      await _channel.invokeMethod('initPrinter');
    } catch (e) {
      print("Error disabling Bluetooth: $e");
    }
  }

  /// 🔹 Fetch Paired Devices from Android's BluetoothAdapter
  static Future<List<String>> getPairedDevices() async {
    try {
      final List<dynamic> devices = await _channel.invokeMethod('getPairedDevices');
      return devices.cast<String>();
    } catch (e) {
      print("Error getting paired devices: $e");
      return [];
    }
  }

  /// 🔹 Connect to Selected Bluetooth Device
  static Future<String> connectToDevice(String macAddress) async {
    try {
      final String result = await _channel.invokeMethod('connectToDevice', {'macAddress': macAddress});
      return result;
    } catch (e) {
      print("Connection error: $e");
      return "Connection failed";
    }
  }

  /// 🔹 Disconnect from Bluetooth Device
  static Future<String> disconnectDevice() async {
    try {
      final String result = await _channel.invokeMethod('disconnectDevice');
      return result;
    } catch (e) {
      print("Disconnection error: $e");
      return "Disconnection failed";
    }
  }

  /// 🔹 Download and Print a PDF
  static Future<String> downloadAndPrintPDF(String url) async {
    try {
      final String result = await _channel.invokeMethod('downloadAndPrintPDF', {'url': url});
      return result;
    } catch (e) {
      print("Error printing PDF: $e");
      return "Printing failed";
    }
  }

  static Future<String> sendTestPrint() async {
    try {
      final String result = await _channel.invokeMethod('sendTestPrint');
      return result;
    } catch (e) {
      print("Error printing test: $e");
      return "Printing failed";
    }
  }



}
