using System;

namespace SyncChat.Application.Models;

public class Message
{
    public string Id { get; set; } = string.Empty;
    public string ConversationId { get; set; } = string.Empty;
    public string SenderId { get; set; } = string.Empty;
    public string Text { get; set; } = string.Empty;
    public string? MediaUrl { get; set; }
    public DateTime Timestamp { get; set; }
    public string[] ReadBy { get; set; } = Array.Empty<string>();
}
