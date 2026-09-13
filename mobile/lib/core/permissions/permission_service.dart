import 'package:permission_handler/permission_handler.dart';

class PermissionService {
  Future<bool> ensureLocation() async {
    final status = await Permission.locationWhenInUse.request();
    return status.isGranted || status.isLimited;
  }

  Future<bool> ensureMicrophone() async =>
      (await Permission.microphone.request()).isGranted;
  Future<bool> ensureCamera() async =>
      (await Permission.camera.request()).isGranted;
}
