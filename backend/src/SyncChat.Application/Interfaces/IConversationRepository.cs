using System.Collections.Generic;
using System.Threading.Tasks;
using SyncChat.Application.Models;

namespace SyncChat.Application.Interfaces;

public interface IConversationRepository
{
    Task<Conversation?> GetConversationByIdAsync(string id);
    Task<Conversation?> GetConversationByParticipantsAsync(string[] participantUids);
    Task<Conversation> CreateConversationAsync(Conversation conversation);
    Task<List<Conversation>> GetConversationsForUserAsync(string uid);
    Task UpdateLastMessageAsync(string id, LastMessageInfo lastMessage);
}
