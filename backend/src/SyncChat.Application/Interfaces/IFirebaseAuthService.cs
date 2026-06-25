using System.Threading.Tasks;

namespace SyncChat.Application.Interfaces;

public record FirebaseTokenResult(string Uid, string? Email, string? DisplayName);

public interface IFirebaseAuthService
{
    Task<FirebaseTokenResult> VerifyIdTokenAsync(string token);
}
