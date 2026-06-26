using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Google.Cloud.Firestore;
using SyncChat.Application.Interfaces;
using SyncChat.Application.Models;

namespace SyncChat.Infrastructure.Repositories;

public class FirestoreMessageRepository : IMessageRepository
{
    private readonly FirestoreDb _db;

    public FirestoreMessageRepository(FirestoreDb db)
    {
        _db = db;
    }

    public async Task<Message> SaveMessageAsync(Message message)
    {
        var docRef = _db.Collection("conversations")
            .Document(message.ConversationId)
            .Collection("messages")
            .Document(message.Id);

        var data = new Dictionary<string, object>
        {
            { "senderId", message.SenderId },
            { "text", message.Text },
            { "timestamp", Timestamp.FromDateTime(message.Timestamp.ToUniversalTime()) },
            { "readBy", message.ReadBy.ToList() }
        };

        if (message.MediaUrl != null)
        {
            data.Add("mediaUrl", message.MediaUrl);
        }

        await docRef.SetAsync(data);
        return message;
    }

    public async Task<List<Message>> GetMessagesAsync(string conversationId, DateTime? cursor, int limit)
    {
        var collectionRef = _db.Collection("conversations")
            .Document(conversationId)
            .Collection("messages");

        Query query = collectionRef.OrderByDescending("timestamp");

        if (cursor.HasValue)
        {
            query = query.StartAfter(Timestamp.FromDateTime(cursor.Value.ToUniversalTime()));
        }

        query = query.Limit(limit);

        var snapshot = await query.GetSnapshotAsync();
        
        return snapshot.Documents
            .Select(doc => MapSnapshotToMessage(conversationId, doc))
            .ToList();
    }

    public async Task MarkMessageAsReadAsync(string conversationId, string messageId, string userId)
    {
        var docRef = _db.Collection("conversations")
            .Document(conversationId)
            .Collection("messages")
            .Document(messageId);

        await docRef.UpdateAsync("readBy", FieldValue.ArrayUnion(userId));
    }

    private static Message MapSnapshotToMessage(string conversationId, DocumentSnapshot snapshot)
    {
        var id = snapshot.Id;
        var senderId = snapshot.ContainsField("senderId") ? snapshot.GetValue<string>("senderId") : string.Empty;
        var text = snapshot.ContainsField("text") ? snapshot.GetValue<string>("text") : string.Empty;
        var mediaUrl = snapshot.ContainsField("mediaUrl") ? snapshot.GetValue<string>("mediaUrl") : null;

        var readByList = new List<string>();
        if (snapshot.ContainsField("readBy"))
        {
            try
            {
                var list = snapshot.GetValue<List<object>>("readBy");
                if (list != null)
                {
                    foreach (var item in list)
                    {
                        if (item is string s) readByList.Add(s);
                    }
                }
            }
            catch
            {
                // Ignore readBy list parse issues
            }
        }

        var timestamp = snapshot.ContainsField("timestamp")
            ? snapshot.GetValue<Timestamp>("timestamp").ToDateTime()
            : DateTime.UtcNow;

        return new Message
        {
            Id = id,
            ConversationId = conversationId,
            SenderId = senderId,
            Text = text,
            MediaUrl = mediaUrl,
            Timestamp = timestamp,
            ReadBy = readByList.ToArray()
        };
    }
}
