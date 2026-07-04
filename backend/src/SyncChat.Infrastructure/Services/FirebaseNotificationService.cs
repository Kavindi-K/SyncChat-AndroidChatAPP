using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using FirebaseAdmin.Messaging;
using SyncChat.Application.Interfaces;
using Microsoft.Extensions.Logging;

namespace SyncChat.Infrastructure.Services;

public class FirebaseNotificationService : INotificationService
{
    private readonly ILogger<FirebaseNotificationService> _logger;
    private readonly IUserRepository _userRepository;

    public FirebaseNotificationService(ILogger<FirebaseNotificationService> logger, IUserRepository userRepository)
    {
        _logger = logger;
        _userRepository = userRepository;
    }

    public async Task SendMessageNotificationAsync(
        string[] recipientFcmTokens,
        string senderName,
        string messagePreview,
        string conversationId,
        string senderId,
        string recipientUid)
    {
        if (recipientFcmTokens == null || recipientFcmTokens.Length == 0)
            return;

        // Truncate preview to 100 chars for notification
        var preview = messagePreview?.Length > 100
            ? messagePreview[..97] + "..."
            : messagePreview ?? string.Empty;

        var messages = new List<Message>();
        foreach (var token in recipientFcmTokens)
        {
            if (string.IsNullOrWhiteSpace(token)) continue;

            messages.Add(new Message
            {
                Token = token,
                Data = new Dictionary<string, string>
                {
                    { "conversationId", conversationId },
                    { "senderId", senderId },
                    { "senderName", senderName },
                    { "preview", preview },
                    { "type", "new_message" }
                },
                Android = new AndroidConfig
                {
                    Priority = Priority.High
                }
            });
        }

        if (messages.Count == 0) return;

        try
        {
            var response = await FirebaseMessaging.DefaultInstance.SendEachAsync(messages);
            _logger.LogInformation(
                "FCM SendEach: {SuccessCount} sent, {FailureCount} failed for conversation {ConversationId}",
                response.SuccessCount, response.FailureCount, conversationId);

            // Collect failed tokens that are invalid or expired (e.g. Unregistered)
            var failedTokens = new List<string>();
            for (int i = 0; i < response.Responses.Count; i++)
            {
                var res = response.Responses[i];
                if (!res.IsSuccess)
                {
                    var exception = res.Exception;
                    _logger.LogWarning("FCM message send failed. Error: {Error}, Code: {ErrorCode}", 
                        exception?.Message, exception?.MessagingErrorCode);
                    if (exception != null && 
                        (exception.MessagingErrorCode == MessagingErrorCode.Unregistered || 
                         exception.MessagingErrorCode == MessagingErrorCode.InvalidArgument))
                    {
                        failedTokens.Add(messages[i].Token);
                    }
                }
            }

            if (failedTokens.Count > 0 && !string.IsNullOrEmpty(recipientUid))
            {
                _logger.LogInformation("Removing {Count} expired FCM tokens for user {Uid}", failedTokens.Count, recipientUid);
                await _userRepository.RemoveFcmTokensAsync(recipientUid, failedTokens.ToArray());
            }
        }
        catch (Exception ex)
        {
            // Non-fatal — SignalR already delivered the message in real time
            _logger.LogWarning(ex, "FCM SendEach failed for conversation {ConversationId}", conversationId);
        }
    }
}
