using System;
using System.Collections.Generic;
using System.Threading.Tasks;
using SyncChat.Application.Models;

namespace SyncChat.Application.Interfaces;

public interface IMessageRepository
{
    Task<Message> SaveMessageAsync(Message message);
    Task<List<Message>> GetMessagesAsync(string conversationId, DateTime? cursor, int limit);
}
