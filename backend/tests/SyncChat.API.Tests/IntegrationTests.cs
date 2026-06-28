using System;
using System.Collections.Generic;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using System.Threading.Tasks;
using Google.Cloud.Firestore;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.AspNetCore.SignalR.Client;
using Microsoft.Extensions.DependencyInjection;
using Moq;
using SyncChat.Application.Interfaces;
using SyncChat.Application.Models;
using SyncChat.Infrastructure.Repositories;
using Xunit;

namespace SyncChat.API.Tests;

public class IntegrationTests : IAsyncLifetime
{
    private readonly WebApplicationFactory<Program> _factory;
    private readonly Mock<IFirebaseAuthService> _mockAuthService;
    private FirestoreDb? _db;
    private const string ProjectId = "syncchat-b0889";

    public IntegrationTests()
    {
        // Point Firestore SDK to local emulator
        Environment.SetEnvironmentVariable("FIRESTORE_EMULATOR_HOST", "127.0.0.1:8080");

        // Set credentials path to bypass initialization checks
        var credentialPath = "firebase-service-account.json";
        if (!File.Exists(credentialPath)) credentialPath = "../firebase-service-account.json";
        if (!File.Exists(credentialPath)) credentialPath = "../../firebase-service-account.json";
        if (!File.Exists(credentialPath)) credentialPath = "../../../firebase-service-account.json";
        if (!File.Exists(credentialPath)) credentialPath = "../../../../firebase-service-account.json";
        if (!File.Exists(credentialPath)) credentialPath = "../../../../../firebase-service-account.json";
        if (File.Exists(credentialPath))
        {
            Environment.SetEnvironmentVariable("GOOGLE_APPLICATION_CREDENTIALS", Path.GetFullPath(credentialPath));
        }

        _mockAuthService = new Mock<IFirebaseAuthService>();

        _factory = new WebApplicationFactory<Program>()
            .WithWebHostBuilder(builder =>
            {
                builder.UseEnvironment("Testing");
                builder.ConfigureServices(services =>
                {
                    services.AddSingleton(_mockAuthService.Object);

                    // Seed FirestoreDb pointing to emulator
                    var db = FirestoreDb.Create(ProjectId);
                    services.AddSingleton(db);

                    services.AddScoped<IUserRepository, FirestoreUserRepository>();
                    services.AddScoped<IConversationRepository, FirestoreConversationRepository>();
                    services.AddScoped<IMessageRepository, FirestoreMessageRepository>();
                });
            });
    }

    public async Task InitializeAsync()
    {
        _db = _factory.Services.GetRequiredService<FirestoreDb>();
        await ClearFirestoreEmulatorAsync();
    }

    public Task DisposeAsync()
    {
        _factory.Dispose();
        return Task.CompletedTask;
    }

    private async Task ClearFirestoreEmulatorAsync()
    {
        using var client = new HttpClient();
        var url = $"http://127.0.0.1:8080/emulator/v1/projects/{ProjectId}/databases/(default)/documents";
        try
        {
            await client.DeleteAsync(url);
        }
        catch
        {
            // Emulator might not be running or reachable during simple dotnet build/test cycles
        }
    }

    private HttpClient CreateAuthenticatedClient(string uid, string email, string name)
    {
        _mockAuthService.Setup(x => x.VerifyIdTokenAsync("valid-token"))
            .ReturnsAsync(new FirebaseTokenResult(uid, email, name));

        var client = _factory.CreateClient();
        client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", "valid-token");
        return client;
    }

    [Fact]
    public async Task SendMessage_ValidRequest_Returns201AndSavesToFirestore()
    {
        // Arrange
        using var scope = _factory.Services.CreateScope();
        var userRepo = scope.ServiceProvider.GetRequiredService<IUserRepository>();
        var convRepo = scope.ServiceProvider.GetRequiredService<IConversationRepository>();

        // Seed users
        await userRepo.UpsertUserAsync("user-a", "Alice", "alice@test.com", null, Array.Empty<string>());
        await userRepo.UpsertUserAsync("user-b", "Bob", "bob@test.com", null, Array.Empty<string>());

        // Seed conversation
        var conversationId = $"test-conv-1-{Guid.NewGuid()}";
        var conv = new Conversation
        {
            Id = conversationId,
            ParticipantUids = new[] { "user-a", "user-b" },
            UpdatedAt = DateTime.UtcNow
        };
        await convRepo.CreateConversationAsync(conv);

        var client = CreateAuthenticatedClient("user-a", "alice@test.com", "Alice");

        var payload = new { text = "Hello Bob!", mediaUrl = (string?)null };
        var content = new StringContent(JsonSerializer.Serialize(payload), Encoding.UTF8, "application/json");

        // Act
        var response = await client.PostAsync($"/api/conversations/{conversationId}/messages", content);

        // Assert
        Assert.Equal(HttpStatusCode.Created, response.StatusCode);

        // Verify Firestore has the message
        var messagesCollection = _db!.Collection("conversations").Document(conversationId).Collection("messages");
        var snapshot = await messagesCollection.GetSnapshotAsync();
        Assert.Single(snapshot.Documents);
        Assert.Equal("Hello Bob!", snapshot.Documents[0].GetValue<string>("text"));

        // Verify lastMessage is updated on conversation doc
        var convDoc = await _db.Collection("conversations").Document(conversationId).GetSnapshotAsync();
        Assert.True(convDoc.ContainsField("lastMessage"));
        var lastMsg = convDoc.GetValue<Dictionary<string, object>>("lastMessage");
        Assert.Equal("Hello Bob!", lastMsg["text"]);
    }

    [Fact]
    public async Task GetMessages_ReturnsPaginatedList()
    {
        // Arrange
        using var scope = _factory.Services.CreateScope();
        var userRepo = scope.ServiceProvider.GetRequiredService<IUserRepository>();
        var convRepo = scope.ServiceProvider.GetRequiredService<IConversationRepository>();
        var msgRepo = scope.ServiceProvider.GetRequiredService<IMessageRepository>();

        await userRepo.UpsertUserAsync("user-a", "Alice", "alice@test.com", null, Array.Empty<string>());
        await userRepo.UpsertUserAsync("user-b", "Bob", "bob@test.com", null, Array.Empty<string>());

        var conversationId = $"test-conv-2-{Guid.NewGuid()}";
        var conv = new Conversation
        {
            Id = conversationId,
            ParticipantUids = new[] { "user-a", "user-b" },
            UpdatedAt = DateTime.UtcNow
        };
        await convRepo.CreateConversationAsync(conv);

        // Seed 3 messages with short delays to ensure unique timestamps
        for (int i = 1; i <= 3; i++)
        {
            await msgRepo.SaveMessageAsync(new Message
            {
                Id = $"msg-{i}",
                ConversationId = conversationId,
                SenderId = "user-a",
                Text = $"Message {i}",
                Timestamp = DateTime.UtcNow.AddSeconds(i)
            });
        }

        var client = CreateAuthenticatedClient("user-a", "alice@test.com", "Alice");

        // Act
        var response = await client.GetAsync($"/api/conversations/{conversationId}/messages?limit=2");

        // Assert
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        var body = await response.Content.ReadAsStringAsync();
        var messages = JsonSerializer.Deserialize<List<Message>>(body, new JsonSerializerOptions { PropertyNameCaseInsensitive = true });
        
        Assert.NotNull(messages);
        Assert.Equal(2, messages.Count);
        // Ordered descending, so msg-3 is first
        Assert.Equal("Message 3", messages[0].Text);
        Assert.Equal("Message 2", messages[1].Text);
    }

    [Fact]
    public async Task SendMessage_MissingAuthHeader_Returns401()
    {
        // Arrange
        var client = _factory.CreateClient(); // No auth header
        var payload = new { text = "Anonymous hello" };
        var content = new StringContent(JsonSerializer.Serialize(payload), Encoding.UTF8, "application/json");

        // Act
        var response = await client.PostAsync("/api/conversations/test-conv/messages", content);

        // Assert
        Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
    }

    [Fact]
    public async Task SendMessage_MalformedJson_Returns400()
    {
        // Arrange
        var client = CreateAuthenticatedClient("user-a", "alice@test.com", "Alice");
        var content = new StringContent("{ invalid-json: }", Encoding.UTF8, "application/json");

        // Act
        var response = await client.PostAsync("/api/conversations/test-conv/messages", content);

        // Assert
        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
    }

    [Fact]
    public async Task SendMessage_RateLimiting_Returns429()
    {
        // Arrange
        using var scope = _factory.Services.CreateScope();
        var userRepo = scope.ServiceProvider.GetRequiredService<IUserRepository>();
        var convRepo = scope.ServiceProvider.GetRequiredService<IConversationRepository>();

        await userRepo.UpsertUserAsync("user-a", "Alice", "alice@test.com", null, Array.Empty<string>());
        await userRepo.UpsertUserAsync("user-b", "Bob", "bob@test.com", null, Array.Empty<string>());

        var conversationId = $"test-conv-rate-{Guid.NewGuid()}";
        var conv = new Conversation
        {
            Id = conversationId,
            ParticipantUids = new[] { "user-a", "user-b" },
            UpdatedAt = DateTime.UtcNow
        };
        await convRepo.CreateConversationAsync(conv);

        var client = CreateAuthenticatedClient("user-a", "alice@test.com", "Alice");

        var payload = new { text = "Spamming!", mediaUrl = (string?)null };
        var content = new StringContent(JsonSerializer.Serialize(payload), Encoding.UTF8, "application/json");

        // Act: send 60 messages (within rate limit)
        for (int i = 0; i < 60; i++)
        {
            var res = await client.PostAsync($"/api/conversations/{conversationId}/messages", content);
            Assert.Equal(HttpStatusCode.Created, res.StatusCode);
        }

        // Send 61st message (exceeds rate limit)
        var blockedRes = await client.PostAsync($"/api/conversations/{conversationId}/messages", content);

        // Assert
        Assert.Equal(HttpStatusCode.TooManyRequests, blockedRes.StatusCode);
    }

    [Fact]
    public async Task SignalR_ConnectWithValidToken_HandshakeSuccessful()
    {
        // Arrange
        var uid = "user-sig1";
        _mockAuthService.Setup(x => x.VerifyIdTokenAsync("valid-signalr-token"))
            .ReturnsAsync(new FirebaseTokenResult(uid, "sig1@test.com", "Sig1"));

        var server = _factory.Server;
        var connection = new Microsoft.AspNetCore.SignalR.Client.HubConnectionBuilder()
            .WithUrl("http://localhost/hubs/chat", options =>
            {
                options.HttpMessageHandlerFactory = _ => server.CreateHandler();
                options.AccessTokenProvider = () => Task.FromResult((string?)"valid-signalr-token");
            })
            .Build();

        // Act & Assert
        var exception = await Record.ExceptionAsync(() => connection.StartAsync());
        Assert.Null(exception); // Handshake successful!
        Assert.Equal(Microsoft.AspNetCore.SignalR.Client.HubConnectionState.Connected, connection.State);

        await connection.StopAsync();
    }

    [Fact]
    public async Task SignalR_ConnectWithInvalidToken_HandshakeFailsWith401()
    {
        // Arrange
        _mockAuthService.Setup(x => x.VerifyIdTokenAsync("invalid-token"))
            .ThrowsAsync(new System.Exception("Invalid token"));

        var server = _factory.Server;
        var connection = new Microsoft.AspNetCore.SignalR.Client.HubConnectionBuilder()
            .WithUrl("http://localhost/hubs/chat", options =>
            {
                options.HttpMessageHandlerFactory = _ => server.CreateHandler();
                options.AccessTokenProvider = () => Task.FromResult((string?)"invalid-token");
            })
            .Build();

        // Act & Assert
        var exception = await Record.ExceptionAsync(() => connection.StartAsync());
        
        Assert.NotNull(exception);
        Assert.Contains("401", exception.Message); // Verifies 401 Unauthorized
    }
}
