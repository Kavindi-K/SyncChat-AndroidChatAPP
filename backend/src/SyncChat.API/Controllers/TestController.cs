using System;
using System.Collections.Generic;
using System.Threading.Tasks;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using SyncChat.Application.Interfaces;
using Google.Cloud.Firestore;

namespace SyncChat.API.Controllers;

[AllowAnonymous]
[ApiController]
[Route("api/test")]
public class TestController : ControllerBase
{
    private readonly INotificationService _notificationService;
    private readonly IUserRepository _userRepository;
    private readonly FirestoreDb _db;

    public TestController(INotificationService notificationService, IUserRepository userRepository, FirestoreDb db)
    {
        _notificationService = notificationService;
        _userRepository = userRepository;
        _db = db;
    }

    [HttpGet("users")]
    public async Task<IActionResult> GetUsers()
    {
        var snapshot = await _db.Collection("users").GetSnapshotAsync();
        var list = new List<object>();
        foreach (var doc in snapshot.Documents)
        {
            var fcmTokens = doc.ContainsField("fcmTokens") ? doc.GetValue<List<string>>("fcmTokens") : new List<string>();
            list.Add(new
            {
                Uid = doc.Id,
                DisplayName = doc.ContainsField("displayName") ? doc.GetValue<string>("displayName") : "",
                Email = doc.ContainsField("email") ? doc.GetValue<string>("email") : "",
                FcmTokens = fcmTokens
            });
        }
        return Ok(list);
    }

    [HttpGet("raw-user/{uid}")]
    public async Task<IActionResult> GetRawUser(string uid)
    {
        var docRef = _db.Collection("users").Document(uid);
        var snapshot = await docRef.GetSnapshotAsync();
        if (!snapshot.Exists)
        {
            return NotFound(new { Message = "User not found" });
        }
        return Ok(snapshot.ToDictionary());
    }

    [HttpPost("trigger-push")]
    public async Task<IActionResult> TriggerPush([FromBody] TriggerPushRequest request)
    {
        string[] tokens;

        if (!string.IsNullOrWhiteSpace(request.FcmToken))
        {
            tokens = new[] { request.FcmToken };
        }
        else
        {
            if (string.IsNullOrWhiteSpace(request.RecipientUid))
            {
                return BadRequest(new { Error = "Either recipientUid or fcmToken must be specified." });
            }

            var recipient = await _userRepository.GetUserByIdAsync(request.RecipientUid);
            if (recipient == null)
            {
                return NotFound(new { Error = $"Recipient user with UID '{request.RecipientUid}' not found." });
            }

            if (recipient.FcmTokens == null || recipient.FcmTokens.Length == 0)
            {
                return BadRequest(new { Error = $"Recipient user '{request.RecipientUid}' has no registered FCM tokens." });
            }

            tokens = recipient.FcmTokens;
        }

        await _notificationService.SendMessageNotificationAsync(
            tokens,
            request.SenderName,
            request.Text,
            request.ConversationId,
            request.SenderId,
            request.RecipientUid ?? "direct-test"
        );

        return Ok(new { Message = "Notification request sent to FCM!", TokensCount = tokens.Length });
    }
}

public class TriggerPushRequest
{
    public string? RecipientUid { get; set; }
    public string? FcmToken { get; set; }
    public string SenderName { get; set; } = "SyncChat";
    public string Text { get; set; } = "Test Message";
    public string ConversationId { get; set; } = "test-conv-id";
    public string SenderId { get; set; } = "test-sender-id";
}
