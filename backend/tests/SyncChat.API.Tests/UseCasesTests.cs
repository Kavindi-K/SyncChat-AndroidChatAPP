using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using FluentValidation;
using Moq;
using SyncChat.Application.Interfaces;
using SyncChat.Application.Models;
using SyncChat.Application.UseCases.Conversations;
using SyncChat.Application.UseCases.Messages;
using SyncChat.Application.UseCases.Users;
using Xunit;

namespace SyncChat.API.Tests;

public class UseCasesTests
{
    // ==========================================
    // 1. UserSearchUseCase Tests
    // ==========================================

    [Fact]
    public async Task UserSearchUseCase_EmptyQuery_ThrowsValidationException()
    {
        // Arrange
        var mockUserRepo = new Mock<IUserRepository>();
        var useCase = new UserSearchUseCase(mockUserRepo.Object);

        // Act & Assert
        await Assert.ThrowsAsync<ValidationException>(() => useCase.ExecuteAsync(""));
    }

    [Fact]
    public async Task UserSearchUseCase_ShortQuery_ThrowsValidationException()
    {
        // Arrange
        var mockUserRepo = new Mock<IUserRepository>();
        var useCase = new UserSearchUseCase(mockUserRepo.Object);

        // Act & Assert
        await Assert.ThrowsAsync<ValidationException>(() => useCase.ExecuteAsync("a"));
    }

    [Fact]
    public async Task UserSearchUseCase_ValidQuery_ReturnsUsers()
    {
        // Arrange
        var mockUserRepo = new Mock<IUserRepository>();
        var expectedUsers = new List<UserProfile>
        {
            new() { Uid = "user-1", DisplayName = "Alice", Email = "alice@test.com" }
        };
        mockUserRepo.Setup(r => r.SearchUsersAsync("Alice")).ReturnsAsync(expectedUsers);

        var useCase = new UserSearchUseCase(mockUserRepo.Object);

        // Act
        var result = await useCase.ExecuteAsync("Alice");

        // Assert
        Assert.Single(result);
        Assert.Equal("Alice", result[0].DisplayName);
        mockUserRepo.Verify(r => r.SearchUsersAsync("Alice"), Times.Once);
    }

    // ==========================================
    // 2. CreateConversationUseCase Tests
    // ==========================================

    [Fact]
    public async Task CreateConversationUseCase_DuplicateParticipants_ReturnsExisting()
    {
        // Arrange
        var mockConvRepo = new Mock<IConversationRepository>();
        var mockUserRepo = new Mock<IUserRepository>();

        var currentUserId = "user-a";
        var targetUserId = "user-b";
        var participants = new[] { "user-a", "user-b" }.OrderBy(u => u).ToArray();

        var existingConv = new Conversation
        {
            Id = "existing-conv-id",
            ParticipantUids = participants,
            UpdatedAt = DateTime.UtcNow
        };

        mockUserRepo.Setup(r => r.UserExistsAsync(targetUserId)).ReturnsAsync(true);
        mockConvRepo.Setup(r => r.GetConversationByParticipantsAsync(It.Is<string[]>(p => p.SequenceEqual(participants))))
            .ReturnsAsync(existingConv);

        var useCase = new CreateConversationUseCase(mockConvRepo.Object, mockUserRepo.Object);

        // Act
        var result = await useCase.ExecuteAsync(currentUserId, targetUserId);

        // Assert
        Assert.Equal("existing-conv-id", result.Id);
        mockConvRepo.Verify(r => r.CreateConversationAsync(It.IsAny<Conversation>()), Times.Never);
    }

    [Fact]
    public async Task CreateConversationUseCase_StartWithSelf_ThrowsValidationException()
    {
        // Arrange
        var mockConvRepo = new Mock<IConversationRepository>();
        var mockUserRepo = new Mock<IUserRepository>();
        var useCase = new CreateConversationUseCase(mockConvRepo.Object, mockUserRepo.Object);

        // Act & Assert
        await Assert.ThrowsAsync<ValidationException>(() => useCase.ExecuteAsync("user-a", "user-a"));
    }

    [Fact]
    public async Task CreateConversationUseCase_TargetDoesNotExist_ThrowsValidationException()
    {
        // Arrange
        var mockConvRepo = new Mock<IConversationRepository>();
        var mockUserRepo = new Mock<IUserRepository>();
        mockUserRepo.Setup(r => r.UserExistsAsync("nonexistent-user")).ReturnsAsync(false);

        var useCase = new CreateConversationUseCase(mockConvRepo.Object, mockUserRepo.Object);

        // Act & Assert
        await Assert.ThrowsAsync<ValidationException>(() => useCase.ExecuteAsync("user-a", "nonexistent-user"));
    }

    // ==========================================
    // 3. SendMessageUseCase Tests
    // ==========================================

    [Fact]
    public async Task SendMessageUseCase_EmptyTextAndMedia_ThrowsValidationException()
    {
        // Arrange
        var mockMsgRepo = new Mock<IMessageRepository>();
        var mockConvRepo = new Mock<IConversationRepository>();
        var useCase = new SendMessageUseCase(mockMsgRepo.Object, mockConvRepo.Object);

        var input = new SendMessageInput("conv-123", "sender-abc", "", null);

        // Act & Assert
        await Assert.ThrowsAsync<ValidationException>(() => useCase.ExecuteAsync(input));
    }

    [Fact]
    public async Task SendMessageUseCase_ValidInput_WritesToRepoAndUpdatesConversation()
    {
        // Arrange
        var mockMsgRepo = new Mock<IMessageRepository>();
        var mockConvRepo = new Mock<IConversationRepository>();

        var conversationId = "conv-123";
        var senderId = "sender-abc";
        var participants = new[] { "sender-abc", "receiver-xyz" };

        var existingConv = new Conversation
        {
            Id = conversationId,
            ParticipantUids = participants,
            UpdatedAt = DateTime.UtcNow
        };

        mockConvRepo.Setup(r => r.GetConversationByIdAsync(conversationId)).ReturnsAsync(existingConv);
        mockMsgRepo.Setup(r => r.SaveMessageAsync(It.IsAny<Message>()))
            .ReturnsAsync((Message m) => m); // return the saved message back

        var useCase = new SendMessageUseCase(mockMsgRepo.Object, mockConvRepo.Object);
        var input = new SendMessageInput(conversationId, senderId, "Hello Alice!", null);

        // Act
        var result = await useCase.ExecuteAsync(input);

        // Assert
        Assert.NotNull(result);
        Assert.Equal("Hello Alice!", result.Text);
        Assert.Equal(senderId, result.SenderId);
        Assert.Equal(conversationId, result.ConversationId);

        // Verify message was saved to repository
        mockMsgRepo.Verify(r => r.SaveMessageAsync(It.Is<Message>(m => m.Text == "Hello Alice!")), Times.Once);

        // Verify last message on the conversation was updated
        mockConvRepo.Verify(r => r.UpdateLastMessageAsync(conversationId, It.Is<LastMessageInfo>(lm => lm.Text == "Hello Alice!" && lm.SenderId == senderId)), Times.Once);
    }

    // ==========================================
    // 4. GetMessagesUseCase Tests
    // ==========================================

    [Fact]
    public async Task GetMessagesUseCase_ReturnsPaginatedResults()
    {
        // Arrange
        var mockMsgRepo = new Mock<IMessageRepository>();
        var mockConvRepo = new Mock<IConversationRepository>();

        var conversationId = "conv-123";
        var requestingUserId = "user-a";
        var participants = new[] { "user-a", "user-b" };

        var existingConv = new Conversation
        {
            Id = conversationId,
            ParticipantUids = participants,
            UpdatedAt = DateTime.UtcNow
        };

        var mockMessages = new List<Message>
        {
            new() { Id = "msg-1", ConversationId = conversationId, SenderId = "user-a", Text = "Hi", Timestamp = DateTime.UtcNow }
        };

        mockConvRepo.Setup(r => r.GetConversationByIdAsync(conversationId)).ReturnsAsync(existingConv);
        mockMsgRepo.Setup(r => r.GetMessagesAsync(conversationId, null, 20)).ReturnsAsync(mockMessages);

        var useCase = new GetMessagesUseCase(mockMsgRepo.Object, mockConvRepo.Object);

        // Act
        var result = await useCase.ExecuteAsync(conversationId, requestingUserId, null, 20);

        // Assert
        Assert.Single(result);
        Assert.Equal("Hi", result[0].Text);
        mockMsgRepo.Verify(r => r.GetMessagesAsync(conversationId, null, 20), Times.Once);
    }

    [Fact]
    public async Task GetMessagesUseCase_NonParticipant_ThrowsValidationException()
    {
        // Arrange
        var mockMsgRepo = new Mock<IMessageRepository>();
        var mockConvRepo = new Mock<IConversationRepository>();

        var conversationId = "conv-123";
        var requestingUserId = "intruder";
        var participants = new[] { "user-a", "user-b" };

        var existingConv = new Conversation
        {
            Id = conversationId,
            ParticipantUids = participants,
            UpdatedAt = DateTime.UtcNow
        };

        mockConvRepo.Setup(r => r.GetConversationByIdAsync(conversationId)).ReturnsAsync(existingConv);

        var useCase = new GetMessagesUseCase(mockMsgRepo.Object, mockConvRepo.Object);

        // Act & Assert
        await Assert.ThrowsAsync<ValidationException>(() => useCase.ExecuteAsync(conversationId, requestingUserId, null, 20));
    }
}
