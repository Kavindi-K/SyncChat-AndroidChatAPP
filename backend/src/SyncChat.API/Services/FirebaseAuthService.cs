using FirebaseAdmin.Auth;

namespace SyncChat.API.Services;

public class FirebaseAuthService : IFirebaseAuthService
{
    public async Task<FirebaseTokenResult> VerifyIdTokenAsync(string token)
    {
        var decoded = await FirebaseAuth.DefaultInstance.VerifyIdTokenAsync(token);
        string? email = decoded.Claims.TryGetValue("email", out var e) ? e as string : null;
        string? name = decoded.Claims.TryGetValue("name", out var n) ? n as string : null;
        return new FirebaseTokenResult(decoded.Uid, email, name);
    }
}
