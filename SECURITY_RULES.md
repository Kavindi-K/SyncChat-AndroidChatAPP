# Firestore Security Rules Tracker

This file tracks rules changes per phase of the project implementation. Keep this updated to ensure production safety and audit readiness.

## Phase 1: Baseline Rules (Locked Down)
**Status:** Active in Firebase Console
**Description:** Deny all access by default; allow authenticated users to read/write their own profiles.

```javascript
rules_version = '2';

service cloud.firestore {
  match /databases/{database}/documents {

    // Deny all access by default
    match /{document=**} {
      allow read, write: if false;
    }

    // Users collection: only the owner can read/write their own document
    match /users/{userId} {
      allow read: if request.auth != null;
      allow write: if request.auth != null && request.auth.uid == userId;
    }
  }
}
```
