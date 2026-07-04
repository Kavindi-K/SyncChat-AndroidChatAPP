using System;
using System.Collections.Generic;
using System.Linq;
using System.Security.Claims;
using System.Threading.Tasks;
using Microsoft.AspNetCore.SignalR;
using Moq;
using SyncChat.API.Hubs;
using SyncChat.Application.Interfaces;
using SyncChat.Application.Models;
using SyncChat.Application.UseCases.Messages;
using Xunit;

namespace SyncChat.API.Tests;

public class ChatHubTests
{
    private readonly Mock<IConversationRepository> _mockConversationRepository;
    private readonly Mock<IMessageRepository> _mockMessageRepository;
    private readonly Mock<IUserRepository> _mockUserRepository;
    private readonly Mock<INotificationService> _mockNotificationService;
    private readonly SendMessageUseCase _sendMessageUseCase;
    private readonly ChatHub _hub;

    private readonly Mock<IHubCallerClients> _mockClients;
    private readonly Mock<IClientProxy> _mockClientProxy;
    private readonly Mock<HubCallerContext> _mockContext;
    private readonly Mock<IGroupManager> _mockGroups;

    public ChatHubTests()
    {
        _mockConversationRepository = new Mock<IConversationRepository>();
        _mockMessageRepository = new Mock<IMessageRepository>();
        _mockUserRepository = new Mock<IUserRepository>();
        _mockNotificationService = new Mock<INotificationService>();

        // Instantiate actual SendMessageUseCase using mocks
        _sendMessageUseCase = new SendMessageUseCase(
            _mockMessageRepository.Object,
            _mockConversationRepository.Object
        );

        _hub = new ChatHub(
            _sendMessageUseCase,
            _mockConversationRepository.Object,
            _mockMessageRepository.Object,
            _mockUserRepository.Object,
            _mockNotificationService.Object
        );

        _mockClients = new Mock<IHubCallerClients>();
        _mockClientProxy = new Mock<IClientProxy>();
        _mockContext = new Mock<HubCallerContext>();
        _mockGroups = new Mock<IGroupManager>();

        _hub.Clients = _mockClients.Object;
        _hub.Context = _mockContext.Object;
        _hub.Groups = _mockGroups.Object;
    }

    private void SetupUser(string userId)
    {
        var claims = new List<Claim> { new(ClaimTypes.NameIdentifier, userId) };
        var identity = new ClaimsIdentity(claims, "TestAuth");
        var principal = new ClaimsPrincipal(identity);
        _mockContext.Setup(c => c.User).Returns(principal);
        _mockContext.Setup(c => c.UserIdentifier).Returns(userId);
    }

    [Fact]
    public async Task SendMessage_ValidInput_SavesAndBroadcastsToRecipientGroup()
    {
        // Arrange
        var senderId = "sender-123";
        var recipientId = "recipient-456";
        var conversationId = "conv-abc";
        var text = "Hello SignalR";

        SetupUser(senderId);

        var conversation = new Conversation
        {
            Id = conversationId,
            ParticipantUids = new[] { senderId, recipientId },
            UpdatedAt = DateTime.UtcNow
        };

        _mockConversationRepository
            .Setup(r => r.GetConversationByIdAsync(conversationId))
            .ReturnsAsync(conversation);

        _mockMessageRepository
            .Setup(r => r.SaveMessageAsync(It.IsAny<Message>()))
            .ReturnsAsync((Message msg) => msg);

        _mockClients
            .Setup(c => c.Group(recipientId))
            .Returns(_mockClientProxy.Object);

        // Setup mock user profiles
        var recipientUser = new UserProfile
        {
            Uid = recipientId,
            DisplayName = "Recipient User",
            FcmTokens = new[] { "token-xyz-123" }
        };
        var senderUser = new UserProfile
        {
            Uid = senderId,
            DisplayName = "Sender User"
        };
        _mockUserRepository.Setup(r => r.GetUserByIdAsync(recipientId)).ReturnsAsync(recipientUser);
        _mockUserRepository.Setup(r => r.GetUserByIdAsync(senderId)).ReturnsAsync(senderUser);

        // Act
        await _hub.SendMessage(conversationId, text);

        // Assert
        _mockMessageRepository.Verify(
            r => r.SaveMessageAsync(It.Is<Message>(m =>
                m.SenderId == senderId &&
                m.ConversationId == conversationId &&
                m.Text == text)),
            Times.Once
        );

        _mockClientProxy.Verify(
            p => p.SendCoreAsync(
                "NewMessage",
                It.Is<object[]>(args => args.Length == 1),
                default
            ),
            Times.Once
        );

        _mockNotificationService.Verify(
            n => n.SendMessageNotificationAsync(
                It.Is<string[]>(tokens => tokens.Contains("token-xyz-123")),
                "Sender User",
                text,
                conversationId,
                senderId,
                recipientId
            ),
            Times.Once
        );
    }

    [Fact]
    public async Task SendMessage_Unauthenticated_ThrowsHubException()
    {
        // Arrange
        _mockContext.Setup(c => c.User).Returns((ClaimsPrincipal)null);
        _mockContext.Setup(c => c.UserIdentifier).Returns((string)null);

        // Act & Assert
        await Assert.ThrowsAsync<HubException>(() =>
            _hub.SendMessage("conv-abc", "Hello"));
    }

    [Fact]
    public async Task StartTyping_ValidUser_CallsClientsGroupWithCorrectPayload()
    {
        // Arrange
        var currentUserId = "user-123";
        var otherUserId = "user-456";
        var conversationId = "conv-abc";

        SetupUser(currentUserId);

        var conversation = new Conversation
        {
            Id = conversationId,
            ParticipantUids = new[] { currentUserId, otherUserId },
            UpdatedAt = DateTime.UtcNow
        };

        _mockConversationRepository
            .Setup(r => r.GetConversationByIdAsync(conversationId))
            .ReturnsAsync(conversation);

        _mockClients
            .Setup(c => c.Group(otherUserId))
            .Returns(_mockClientProxy.Object);

        // Act
        await _hub.StartTyping(conversationId);

        // Assert
        _mockClientProxy.Verify(
            p => p.SendCoreAsync(
                "UserTyping",
                It.Is<object[]>(args => args.Length == 2),
                default
            ),
            Times.Once
        );
    }

    [Fact]
    public async Task MarkRead_ValidUser_UpdatesReadByAndNotifiesSender()
    {
        // Arrange
        var currentUserId = "user-123";
        var otherUserId = "user-456";
        var conversationId = "conv-abc";
        var messageId = "msg-xyz";

        SetupUser(currentUserId);

        var conversation = new Conversation
        {
            Id = conversationId,
            ParticipantUids = new[] { currentUserId, otherUserId },
            UpdatedAt = DateTime.UtcNow
        };

        _mockConversationRepository
            .Setup(r => r.GetConversationByIdAsync(conversationId))
            .ReturnsAsync(conversation);

        _mockClients
            .Setup(c => c.Group(otherUserId))
            .Returns(_mockClientProxy.Object);

        // Act
        await _hub.MarkRead(conversationId, messageId);

        // Assert
        _mockMessageRepository.Verify(
            r => r.MarkMessageAsReadAsync(conversationId, messageId, currentUserId),
            Times.Once
        );

        _mockClientProxy.Verify(
            p => p.SendCoreAsync(
                "MessageRead",
                It.Is<object[]>(args => args.Length == 3),
                default
            ),
            Times.Once
        );
    }
}
