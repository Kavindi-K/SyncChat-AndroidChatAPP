using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Google.Cloud.Firestore;
using SyncChat.Application.Interfaces;
using SyncChat.Application.Models;

namespace SyncChat.Infrastructure.Repositories;

public class FirestoreConversationRepository : IConversationRepository
{
    private readonly FirestoreDb _db;

    public FirestoreConversationRepository(FirestoreDb db)
    {
        _db = db;
    }

    public async Task<Conversation?> GetConversationByIdAsync(string id)
    {
        var docRef = _db.Collection("conversations").Document(id);
        var snapshot = await docRef.GetSnapshotAsync();
        if (!snapshot.Exists) return null;

        return MapSnapshotToConversation(snapshot);
    }

    public async Task<Conversation?> GetConversationByParticipantsAsync(string[] participantUids)
    {
        var query = _db.Collection("conversations")
            .WhereEqualTo("participantUids", participantUids.ToList());

        var snapshot = await query.GetSnapshotAsync();
        var doc = snapshot.Documents.FirstOrDefault();
        if (doc == null) return null;

        return MapSnapshotToConversation(doc);
    }

    public async Task<Conversation> CreateConversationAsync(Conversation conversation)
    {
        var docRef = _db.Collection("conversations").Document(conversation.Id);
        
        var data = new Dictionary<string, object>
        {
            { "participantUids", conversation.ParticipantUids.ToList() },
            { "updatedAt", Timestamp.FromDateTime(conversation.UpdatedAt.ToUniversalTime()) }
        };

        if (conversation.LastMessage != null)
        {
            data.Add("lastMessage", new Dictionary<string, object>
            {
                { "text", conversation.LastMessage.Text },
                { "senderId", conversation.LastMessage.SenderId },
                { "timestamp", Timestamp.FromDateTime(conversation.LastMessage.Timestamp.ToUniversalTime()) }
            });
        }

        await docRef.SetAsync(data);
        return conversation;
    }

    public async Task<List<Conversation>> GetConversationsForUserAsync(string uid)
    {
        var query = _db.Collection("conversations")
            .WhereArrayContains("participantUids", uid)
            .OrderByDescending("updatedAt");

        var snapshot = await query.GetSnapshotAsync();
        
        return snapshot.Documents
            .Select(MapSnapshotToConversation)
            .ToList();
    }

    public async Task UpdateLastMessageAsync(string id, LastMessageInfo lastMessage)
    {
        var docRef = _db.Collection("conversations").Document(id);
        
        var data = new Dictionary<string, object>
        {
            { "lastMessage", new Dictionary<string, object>
                {
                    { "text", lastMessage.Text },
                    { "senderId", lastMessage.SenderId },
                    { "timestamp", Timestamp.FromDateTime(lastMessage.Timestamp.ToUniversalTime()) }
                }
            },
            { "updatedAt", Timestamp.FromDateTime(DateTime.UtcNow) }
        };

        await docRef.UpdateAsync(data);
    }

    private static Conversation MapSnapshotToConversation(DocumentSnapshot snapshot)
    {
        var id = snapshot.Id;
        var participants = new List<string>();
        
        if (snapshot.ContainsField("participantUids"))
        {
            var list = snapshot.GetValue<List<object>>("participantUids");
            if (list != null)
            {
                foreach (var item in list)
                {
                    if (item is string s) participants.Add(s);
                }
            }
        }

        LastMessageInfo? lastMsg = null;
        if (snapshot.ContainsField("lastMessage"))
        {
            try
            {
                var lastMsgDict = snapshot.GetValue<Dictionary<string, object>>("lastMessage");
                if (lastMsgDict != null)
                {
                    string text = lastMsgDict.TryGetValue("text", out var t) ? t?.ToString() ?? string.Empty : string.Empty;
                    string senderId = lastMsgDict.TryGetValue("senderId", out var s) ? s?.ToString() ?? string.Empty : string.Empty;
                    
                    DateTime ts = DateTime.UtcNow;
                    if (lastMsgDict.TryGetValue("timestamp", out var tsVal))
                    {
                        if (tsVal is Timestamp timestamp)
                        {
                            ts = timestamp.ToDateTime();
                        }
                        else if (tsVal is string tsStr && DateTime.TryParse(tsStr, out var parsedTs))
                        {
                            ts = parsedTs;
                        }
                    }

                    lastMsg = new LastMessageInfo
                    {
                        Text = text,
                        SenderId = senderId,
                        Timestamp = ts
                    };
                }
            }
            catch
            {
                // Ignore parsing errors for malformed last message object
            }
        }

        var updatedAt = snapshot.ContainsField("updatedAt")
            ? snapshot.GetValue<Timestamp>("updatedAt").ToDateTime()
            : DateTime.UtcNow;

        var blockedBy = new List<string>();
        if (snapshot.ContainsField("blockedBy"))
        {
            var list = snapshot.GetValue<List<object>>("blockedBy");
            if (list != null)
            {
                foreach (var item in list)
                {
                    if (item is string s) blockedBy.Add(s);
                }
            }
        }

        return new Conversation
        {
            Id = id,
            ParticipantUids = participants.ToArray(),
            LastMessage = lastMsg,
            UpdatedAt = updatedAt,
            BlockedBy = blockedBy.ToArray()
        };
    }
}
