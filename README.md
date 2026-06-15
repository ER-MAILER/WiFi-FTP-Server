# WiFi FTP Server

Transform your Android device into an offline, lightning-fast, and secure local FTP server. This application allows you to seamlessly transfer files, photos, videos, and directories between your phone and any computer, Mac, or smart device connected to the same network—without needing any USB cables, internet connections, or cloud services.

Designed with **Jetpack Compose** and **Material Design 3**, this app blends top-tier visual styling with robust, high-performance backend server capabilities.

---

## Visual Preview & Design Philosophy
This application uses a highly customized **Slate Twilight** theme, utilizing:
- **Elegant Negative Space**: Clean margins and proportional padding for reduced visual clutter and effortless readability.
- **Adaptive QR Panel**: The setup and QR scanning section remain cleanly hidden while the server is offline, then fluidly transition into view using Jetpack Compose's `AnimatedVisibility` when launched.
- **Dynamic Touch Targets**: Large component bounds (48dp+) providing responsive Material Ripple touch-feedback.

---

## Key Features

*   **Dynamic QR Code Connection**: No need to manually type long, complex IP addresses. Once the server starts, a high-resolution QR code is generated instantly showing the active FTP URL for swift access.
*   **Intelligent Network Autodetect**: Seamlessly detects and binds to your active connection interface—whether you're on standard **Wi-Fi**, local **Ethernet**, **Portable Mobile Hotspot (AP/SoftAP)**, or **USB Tethering** links.
*   **Real-Time Status Console**: Includes an integrated on-screen logs explorer displaying live client connections, active paths (`CWD`), received commands (`LIST`, `RETR`, `STOR`), and real-time status alerts.
*   **Strict Permission Protection**: Automatically request and guide users to authorize needed storage permissions (`WRITE_EXTERNAL_STORAGE` and Android 11+ `MANAGE_EXTERNAL_STORAGE`), ensuring FTP clients never face empty, read-only, or `null` directory listings.
*   **High-Performance Socket Server**: Built upon a custom-engineered Kotlin socket implementation supporting core RFC-959 FTP operations cleanly without third-party bloated libraries.

---

## 🛠️ How It Works (Client Access Guide)

1.  **Launch the App**: Ensure your phone is connected to a local Wi-Fi router, running a portable hotspot, or connected to your computer via USB tethering.
2.  **Start the Server**: Tap the prominent **Start Server** button. If required, authorize storage permissions when prompted.
3.  **Establish Connection**:
    *   **Method A**: Copy/type the live FTP address (e.g., `ftp://192.168.43.1:2121`) shown on the screen directly into your PC's File Explorer address bar, macOS Finder, or an FTP Client (like FileZilla).
    *   **Method B**: Scan the generated QR code directly from another mobile device to pair instantly.
4.  **Transfer Files**: Enjoy instantaneous, hardware-speed offline reading, writing, creating, and deleting privileges over your shared directories!

---

## 💻 Tech Stack & Architecture

-   **Base Language**: 100% Kotlin
-   **UI Toolkit**: Jetpack Compose (Material Design 3 with dynamic light/dark coloring fallback)
-   **Local Storage**: Room Database (for persisting configurations, directories, ports, and state)
-   **Background Service**: Android Foreground Service with continuous notification bounds for non-blocking background server execution
-   **Network Framework**: Custom Socket Server supporting Passive (`PASV`) connectivity, ensuring high compatibility with firewalls and desktop operating systems.
