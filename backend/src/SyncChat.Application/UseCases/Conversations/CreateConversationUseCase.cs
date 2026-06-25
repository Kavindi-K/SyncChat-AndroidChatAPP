using System;
using System.Linq;
using System.Threading.Tasks;
using FluentValidation;
using SyncChat.Application.Interfaces;
using SyncChat.Application.Models;

namespace SyncChat.Application.UseCases.Conversations;

public class CreateConversationUseCase
{
    private readonly IConversationRepository _conversationRepository;
    private readonly IUserRepository _userRepository;

    public CreateConversationUseCase(
        IConversationRepository conversationRepository,
        IUserRepository userRepository)
    {
        _conversationRepository = conversationRepository;
        _userRepository = userRepository;
    }

    public async Task<Conversation> ExecuteAsync(string currentUserId, string targetUserId)
    {
        if (string.IsNullOrWhiteSpace(currentUserId) || string.IsNullOrWhiteSpace(targetUserId))
        {
            throw new ValidationException("Participant UIDs cannot be empty.");
        }

        if (currentUserId == targetUserId)
        {
            throw new ValidationException("Cannot start a conversation with yourself.");
        }

        // Verify target user exists
        var targetExists = await _userRepository.UserExistsAsync(targetUserId);
        if (!targetExists)
        {
            throw new ValidationException($"User with ID {targetUserId} does not exist.");
        }

        // Sort UIDs so they are always in consistent alphabetical order
        var participants = new[] { currentUserId, targetUserId }.OrderBy(uid => uid).ToArray();

        // Check if conversation already exists
        var existing = await _conversationRepository.GetConversationByParticipantsAsync(participants);
        if (existing != null)
        {
            return existing;
        }

        // Create new conversation
        var newConv = new Conversation
        {
            Id = Guid.NewGuid().ToString("N"), // Hex-string ID without dashes for firestore
            ParticipantUids = participants,
            UpdatedAt = DateTime.UtcNow
        };

        return await _conversationRepository.CreateConversationAsync(newConv);
    }
}
