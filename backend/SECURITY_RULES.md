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

    // Users collection: any logged-in user can read profiles; owners can write their own profile
    match /users/{userId} {
      allow read: if request.auth != null;
      allow write: if request.auth != null && request.auth.uid == userId;
    }

    // Conversations collection: users can read/write if they are a participant
    match /conversations/{conversationId} {
      allow read: if request.auth != null && request.auth.uid in resource.data.participantUids;
      allow create: if request.auth != null && request.auth.uid in request.resource.data.participantUids;
      allow update: if request.auth != null && request.auth.uid in resource.data.participantUids;

      // Messages subcollection: users can read/write if they are a participant in the parent conversation
      match /messages/{messageId} {
        allow read, write: if request.auth != null && 
          request.auth.uid in get(/databases/$(database)/documents/conversations/$(conversationId)).data.participantUids;
      }
    }
  }
}
```
