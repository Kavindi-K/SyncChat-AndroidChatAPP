using System.Net;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.Extensions.DependencyInjection;
using Moq;
using SyncChat.Application.Interfaces;

namespace SyncChat.API.Tests;

public class UsersControllerTests
{
    /// <summary>
    /// Creates a test client that:
    /// - Runs in Testing environment (skips real Firebase init)
    /// - Mocks IFirebaseAuthService to always authenticate as uid-test-user
    /// - Mocks IUserRepository to avoid real Firestore calls
    /// </summary>
    private HttpClient CreateClient(Mock<IUserRepository> mockRepo, string fakeUid = "uid-test-user")
    {
        var mockAuth = new Mock<IFirebaseAuthService>();
        mockAuth.Setup(x => x.VerifyIdTokenAsync(It.IsAny<string>()))
            .ReturnsAsync(new FirebaseTokenResult(fakeUid, "test@test.com", "Test User"));

        var factory = new WebApplicationFactory<Program>()
            .WithWebHostBuilder(b =>
            {
                b.UseEnvironment("Testing");
                b.ConfigureServices(services =>
                {
                    // Replace real Firebase auth service — handler will call this mock
                    services.AddSingleton(mockAuth.Object);
                    // Replace real Firestore repository
                    services.AddSingleton(mockRepo.Object);
                });
            });

        return factory.CreateClient();
    }

    [Fact]
    public async Task UpsertProfile_ValidBody_Returns200AndCallsRepo()
    {
        var mockRepo = new Mock<IUserRepository>();
        mockRepo.Setup(x => x.UpsertUserAsync(
            It.IsAny<string>(), It.IsAny<string>(), It.IsAny<string>(),
            It.IsAny<string>(), It.IsAny<string[]>()))
            .Returns(Task.CompletedTask);

        var client = CreateClient(mockRepo);
        // Any bearer token works — IFirebaseAuthService mock returns success for all tokens
        client.DefaultRequestHeaders.Authorization =
            new AuthenticationHeaderValue("Bearer", "any-token");

        var body = JsonSerializer.Serialize(new
        {
            displayName = "Test User",
            email = "test@test.com",
            photoUrl = "",
            fcmTokens = Array.Empty<string>()
        });

        var response = await client.PostAsync(
            "/api/users/me",
            new StringContent(body, Encoding.UTF8, "application/json"));

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);

        // Verify repository was called with the correct uid from the token
        mockRepo.Verify(x => x.UpsertUserAsync(
            "uid-test-user", "Test User", "test@test.com",
            It.IsAny<string>(), It.IsAny<string[]>()), Times.Once);
    }

    [Fact]
    public async Task UpsertProfile_InvalidEmail_Returns400()
    {
        var mockRepo = new Mock<IUserRepository>();
        var client = CreateClient(mockRepo);
        client.DefaultRequestHeaders.Authorization =
            new AuthenticationHeaderValue("Bearer", "any-token");

        var body = JsonSerializer.Serialize(new
        {
            displayName = "Test User",
            email = "not-a-valid-email",
            photoUrl = "",
            fcmTokens = Array.Empty<string>()
        });

        var response = await client.PostAsync(
            "/api/users/me",
            new StringContent(body, Encoding.UTF8, "application/json"));

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);

        // Repository must NOT be called for invalid requests
        mockRepo.Verify(x => x.UpsertUserAsync(
            It.IsAny<string>(), It.IsAny<string>(), It.IsAny<string>(),
            It.IsAny<string>(), It.IsAny<string[]>()), Times.Never);
    }
}
