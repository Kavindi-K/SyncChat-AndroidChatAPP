using System.Threading.Tasks;

namespace SyncChat.Application.Interfaces;

public interface INotificationService
{
    /// <summary>
    /// Sends an FCM push notification to the specified recipient.
    /// Silently ignores invalid/expired tokens — never throws.
    /// </summary>
    Task SendMessageNotificationAsync(
        string[] recipientFcmTokens,
        string senderName,
        string messagePreview,
        string conversationId,
        string senderId,
        string recipientUid);
}
