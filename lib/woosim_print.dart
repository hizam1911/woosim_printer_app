import 'dart:io';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_blue_plus/flutter_blue_plus.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:woosim_printer_flutter/woosim_service.dart' as bps;

class WoosimPrint extends StatefulWidget {
  const WoosimPrint({super.key,});

  @override
  _WoosimPrintState createState() => _WoosimPrintState();
}

class _WoosimPrintState extends State<WoosimPrint> {
  List<String> pairedDevices = [];
  String connectionStatus = "Not connected";
  String statusConnection = "DISCONNECTED"; // new one with handler
  String connectedDevice = "";
  String printStatus = "No print job";
  String pdfUrl = ""; // replace with a real pdf url
  static const platform = MethodChannel("bluetooth_channel");

  @override
  void initState() {
    super.initState();
    _requestBluetoothPermissions();
    _fetchPairedDevices();

    checkPrinterStatus();
    platform.setMethodCallHandler(_handleMethodCall);
  }

  Future<void> checkPrinterStatus() async {
    try {
      final String result = await platform.invokeMethod("checkPrinterStatus");
      setState(() {
        statusConnection = result;
      });
    } catch (e) {
      print("Failed to check printer status: $e");
    }
  }

  Future<dynamic> _handleMethodCall(MethodCall call) async {
    if (call.method == "updateStatusConnection") {
      final String status = call.arguments;
      List<String> statusParts = status.split("|");
      String newStatus = statusParts[0];

      setState(() {
        statusConnection = newStatus;
      });

      String message;
      switch (newStatus) {
        case "CONNECTED":
          message = "Printer connected: ${statusParts[1]}";
          break;
        case "DISCONNECTED":
          message = "Printer disconnected";
          break;
        case "ALREADY_CONNECTED":
          message = "Already connected to this printer";
          break;
        default:
          message = "Unknown printer status";
      }

      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(message)),
      );
    }
  }

  Future<void> connectToPrinterWithHandling(String macAddress) async {
    try {
      final String result = await platform.invokeMethod("connectToDeviceWithHandling", {"macAddress": macAddress});
      print(result);
    } catch (e) {
      print("Connection error: $e");
    }
  }

  Future<void> _selectAndPrintPDF() async {
    FilePickerResult? result = await FilePicker.platform.pickFiles(
      type: FileType.custom,
      allowedExtensions: ['pdf'],
    );

    if (result != null) {
      File file = File(result.files.single.path!);
      print("Selected PDF Path: ${file.path}");

      try {
        final bool success = await platform.invokeMethod("printPDF", {
          "filePath": file.path,
        });

        if (success) {
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text("PDF sent to printer successfully!")),
          );
        } else {
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text("Failed to print PDF")),
          );
        }
      } on PlatformException catch (e) {
        print("Error printing PDF: ${e.message}");
      }
    }
  }

  /// 🔹 Request Bluetooth Permissions (Android 12+)
  Future<void> _requestBluetoothPermissions() async {
    if (await Permission.bluetoothConnect.request().isGranted) {
      print("Bluetooth permission granted");
    } else {
      print("Bluetooth permission denied");
    }
  }

  /// 🔹 Fetch paired devices when screen loads
  Future<void> _fetchPairedDevices() async {
    List<String> devices = await bps.WoosimService.getPairedDevices();
    setState(() {
      pairedDevices = devices;
    });
    print("paired devices are: $pairedDevices");
  }

  /// 🔹 Scan for Bluetooth Devices
  void _scanForDevices() async {
    // FlutterBluePlus.startScan(timeout: Duration(seconds: 4));
    _requestBluetoothPermissions();
    _fetchPairedDevices();

    showDialog(
      context: context,
      builder: (BuildContext context) {
        return AlertDialog(
          title: Text("Select a Bluetooth Device"),
          content: SizedBox(
            width: double.maxFinite, // Ensures the dialog does not shrink
            child: pairedDevices.isEmpty
                ? Center(child: Text("No paired devices found."))
                : Container(
              height: 300, // 🔹 Set a fixed height to prevent infinite expansion
              child: ListView.builder(
                shrinkWrap: true, // 🔹 Ensures the list only takes up necessary space
                itemCount: pairedDevices.length,
                itemBuilder: (context, index) {
                  String name = pairedDevices[index].split(" - ")[0];
                  String mac = pairedDevices[index].split(" - ")[1];
                  return ListTile(
                    title: Text(name), // Device Name
                    subtitle: Text(mac), // MAC Address
                    onTap: () {
                      Navigator.pop(context);
                      print("Selected: ${pairedDevices[index]}");
                      // _connectToDevice(mac);
                      connectToPrinterWithHandling(mac);
                    },
                  );
                },
              ),
            ),
          ),
        );
      },
    );
  }

  /// 🔹 Connect to selected device
  void _connectToDevice(String macAddress) async {
    String result = await bps.WoosimService.connectToDevice(macAddress);
    setState(() {
      connectionStatus = result;
      connectedDevice = macAddress;
    });
    print(result);
  }

  /// 🔹 Disconnect from connected device
  void _disconnectDevice() async {
    String result = await bps.WoosimService.disconnectDevice();
    setState(() {
      connectionStatus = result;
      connectedDevice = "";
    });
    print(result);
  }

  /// 🔹 Function to Print PDF from URL
  void _printPDF() async {
    String result = await bps.WoosimService.downloadAndPrintPDF(pdfUrl);
    setState(() {
      printStatus = result;
    });
    print(result);
  }

  @override
  Widget build(BuildContext context) {
    TextTheme textTheme = Theme.of(context).textTheme;

    return Scaffold(
      appBar: AppBar(
        leading: IconButton(
            onPressed: () {
              Navigator.pop(context);
            },
            icon: Icon(Icons.arrow_back_sharp, color: Colors.black,)
        ),
        elevation: 0, // Remove shadow
        title: Text(
          "Woosim Printing Page".toUpperCase(),
          style: textTheme.titleMedium!.copyWith(color: Colors.black),
        ),
        centerTitle: true,
      ),
      body: Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.start,
          children: [
            Padding(
              padding: EdgeInsets.all(10),
              child: Divider(
                color: Colors.black,
                height: 1,
                thickness: 2,
              ),
            ),
            if (statusConnection == "DISCONNECTED" || statusConnection == "FAILED")
              ElevatedButton(
                onPressed: _scanForDevices,
                child: Text("Search for Devices"),
              ),
            SizedBox(height: 20),
            // Text("Status: $connectionStatus", style: TextStyle(fontSize: 16)),
            Text("Status: $statusConnection", style: TextStyle(fontSize: 16)),
            if (statusConnection == "CONNECTED" || statusConnection == "ALREADY_CONNECTED") // Show disconnect button only if connected
              ElevatedButton(
                onPressed: _disconnectDevice,
                child: Text("Disconnect"),
              ),
            Padding(
              padding: EdgeInsets.all(10),
              child: Divider(
                color: Colors.black,
                height: 1,
                thickness: 2,
              ),
            ),
            Center(
              child: ElevatedButton(
                onPressed: _printPDF,
                child: Text("Download & Print PDF"),
              ),
            ),
            SizedBox(height: 20),
            Padding(
              padding: EdgeInsets.all(10),
              child: Divider(
                color: Colors.black,
                height: 1,
                thickness: 2,
              ),
            ),
            Text("Status: $printStatus", style: TextStyle(fontSize: 16)),
            Center(
              child: ElevatedButton(
                onPressed: _selectAndPrintPDF,
                child: Text("Select & Print PDF"),
              ),
            ),
            Padding(
              padding: EdgeInsets.all(10),
              child: Divider(
                color: Colors.black,
                height: 1,
                thickness: 2,
              ),
            ),
            Center(
              child: ElevatedButton(
                onPressed: () async {
                  String result = await bps.WoosimService.sendTestPrint();
                  ScaffoldMessenger.of(context).showSnackBar(
                    SnackBar(content: Text(result)),
                  );
                },
                child: Text("Print Test Page"),
              ),
            ),
          ],
        ),
      ),
    );
  }
}