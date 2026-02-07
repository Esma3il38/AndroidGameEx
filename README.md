# GameEx - Android Memory Editor

GameEx is an open-source Android memory editor application designed for educational purposes. It allows users to scan and modify memory of other running processes on a rooted Android device.

## Features

*   **Process Selection**: browse and select running processes to attach to.
*   **Floating Overlay**: An interactive floating icon that expands into a dashboard, allowing you to use the tool while in-game.
*   **Memory Scanner**: High-performance native C++ memory scanner capable of searching for integer values.
*   **Memory Viewer**: Read and write memory of attached processes.
*   **Root Access**: Requires root privileges to access the memory of other applications.

## Prerequisites

*   **Rooted Android Device**: The application relies on `su` to access `/proc/[pid]/mem` and `/proc/[pid]/maps` of other processes.
*   **Android SDK**: API Level 26 (Android 8.0) or higher is recommended for full feature support (though it may run on older versions).

## Build Instructions

1.  Clone the repository.
2.  Open the project in Android Studio.
3.  Build the project using Gradle:
    ```bash
    ./gradlew assembleDebug
    ```
4.  The APK will be located in `app/build/outputs/apk/debug/`.

## Architecture

The project consists of two main components:
1.  **Android App (Kotlin)**: Handles the UI, floating overlay service, and process management.
2.  **Native Library (C++)**: `native-scanner` handles the heavy lifting of memory scanning, parsing memory maps, and reading/writing memory via `process_vm_readv`.

## Usage

1.  Launch the app and grant Root permissions when prompted.
2.  Select a target process from the list.
3.  The app will close and a floating icon will appear.
4.  Open the game or app you want to modify.
5.  Click the floating icon to open the dashboard.
6.  Use the "Scan" button to search for values (implementation pending full UI integration).

## Disclaimer

This tool is for educational purposes only. Use it responsibly. The authors are not responsible for any damage or bans resulting from the use of this tool.
# GameEx

An Android memory editor for Android 15+, serving as an alternative to Game Guardian.
