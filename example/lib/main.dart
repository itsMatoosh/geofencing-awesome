// Copyright 2018 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

import 'dart:async';
import 'dart:developer';
import 'dart:isolate';
import 'dart:ui';

import 'package:flutter/material.dart';
import 'package:flutter_local_notifications/flutter_local_notifications.dart';
import 'package:geofencing/geofencing.dart';
import 'package:permission_handler/permission_handler.dart';

void main() => runApp(MyApp());

class MyApp extends StatefulWidget {
  @override
  _MyAppState createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  String geofenceState = 'N/A';
  List<String> registeredGeofences = [];

  String fenceId = 'test';
  double latitude = 50.00187;
  double longitude = 36.23866;
  double radius = 200.0;

  ReceivePort port = ReceivePort();
  final List<GeofenceEvent> triggers = <GeofenceEvent>[
    GeofenceEvent.enter,
    GeofenceEvent.exit
  ];

  final AndroidGeofencingSettings androidSettings = AndroidGeofencingSettings(
    initialTrigger: <GeofenceEvent>[
      GeofenceEvent.enter,
      GeofenceEvent.exit,
    ],
    loiteringDelay: 0,
    notificationResponsiveness: 0,
  );

  @override
  void initState() {
    super.initState();
    // listen for background geofence events in the foreground
    IsolateNameServer.registerPortWithName(
      port.sendPort,
      'geofencing_send_port',
    );
    port.listen((dynamic data) {
      print('Event: $data');
      setState(() {
        geofenceState = data;
      });
    });

    // initialize the plugin
    initPlugin();
  }

  /// Registers a geofence based on the current state of the UI.
  void registerGeofence() async {
    final locInUserPermission = await Permission.locationWhenInUse.request();
    final locAlwaysPermission = await Permission.locationAlways.request();
    final notificationsPermission = await Permission.notification.request();
    if (locInUserPermission.isGranted &&
        locAlwaysPermission.isGranted &&
        notificationsPermission.isGranted) {
      await GeofencingManager.registerGeofence(
        GeofenceRegion(
          fenceId,
          latitude,
          longitude,
          radius,
          triggers,
          androidSettings,
        ),
        callback,
      );
      final registeredIds = await GeofencingManager.getRegisteredGeofenceIds();
      setState(() {
        registeredGeofences = registeredIds;
      });
    } else {
      log('Location and notification permissions are required.');
    }
  }

  /// Unregisters all geofences.
  void unregisterAllGeofences() async {
    // get registered geofences
    final registeredIds = await GeofencingManager.getRegisteredGeofenceIds();
    for (final registeredId in registeredIds) {
      await GeofencingManager.removeGeofenceById(registeredId);
    }
    final registeredIdsNew = await GeofencingManager.getRegisteredGeofenceIds();
    setState(() {
      geofenceState = "N/A";
      registeredGeofences = registeredIdsNew;
    });
  }

  /// Called in the background when a geofencing event is called.
  @pragma('vm:entry-point')
  static void callback(List<String> ids, Location l, GeofenceEvent e) async {
    String debugMessage = 'Fences: $ids Event: $e Location $l';
    print(debugMessage);
    final SendPort send =
        IsolateNameServer.lookupPortByName('geofencing_send_port');
    send?.send(e.toString());
    showGeofenceNotification(debugMessage);
  }

  static void showGeofenceNotification(String message) async {
    FlutterLocalNotificationsPlugin flutterLocalNotificationsPlugin =
        FlutterLocalNotificationsPlugin();
    // initialise the plugin. app_icon needs to be a added as a drawable resource to the Android head project
    const AndroidInitializationSettings initializationSettingsAndroid =
        AndroidInitializationSettings('default_notification_icon');
    final DarwinInitializationSettings initializationSettingsDarwin =
        DarwinInitializationSettings();
    final LinuxInitializationSettings initializationSettingsLinux =
        LinuxInitializationSettings(defaultActionName: 'Open notification');
    final InitializationSettings initializationSettings =
        InitializationSettings(
            android: initializationSettingsAndroid,
            iOS: initializationSettingsDarwin,
            macOS: initializationSettingsDarwin,
            linux: initializationSettingsLinux);
    await flutterLocalNotificationsPlugin.initialize(
      initializationSettings,
    );

    const AndroidNotificationDetails androidNotificationDetails =
        AndroidNotificationDetails(
            'geofence-event-notifs', 'Geofence Event Notifs',
            channelDescription: 'your channel description',
            importance: Importance.max,
            priority: Priority.high,
            ticker: 'ticker');
    const NotificationDetails notificationDetails =
        NotificationDetails(android: androidNotificationDetails);
    await flutterLocalNotificationsPlugin.show(
        0, 'Geofence Event', message, notificationDetails,
        payload: 'item x');
  }

  /// Initialies the geofencing plugin.
  Future<void> initPlugin() async {
    print('Initializing...');
    await GeofencingManager.initialize();
    print('Initialization done');
  }

  String numberValidator(String value) {
    if (value == null) {
      return null;
    }
    final num a = num.tryParse(value);
    if (a == null) {
      return '"$value" is not a valid number';
    }
    return null;
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      home: Scaffold(
          appBar: AppBar(
            title: const Text('Flutter Geofencing Example'),
          ),
          body: SingleChildScrollView(
            child: Container(
                padding: const EdgeInsets.all(20.0),
                child: Column(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: <Widget>[
                      Text('Current state: $geofenceState'),
                      Center(
                        child: TextButton(
                          child: const Text('Register'),
                          onPressed: registerGeofence,
                        ),
                      ),
                      Text('Registered Geofences: $registeredGeofences'),
                      Center(
                        child: TextButton(
                          child: const Text('Unregister All'),
                          onPressed: unregisterAllGeofences,
                        ),
                      ),
                      TextField(
                        decoration: const InputDecoration(
                          labelText: 'Fence ID',
                        ),
                        keyboardType: TextInputType.text,
                        controller: TextEditingController(text: fenceId),
                        onChanged: (String s) {
                          fenceId = s.toLowerCase().replaceAll(' ', '');
                        },
                      ),
                      TextField(
                        decoration: const InputDecoration(
                          labelText: 'Latitude',
                        ),
                        keyboardType: TextInputType.number,
                        controller:
                            TextEditingController(text: latitude.toString()),
                        onChanged: (String s) {
                          latitude = double.tryParse(s);
                        },
                      ),
                      TextField(
                          decoration:
                              const InputDecoration(labelText: 'Longitude'),
                          keyboardType: TextInputType.number,
                          controller:
                              TextEditingController(text: longitude.toString()),
                          onChanged: (String s) {
                            longitude = double.tryParse(s);
                          }),
                      TextField(
                          decoration:
                              const InputDecoration(labelText: 'Radius'),
                          keyboardType: TextInputType.number,
                          controller:
                              TextEditingController(text: radius.toString()),
                          onChanged: (String s) {
                            radius = double.tryParse(s);
                          }),
                    ])),
          )),
    );
  }
}
