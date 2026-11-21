# ROT Vahan & CSK Admin Panel — Setup & Android Build Guide

This repository contains complete code for **both applications** connected to Firebase project **`sk-admin-panel-886ef`** (`https://sk-admin-panel-886ef-default-rtdb.firebaseio.com`):

1. **ROT Vahan User App**
   - Native Android Studio project: `/android-app/` (`com.rotvahan.app`, includes `TimerForegroundService` with `foregroundServiceType="dataSync"`, `RotVahanSyncManager`, and automatic `moveTaskToBack(true)` after pressing OK)
   - Matching Web Preview: `/` (`src/App.tsx`)
2. **CSK Admin Panel**
   - Native Android Studio project: `/csk-admin-android/` (`com.cskadmin.app`)
   - Matching Web Preview: `/csk-admin` (`src/components/CskAdminPanel.tsx`)
3. **Database Rules & Server Time**
   - `/database.rules.json`
   - `/server.ts` (`/api/server-time`, `/api/health`)

---

## Step 1: Firebase Setup (`sk-admin-panel-886ef`)

1. Open **Firebase Console** → select **`sk-admin-panel-886ef`**.
2. Go to **Authentication → Sign-in method**:
   - Enable **Anonymous** sign-in (used by ROT Vahan).
   - Enable **Email/Password** (and optionally **Google**) for CSK Admin Panel.
3. Go to **Realtime Database → Rules** and deploy `/database.rules.json`.
4. Add your admin UID under `/admins/{uid}: true` in Realtime Database so CSK Admin Panel can read `/users`.

---

## Step 2: Build Installable Android APKs Locally

> Note: No `.apk` binaries are compiled inside this web container; build the APKs locally using Android Studio or Gradle.

### Build ROT Vahan (`/android-app`)
1. Open `/android-app` in **Android Studio** (or run `./gradlew assembleDebug` inside `/android-app`).
2. Output Debug APK: `android-app/app/build/outputs/apk/debug/app-debug.apk`.

### Build CSK Admin Panel (`/csk-admin-android`)
1. Open `/csk-admin-android` in **Android Studio** (or run `./gradlew assembleDebug` inside `/csk-admin-android`).
2. Output Debug APK: `csk-admin-android/app/build/outputs/apk/debug/app-debug.apk`.
