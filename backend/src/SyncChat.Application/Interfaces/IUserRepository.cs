using System.Collections.Generic;
using System.Threading.Tasks;
using SyncChat.Application.Models;

namespace SyncChat.Application.Interfaces;

public interface IUserRepository
{
    Task<bool> UserExistsAsync(string uid);
    Task UpsertUserAsync(string uid, string displayName, string email, string? photoUrl, string[] fcmTokens);
    Task<UserProfile?> GetUserByIdAsync(string uid);
    Task<List<UserProfile>> SearchUsersAsync(string query);
}
