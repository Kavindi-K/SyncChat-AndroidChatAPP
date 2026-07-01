using System;
using System.Linq;
using System.Security.Claims;
using System.Threading.Tasks;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.SignalR;
using SyncChat.Application.Interfaces;
using SyncChat.Application.UseCases.Messages;

namespace SyncChat.API.Hubs;

[Authorize]
public class ChatHub : Hub
{
    private readonly SendMessageUseCase _sendMessageUseCase;
    private readonly IConversationRepository _conversationRepository;
    private readonly IMessageRepository _messageRepository;

    public ChatHub(
        SendMessageUseCase sendMessageUseCase,
        IConversationRepository conversationRepository,
        IMessageRepository messageRepository)
    {
        _sendMessageUseCase = sendMessageUseCase;
        _conversationRepository = conversationRepository;
        _messageRepository = messageRepository;
    }

    public override async Task OnConnectedAsync()
    {
        var userId = GetCurrentUserId();
        if (string.IsNullOrEmpty(userId))
        {
            Context.Abort();
            return;
        }

        await Groups.AddToGroupAsync(Context.ConnectionId, userId);
        await base.OnConnectedAsync();
    }

    public async Task SendMessage(string conversationId, string text)
    {
        var senderId = GetCurrentUserId();
        if (string.IsNullOrEmpty(senderId))
        {
            throw new HubException("Unauthorized.");
        }

        var conversation = await _conversationRepository.GetConversationByIdAsync(conversationId);
        if (conversation == null)
        {
            // Conversation may have been deleted; ignore silently (stale client cache)
            return;
        }

        if (!conversation.ParticipantUids.Contains(senderId))
        {
            throw new HubException("User is not a participant in this conversation.");
        }

        // Save message via use case
        var input = new SendMessageInput(conversationId, senderId, text, null);
        var message = await _sendMessageUseCase.ExecuteAsync(input);

        var recipientUid = conversation.ParticipantUids.FirstOrDefault(uid => uid != senderId);
        if (!string.IsNullOrEmpty(recipientUid))
        {
            // Serialize the message to a JSON string because the Android client expects a single String payload
            var payloadStr = System.Text.Json.JsonSerializer.Serialize(message);
            await Clients.Group(recipientUid).SendAsync("NewMessage", payloadStr);
        }
    }

    public async Task MarkRead(string conversationId, string messageId)
    {
        var currentUserId = GetCurrentUserId();
        if (string.IsNullOrEmpty(currentUserId))
        {
            throw new HubException("Unauthorized.");
        }

        var conversation = await _conversationRepository.GetConversationByIdAsync(conversationId);
        if (conversation == null)
        {
            // Conversation may have been deleted; ignore silently (stale client cache)
            return;
        }

        if (!conversation.ParticipantUids.Contains(currentUserId))
        {
            throw new HubException("User is not a participant in this conversation.");
        }

        await _messageRepository.MarkMessageAsReadAsync(conversationId, messageId, currentUserId);

        // Notify other participant that message has been read
        var otherUserId = conversation.ParticipantUids.FirstOrDefault(uid => uid != currentUserId);
        if (!string.IsNullOrEmpty(otherUserId))
        {
            await Clients.Group(otherUserId).SendAsync("MessageRead", conversationId, messageId, currentUserId);
        }
    }

    public async Task StartTyping(string conversationId)
    {
        var currentUserId = GetCurrentUserId();
        if (string.IsNullOrEmpty(currentUserId))
        {
            throw new HubException("Unauthorized.");
        }

        var conversation = await _conversationRepository.GetConversationByIdAsync(conversationId);
        if (conversation == null) return;

        if (!conversation.ParticipantUids.Contains(currentUserId)) return;

        var otherUserId = conversation.ParticipantUids.FirstOrDefault(uid => uid != currentUserId);
        if (!string.IsNullOrEmpty(otherUserId))
        {
            await Clients.Group(otherUserId).SendAsync("UserTyping", conversationId, currentUserId);
        }
    }

    public async Task StopTyping(string conversationId)
    {
        var currentUserId = GetCurrentUserId();
        if (string.IsNullOrEmpty(currentUserId))
        {
            throw new HubException("Unauthorized.");
        }

        var conversation = await _conversationRepository.GetConversationByIdAsync(conversationId);
        if (conversation == null) return;

        if (!conversation.ParticipantUids.Contains(currentUserId)) return;

        var otherUserId = conversation.ParticipantUids.FirstOrDefault(uid => uid != currentUserId);
        if (!string.IsNullOrEmpty(otherUserId))
        {
            await Clients.Group(otherUserId).SendAsync("UserStoppedTyping", conversationId, currentUserId);
        }
    }

    private string? GetCurrentUserId()
    {
        return Context.User?.FindFirst(ClaimTypes.NameIdentifier)?.Value ?? Context.UserIdentifier;
    }
}
