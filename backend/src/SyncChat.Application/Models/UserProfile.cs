using System;

namespace SyncChat.Application.Models;

public class UserProfile
{
    public string Uid { get; set; } = string.Empty;
    public string DisplayName { get; set; } = string.Empty;
    public string Email { get; set; } = string.Empty;
    public string PhotoUrl { get; set; } = string.Empty;
    public string Bio { get; set; } = string.Empty;
    public bool IsOnline { get; set; }
    public string[] FcmTokens { get; set; } = Array.Empty<string>();
    public DateTime CreatedAt { get; set; }
}
