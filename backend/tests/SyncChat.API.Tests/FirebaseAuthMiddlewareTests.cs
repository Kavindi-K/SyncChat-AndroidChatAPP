using System.Net;
using System.Net.Http.Headers;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.Extensions.DependencyInjection;
using Moq;
using SyncChat.Application.Interfaces;

namespace SyncChat.API.Tests;

public class FirebaseAuthMiddlewareTests
{
    private HttpClient CreateClient(Mock<IFirebaseAuthService> mockAuth)
    {
        var factory = new WebApplicationFactory<Program>()
            .WithWebHostBuilder(b =>
            {
                b.UseEnvironment("Testing");
                b.ConfigureServices(services =>
                {
                    services.AddSingleton(mockAuth.Object);
                });
            });
        return factory.CreateClient();
    }

    [Fact]
    public async Task ValidToken_Returns200()
    {
        var mock = new Mock<IFirebaseAuthService>();
        mock.Setup(x => x.VerifyIdTokenAsync("valid-token"))
            .ReturnsAsync(new FirebaseTokenResult("uid-123", "user@test.com", "Test User"));

        var client = CreateClient(mock);
        client.DefaultRequestHeaders.Authorization =
            new AuthenticationHeaderValue("Bearer", "valid-token");

        // GET /api/test/ping is a protected endpoint
        var response = await client.GetAsync("/api/test/ping");

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
    }

    [Fact]
    public async Task ExpiredToken_Returns401()
    {
        var mock = new Mock<IFirebaseAuthService>();
        mock.Setup(x => x.VerifyIdTokenAsync(It.IsAny<string>()))
            .ThrowsAsync(new Exception("Firebase ID token has expired."));

        var client = CreateClient(mock);
        client.DefaultRequestHeaders.Authorization =
            new AuthenticationHeaderValue("Bearer", "expired-token");

        var response = await client.GetAsync("/api/test/ping");

        Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
    }

    [Fact]
    public async Task TamperedToken_Returns401()
    {
        var mock = new Mock<IFirebaseAuthService>();
        mock.Setup(x => x.VerifyIdTokenAsync(It.IsAny<string>()))
            .ThrowsAsync(new Exception("Firebase ID token has invalid signature."));

        var client = CreateClient(mock);
        client.DefaultRequestHeaders.Authorization =
            new AuthenticationHeaderValue("Bearer", "tampered-token");

        var response = await client.GetAsync("/api/test/ping");

        Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
    }

    [Fact]
    public async Task MissingAuthorizationHeader_Returns401()
    {
        var mock = new Mock<IFirebaseAuthService>();
        var client = CreateClient(mock);
        // No Authorization header

        var response = await client.GetAsync("/api/test/ping");

        Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
    }
}
