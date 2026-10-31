# Gmail Farmer

Native Android Compose app for reviewing Google accounts visible to the current Android profile.

## Scope

- Grey liquid-glass interface with generated monochrome launcher icon.
- Lists accounts exposed by `AccountManager` for `com.google`.
- Masks email addresses by default.
- Opens `Settings.ACTION_SYNC_SETTINGS` for user-confirmed account management. The app never calls account removal APIs.
- Stores local manual review checklist titles only. Checklists are inert and have no run or automation action.
- No mail access, password/token storage, network calls, accessibility service, root, or account-registration automation.

Android can hide accounts from apps, so an empty list does not prove that no account exists. The system Settings screen owns account removal and policy checks.

## Build with GitHub Actions

1. Upload this folder to any GitHub repository you control.
2. Open the repository's **Actions** tab.
3. Select **Build Android APK** and choose **Run workflow**.
4. Open the completed run and download the `gmail-farmer-debug` artifact.
5. Extract `app-debug.apk` and install it on a test Android device.

The workflow provisions Java 17, Android SDK 35 and Gradle 8.9 on `ubuntu-latest`.

## Local build

Requires JDK 17, Android SDK 35 and Gradle 8.9. GitHub Actions provisions these tools automatically.

```bash
gradle assembleDebug
```
