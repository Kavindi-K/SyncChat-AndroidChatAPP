namespace SyncChat.API.Repositories;

public interface IUserRepository
{
    Task<bool> UserExistsAsync(string uid);
    Task UpsertUserAsync(string uid, string displayName, string email, string photoUrl, string[] fcmTokens);
}
