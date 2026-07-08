using System.Collections.Generic;
using System.Threading.Tasks;
using SyncChat.Application.Models;

namespace SyncChat.Application.Interfaces;

public interface IUserRepository
{
    Task<bool> UserExistsAsync(string uid);
    Task UpsertUserAsync(string uid, string displayName, string email, string? photoUrl, string? bio, string[]? fcmTokens);
    Task<UserProfile?> GetUserByIdAsync(string uid);
    Task<List<UserProfile>> SearchUsersAsync(string query);

    /// <summary>Atomically adds a token to the user's fcmTokens array (deduped, max 5 tokens kept).</summary>
    Task StoreFcmTokenAsync(string uid, string token);

    /// <summary>Atomically removes tokens from the user's fcmTokens array.</summary>
    Task RemoveFcmTokensAsync(string uid, string[] tokens);
}
