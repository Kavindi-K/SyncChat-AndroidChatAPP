namespace SyncChat.API.Services;

public record FirebaseTokenResult(string Uid, string? Email, string? DisplayName);

public interface IFirebaseAuthService
{
    Task<FirebaseTokenResult> VerifyIdTokenAsync(string token);
}
