using System;
using System.Collections.Generic;
using System.Net;
using System.Net.Http;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using FirebaseAdmin;
using Google.Apis.Auth.OAuth2;
using Google.Apis.Http;
using Microsoft.Extensions.Logging;
using Moq;
using SyncChat.Application.Interfaces;
using SyncChat.Infrastructure.Services;
using Xunit;

namespace SyncChat.API.Tests;

public class NotificationServiceTests : IDisposable
{
    private readonly Mock<IUserRepository> _mockUserRepository;
    private readonly Mock<ILogger<FirebaseNotificationService>> _mockLogger;
    private readonly FakeHttpMessageHandler _fakeHandler;

    public NotificationServiceTests()
    {
        _mockUserRepository = new Mock<IUserRepository>();
        _mockLogger = new Mock<ILogger<FirebaseNotificationService>>();
        _fakeHandler = new FakeHttpMessageHandler();

        // Reset FirebaseApp for test execution isolation
        if (FirebaseApp.DefaultInstance != null)
        {
            FirebaseApp.DefaultInstance.Delete();
        }

        FirebaseApp.Create(new AppOptions
        {
            Credential = GoogleCredential.FromAccessToken("mock-access-token"),
            ProjectId = "test-project-123",
            HttpClientFactory = new MockHttpClientFactory(_fakeHandler)
        });
    }

    public void Dispose()
    {
        FirebaseApp.DefaultInstance?.Delete();
    }

    [Fact]
    public async Task SendMessageNotificationAsync_CallsFirebaseMessagingWithCorrectPayload()
    {
        // Arrange
        var service = new FirebaseNotificationService(_mockLogger.Object, _mockUserRepository.Object);
        var tokens = new[] { "token-1" };
        var senderName = "Alice";
        var preview = "Hello Bob!";
        var conversationId = "conv-123";
        var senderId = "sender-1";
        var recipientUid = "recipient-2";

        var requestsMade = new List<string>();

        _fakeHandler.SendAsyncFunc = async (req) =>
        {
            var url = req.RequestUri?.ToString() ?? "";
            if (url.Contains("messages:send"))
            {
                var body = await req.Content!.ReadAsStringAsync();
                requestsMade.Add(body);
                return new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new StringContent(@"{""name"":""projects/test-project-123/messages/12345""}", Encoding.UTF8, "application/json")
                };
            }
            return new HttpResponseMessage(HttpStatusCode.NotFound);
        };

        // Act
        await service.SendMessageNotificationAsync(tokens, senderName, preview, conversationId, senderId, recipientUid);

        // Assert
        Assert.Single(requestsMade);
        var sentJson = requestsMade[0];
        Assert.Contains("token-1", sentJson);
        Assert.Contains("Alice", sentJson);
        Assert.Contains("Hello Bob!", sentJson);
        Assert.Contains("conv-123", sentJson);

        // No tokens should be removed since it succeeded
        _mockUserRepository.Verify(r => r.RemoveFcmTokensAsync(It.IsAny<string>(), It.IsAny<string[]>()), Times.Never);
    }

    [Fact]
    public async Task SendMessageNotificationAsync_InvalidOrExpiredToken_RemovesTokenFromUserRepository()
    {
        // Arrange
        var service = new FirebaseNotificationService(_mockLogger.Object, _mockUserRepository.Object);
        var tokens = new[] { "expired-token-123" };
        var senderName = "Alice";
        var preview = "Hello Bob!";
        var conversationId = "conv-123";
        var senderId = "sender-1";
        var recipientUid = "recipient-2";

        _fakeHandler.SendAsyncFunc = (req) =>
        {
            var url = req.RequestUri?.ToString() ?? "";
            if (url.Contains("messages:send"))
            {
                var errorResponse = @"{
                    ""error"": {
                        ""code"": 404,
                        ""message"": ""Requested entity was not found."",
                        ""status"": ""NOT_FOUND"",
                        ""details"": [
                            {
                                ""@type"": ""type.googleapis.com/google.firebase.fcm.v1.FcmError"",
                                ""errorCode"": ""UNREGISTERED""
                            }
                        ]
                    }
                }";
                return Task.FromResult(new HttpResponseMessage(HttpStatusCode.NotFound)
                {
                    Content = new StringContent(errorResponse, Encoding.UTF8, "application/json")
                });
            }
            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.NotFound));
        };

        // Act
        await service.SendMessageNotificationAsync(tokens, senderName, preview, conversationId, senderId, recipientUid);

        // Assert
        _mockUserRepository.Verify(r => r.RemoveFcmTokensAsync("recipient-2", It.Is<string[]>(t => t.Length == 1 && t[0] == "expired-token-123")), Times.Once);
    }
}

public class FakeHttpMessageHandler : HttpMessageHandler
{
    public Func<HttpRequestMessage, Task<HttpResponseMessage>> SendAsyncFunc { get; set; } = null!;

    protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
    {
        return SendAsyncFunc(request);
    }
}

public class MockHttpClientFactory : Google.Apis.Http.HttpClientFactory
{
    private readonly HttpMessageHandler _handler;
    public MockHttpClientFactory(HttpMessageHandler handler)
    {
        _handler = handler;
    }
    protected override HttpMessageHandler CreateHandler(CreateHttpClientArgs args)
    {
        return _handler;
    }
}
