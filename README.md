# SyncChat — Real-Time Android Chat Application

SyncChat is a full-stack, real-time messaging application consisting of a **Kotlin/Jetpack Compose Android app** and an **ASP.NET Core / .NET 9 Web API** backend. 

Featuring an offline-first architecture with local SQLite/Room caching, real-time messaging powered by SignalR, and cloud-hosted data using Google Firebase (Firestore and Auth) and Cloudinary, SyncChat provides a modern, fast, and feature-rich chat experience.

---

## 🚀 Key Features

### 💬 Messaging & Real-time Delivery
* **Real-time Chat**: Powered by ASP.NET Core SignalR, allowing instant delivery of messages and typing indicators ("User is typing...").
* **Rich Media Sharing**: Support for uploading and viewing images, videos, voice messages (with an inline player), and generic files.
* **PDF Attachment Support**: Opens PDF attachments natively in the app or handles safe fallback viewing via system browser utilities.
* **Offline-First Storage**: Messages and chat metadata are cached locally using Room DB. Messages are synchronized to the server in the background, allowing seamless offline reading.

### 👥 Presence & Contacts
* **Active Status Presence**: Signals real-time online/offline presence using SignalR hub tracking. Other users immediately see an "Active now" status in the chat header.
* **Real-time Profile Synchronization**: Firestore snapshot listeners ensure changes to a user's display name, profile photo, or bio are immediately synchronized and visible to all contacts across chat lists and dialogs without reloading.

### ⚙️ Chat & User Management
* **3-Dot Action Menu**:
  * **Profile Info**: Instantly displays the contact's name, email, avatar, and bio in a sleek, customizable dialog.
  * **Pin Chat**: Toggles pinning the conversation to the top of the chat list with double-layer (Firestore + Room) synchronization.
  * **Clear Chat**: Deletes all messages in a conversation from both local cache and remote Firestore database.
  * **Delete Chat**: Safely removes the conversation entirely from the user's active list.
  * **Block/Unblock**: Toggles user blocking. Blocking replaces the messaging interface with a warning banner and stops incoming messages.
* **Home Screen Selection Mode**: Long-press conversation items to toggle multi-selection mode, allowing you to bulk-pin, bulk-unpin, or bulk-delete chats with clean UI indicators.
* **Profile Management**: Manage display name, bio, and password. Upload profile pictures directly to Cloudinary with safety checks that block saves during active uploads.

---

## 🏛️ Project Architecture

The codebase is split into two primary components:

### 1. Backend (`/backend`)
Built following **Clean Architecture** principles to separate business logic from database and presentation layers:
* **SyncChat.API**: ASP.NET Core controllers, SignalR Hubs (`ChatHub`), Firebase authentication middleware, and error handling.
* **SyncChat.Application**: Use cases, domain models, DTOs, and interface definitions.
* **SyncChat.Infrastructure**: Concrete repository implementations (Google Cloud Firestore SDK Integration), Firebase Admin SDK helper classes, and Cloudinary media upload service wrappers.
* **SyncChat.API.Tests**: Unit and integration test suite using xUnit, Moq, and WebApplicationFactory.

### 2. Frontend (`/frontend`)
Built using the modern Android development stack:
* **MVVM Architecture**: Clear decoupling of UI state (Jetpack Compose Screens) from database fetching (ViewModels & Repositories).
* **Data Sources**:
  * **Room Database**: Caches conversations and messages locally for instant load times and offline support.
  * **Retrofit API Repository**: Integrates with the backend REST endpoints for uploads, tokens, and registration.
  * **Firebase SDK (Auth/Firestore)**: Directly fetches and updates user documents, authentication states, and real-time snapshot listens.

---

## 🛠️ Tech Stack

| Component | Technology | Description |
| :--- | :--- | :--- |
| **Backend** | .NET 9 / C# | Core API Framework |
| | SignalR | Real-time WebSocket connection |
| | Firebase Admin SDK | Authentication verification & FCM Notifications |
| | Google.Cloud.Firestore | Firestore database management |
| **Frontend** | Kotlin | Primary development language |
| | Jetpack Compose | Modern declarative UI layout |
| | Room (SQLite) | Offline local database caching |
| | Retrofit2 / OkHttp3 | REST API client |
| | SignalR Client Java | WebSocket connection to Kestrel |
| | Coil | Asynchronous image loading |
| | Cloudinary | Profile picture & attachment uploads |

---

## ⚙️ Development Setup & Installation

### Backend Setup
1. Ensure you have the **.NET 9 SDK** installed.
2. Go to the Firebase Console, generate a new Private Key (`service-account-file.json`), and set up the Application Default Credentials (ADC) environment variable:
   ```powershell
   $env:GOOGLE_APPLICATION_CREDENTIALS="path/to/service-account-file.json"
   ```
3. Navigate to the backend directory and restore packages:
   ```bash
   cd backend
   dotnet restore
   ```
4. Start the server using the Development/HTTP profile:
   ```bash
   dotnet run --project src/SyncChat.API --launch-profile http
   ```
   The backend will start listening on `http://localhost:5228`.

### Frontend Setup
1. Open the `/frontend` directory in **Android Studio**.
2. Add your `google-services.json` file inside the `app/` folder.
3. Configure the Cloudinary credentials in your environment or upload helper if modifying the presets.
4. **Connect Device via USB Debugging**:
   To route network requests from your physical device or emulator to the local backend, configure ADB port reversing:
   ```bash
   adb reverse tcp:5228 tcp:5228
   ```
5. Compile and run the app from Android Studio or build the debug APK directly:
   ```bash
   ./gradlew assembleDebug
   ```

---

## 🧪 Running Tests

* **Backend Tests**: Run the unit and integration tests under the backend test project:
  ```bash
  cd backend
  dotnet test
  ```
