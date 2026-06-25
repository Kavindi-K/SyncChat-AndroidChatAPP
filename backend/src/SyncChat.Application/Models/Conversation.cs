using System;

namespace SyncChat.Application.Models;

public class Conversation
{
    public string Id { get; set; } = string.Empty;
    public string[] ParticipantUids { get; set; } = Array.Empty<string>();
    public LastMessageInfo? LastMessage { get; set; }
    public DateTime UpdatedAt { get; set; }
}

public class LastMessageInfo
{
    public string Text { get; set; } = string.Empty;
    public string SenderId { get; set; } = string.Empty;
    public DateTime Timestamp { get; set; }
}
