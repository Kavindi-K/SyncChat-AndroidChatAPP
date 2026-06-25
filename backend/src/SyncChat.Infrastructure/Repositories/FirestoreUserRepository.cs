using System;
using System.Collections.Generic;
using System.Threading.Tasks;
using Google.Cloud.Firestore;
using SyncChat.Application.Interfaces;
using SyncChat.Application.Models;

namespace SyncChat.Infrastructure.Repositories;

public class FirestoreUserRepository : IUserRepository
{
    private readonly FirestoreDb _db;

    public FirestoreUserRepository(FirestoreDb db)
    {
        _db = db;
    }

    public async Task<bool> UserExistsAsync(string uid)
    {
        var docRef = _db.Collection("users").Document(uid);
        var snapshot = await docRef.GetSnapshotAsync();
        return snapshot.Exists;
    }

    public async Task UpsertUserAsync(string uid, string displayName, string email, string? photoUrl, string[] fcmTokens)
    {
        var docRef = _db.Collection("users").Document(uid);
        var snapshot = await docRef.GetSnapshotAsync();

        var data = new Dictionary<string, object>
        {
            { "displayName", displayName },
            { "email", email },
            { "fcmTokens", fcmTokens }
        };

        if (photoUrl != null)
        {
            data.Add("photoUrl", photoUrl);
        }

        if (!snapshot.Exists)
        {
            data.Add("createdAt", Timestamp.FromDateTime(DateTime.UtcNow));
        }

        await docRef.SetAsync(data, SetOptions.MergeAll);
    }

    public async Task<UserProfile?> GetUserByIdAsync(string uid)
    {
        var docRef = _db.Collection("users").Document(uid);
        var snapshot = await docRef.GetSnapshotAsync();
        if (!snapshot.Exists) return null;

        return MapSnapshotToUserProfile(snapshot);
    }

    public async Task<List<UserProfile>> SearchUsersAsync(string query)
    {
        var users = new List<UserProfile>();
        if (string.IsNullOrWhiteSpace(query)) return users;

        var term = query.Trim();

        // Search by displayName prefix
        var nameQuery = _db.Collection("users")
            .OrderBy("displayName")
            .StartAt(term)
            .EndAt(term + "\uf8ff")
            .Limit(20);
        var nameSnap = await nameQuery.GetSnapshotAsync();

        // Search by email prefix
        var emailQuery = _db.Collection("users")
            .OrderBy("email")
            .StartAt(term)
            .EndAt(term + "\uf8ff")
            .Limit(20);
        var emailSnap = await emailQuery.GetSnapshotAsync();

        var seenUids = new HashSet<string>();

        foreach (var doc in nameSnap.Documents)
        {
            var user = MapSnapshotToUserProfile(doc);
            if (user != null && seenUids.Add(user.Uid))
            {
                users.Add(user);
            }
        }

        foreach (var doc in emailSnap.Documents)
        {
            var user = MapSnapshotToUserProfile(doc);
            if (user != null && seenUids.Add(user.Uid))
            {
                users.Add(user);
            }
        }

        return users;
    }

    private static UserProfile MapSnapshotToUserProfile(DocumentSnapshot snapshot)
    {
        var uid = snapshot.Id;
        var displayName = snapshot.ContainsField("displayName") ? snapshot.GetValue<string>("displayName") : string.Empty;
        var email = snapshot.ContainsField("email") ? snapshot.GetValue<string>("email") : string.Empty;
        var photoUrl = snapshot.ContainsField("photoUrl") ? snapshot.GetValue<string>("photoUrl") : string.Empty;

        var fcmTokensList = new List<string>();
        if (snapshot.ContainsField("fcmTokens"))
        {
            try
            {
                var tokensObj = snapshot.GetValue<object>("fcmTokens");
                if (tokensObj is List<object> list)
                {
                    foreach (var item in list)
                    {
                        if (item is string s) fcmTokensList.Add(s);
                    }
                }
                else if (tokensObj is string[] arr)
                {
                    fcmTokensList.AddRange(arr);
                }
            }
            catch
            {
                // Ignore parsing errors for empty/malformed tokens
            }
        }

        var createdAt = snapshot.ContainsField("createdAt")
            ? snapshot.GetValue<Timestamp>("createdAt").ToDateTime()
            : DateTime.UtcNow;

        return new UserProfile
        {
            Uid = uid,
            DisplayName = displayName,
            Email = email,
            PhotoUrl = photoUrl,
            FcmTokens = fcmTokensList.ToArray(),
            CreatedAt = createdAt
        };
    }
}
