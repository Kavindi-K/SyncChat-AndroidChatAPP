using Google.Cloud.Firestore;

namespace SyncChat.API.Repositories;

public class UserRepository : IUserRepository
{
    private readonly FirestoreDb _db;

    public UserRepository(FirestoreDb db) => _db = db;

    public async Task<bool> UserExistsAsync(string uid)
    {
        var snap = await _db.Collection("users").Document(uid).GetSnapshotAsync();
        return snap.Exists;
    }

    public async Task UpsertUserAsync(string uid, string displayName, string email, string photoUrl, string[] fcmTokens)
    {
        var docRef = _db.Collection("users").Document(uid);
        var snap = await docRef.GetSnapshotAsync();

        var data = new Dictionary<string, object>
        {
            { "displayName", displayName },
            { "email", email },
            { "photoUrl", photoUrl },
            { "fcmTokens", (object)fcmTokens }
        };

        if (!snap.Exists)
            data.Add("createdAt", Timestamp.FromDateTime(DateTime.UtcNow));

        await docRef.SetAsync(data, SetOptions.MergeAll);
    }
}
