# Nordic BLE library
-keep class no.nordicsemi.android.ble.** { *; }

# WristLink ANCS models
-keep class com.wristlink.ancs.** { *; }

# Health Services talks protobuf-lite, which finds message fields by name through reflection;
# with renamed fields the app crashes at startup ("Field name_ for ... not found")
-keep class androidx.health.services.client.** { *; }
