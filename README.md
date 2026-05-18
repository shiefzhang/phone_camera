# Phone Camera

Turn an idle Android phone into a LAN webcam.

## Compatibility

- Minimum Android version: Android 4.1, API 16.
- Camera implementation: legacy `android.hardware.Camera`, chosen for older phones.
- Stream format: MJPEG over HTTP, viewable in most desktop browsers and VLC.
- RTSP format: H.264 video and AAC audio over RTP, useful for clients such as VLC and ffmpeg.
- Authentication: HTTP Basic Auth.

## Features

- Uses the phone camera to capture preview frames.
- Switches between front and rear cameras when the device has more than one camera.
- Provides a zoom slider when the selected camera reports zoom support.
- Shows a LAN control page URL on the phone, for example `http://192.168.1.23:8080/`.
- Shows a raw MJPEG URL on the phone, for example `http://192.168.1.23:8080/stream.mjpg`.
- Shows an RTSP audio/video URL on the phone, for example `rtsp://admin:123456@192.168.1.23:8554/camera`.
- The browser page includes camera switch, output orientation, and zoom controls after login.
- Supports portrait/landscape output switching for both MJPEG and RTSP video.
- Shows active connections and recent connection history on the phone.
- Lets you set the username and password on the phone.
- Includes an About dialog with version, author contact, donation text, and QR code save support.
- Keeps the screen awake while the app is running.

Default login:

- Username: `admin`
- Password: `123456`

## Usage

1. Open this folder in Android Studio.
2. Build and install the app on the phone.
3. Grant camera and microphone permissions if Android asks for them.
4. Put the phone and viewer device on the same Wi-Fi or hotspot.
5. Open the URL shown on the phone screen and sign in.

## Build

If Gradle is available:

```powershell
.\gradlew.bat assembleDebug
```

You can also use Android Studio's Build APK action.
