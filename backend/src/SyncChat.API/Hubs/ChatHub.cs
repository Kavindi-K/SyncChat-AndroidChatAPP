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
            throw new HubException("Conversation not found.");
        }

        if (!conversation.ParticipantUids.Contains(senderId))
        {
            throw new HubException("User is not a participant in this conversation.");
        }

        // Save message via use case
        var input = new SendMessageInput(conversationId, senderId, text, null);
        var message = await _sendMessageUseCase.ExecuteAsync(input);

        // Find recipient to notify
        var recipientUid = conversation.ParticipantUids.FirstOrDefault(uid => uid != senderId);
        if (!string.IsNullOrEmpty(recipientUid))
        {
            await Clients.Group(recipientUid).SendAsync("NewMessage", new
            {
                id = message.Id,
                conversationId = message.ConversationId,
                senderId = message.SenderId,
                text = message.Text,
                mediaUrl = message.MediaUrl,
                timestamp = message.Timestamp,
                readBy = message.ReadBy
            });
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
            throw new HubException("Conversation not found.");
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
            await Clients.Group(otherUserId).SendAsync("MessageRead", new
            {
                conversationId,
                messageId,
                readBy = currentUserId
            });
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
            await Clients.Group(otherUserId).SendAsync("UserTyping", new
            {
                conversationId,
                userId = currentUserId
            });
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
            await Clients.Group(otherUserId).SendAsync("UserStoppedTyping", new
            {
                conversationId,
                userId = currentUserId
            });
        }
    }

    private string? GetCurrentUserId()
    {
        return Context.User?.FindFirst(ClaimTypes.NameIdentifier)?.Value ?? Context.UserIdentifier;
    }
}
