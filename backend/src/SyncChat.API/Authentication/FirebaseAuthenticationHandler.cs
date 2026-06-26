using System.Security.Claims;
using System.Text.Encodings.Web;
using Microsoft.AspNetCore.Authentication;
using Microsoft.Extensions.Options;
using SyncChat.Application.Interfaces;

namespace SyncChat.API.Authentication;

public class FirebaseAuthenticationOptions : AuthenticationSchemeOptions { }

public class FirebaseAuthenticationHandler : AuthenticationHandler<FirebaseAuthenticationOptions>
{
    private readonly IFirebaseAuthService _firebaseAuthService;

    public FirebaseAuthenticationHandler(
        IOptionsMonitor<FirebaseAuthenticationOptions> options,
        ILoggerFactory logger,
        UrlEncoder encoder,
        IFirebaseAuthService firebaseAuthService)
        : base(options, logger, encoder)
    {
        _firebaseAuthService = firebaseAuthService;
    }

    protected override async Task<AuthenticateResult> HandleAuthenticateAsync()
    {
        string? token = null;

        if (Request.Headers.TryGetValue("Authorization", out var headerValues))
        {
            var header = headerValues.ToString();
            if (header.StartsWith("Bearer ", StringComparison.OrdinalIgnoreCase))
            {
                token = header["Bearer ".Length..].Trim();
            }
        }

        if (string.IsNullOrEmpty(token) && Request.Query.TryGetValue("access_token", out var queryValues))
        {
            token = queryValues.ToString();
        }

        if (string.IsNullOrEmpty(token))
            return AuthenticateResult.Fail("Missing or Empty Authentication Token");

        try
        {
            var result = await _firebaseAuthService.VerifyIdTokenAsync(token);

            var claims = new List<Claim> { new(ClaimTypes.NameIdentifier, result.Uid) };
            if (result.Email is not null) claims.Add(new(ClaimTypes.Email, result.Email));
            if (result.DisplayName is not null) claims.Add(new(ClaimTypes.Name, result.DisplayName));

            var ticket = new AuthenticationTicket(
                new System.Security.Claims.ClaimsPrincipal(new ClaimsIdentity(claims, Scheme.Name)),
                Scheme.Name);

            return AuthenticateResult.Success(ticket);
        }
        catch (Exception ex)
        {
            return AuthenticateResult.Fail("Token verification failed: " + ex.Message);
        }
    }
}
