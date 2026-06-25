using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using FluentValidation;
using SyncChat.Application.Interfaces;
using SyncChat.Application.Models;

namespace SyncChat.Application.UseCases.Messages;

public class GetMessagesUseCase
{
    private readonly IMessageRepository _messageRepository;
    private readonly IConversationRepository _conversationRepository;

    public GetMessagesUseCase(
        IMessageRepository messageRepository,
        IConversationRepository conversationRepository)
    {
        _messageRepository = messageRepository;
        _conversationRepository = conversationRepository;
    }

    public async Task<List<Message>> ExecuteAsync(string conversationId, string requestingUserId, DateTime? cursor, int limit)
    {
        if (string.IsNullOrWhiteSpace(conversationId))
        {
            throw new ValidationException("Conversation ID is required.");
        }

        if (string.IsNullOrWhiteSpace(requestingUserId))
        {
            throw new ValidationException("Requesting User ID is required.");
        }

        if (limit <= 0 || limit > 100)
        {
            limit = 20; // Default limit
        }

        // Verify conversation exists and user is a participant
        var conversation = await _conversationRepository.GetConversationByIdAsync(conversationId);
        if (conversation == null)
        {
            throw new ValidationException("Conversation does not exist.");
        }

        if (!conversation.ParticipantUids.Contains(requestingUserId))
        {
            throw new ValidationException("User is not a participant in this conversation.");
        }

        return await _messageRepository.GetMessagesAsync(conversationId, cursor, limit);
    }
}
