using System;
using System.Security.Claims;
using System.Threading.Tasks;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.RateLimiting;
using SyncChat.Application.UseCases.Messages;
using Microsoft.AspNetCore.SignalR;
using SyncChat.API.Hubs;
using SyncChat.Application.Interfaces;

namespace SyncChat.API.Controllers;

[Authorize]
[ApiController]
[EnableRateLimiting("messagePolicy")]
[Route("api/conversations/{conversationId}/messages")]
public class MessagesController : ControllerBase
{
    private readonly SendMessageUseCase _sendMessageUseCase;
    private readonly GetMessagesUseCase _getMessagesUseCase;
    private readonly ILogger<MessagesController> _logger;
    private readonly IHubContext<ChatHub> _hubContext;
    private readonly IConversationRepository _conversationRepository;

    public MessagesController(
        SendMessageUseCase sendMessageUseCase,
        GetMessagesUseCase getMessagesUseCase,
        ILogger<MessagesController> logger,
        IHubContext<ChatHub> hubContext,
        IConversationRepository conversationRepository)
    {
        _sendMessageUseCase = sendMessageUseCase;
        _getMessagesUseCase = getMessagesUseCase;
        _logger = logger;
        _hubContext = hubContext;
        _conversationRepository = conversationRepository;
    }

    [HttpPost]
    public async Task<IActionResult> SendMessage(string conversationId, [FromBody] SendMessageRequest request)
    {
        var currentUserId = User.FindFirst(ClaimTypes.NameIdentifier)?.Value;
        if (string.IsNullOrEmpty(currentUserId))
            return Unauthorized(new { Error = "User ID missing from claims" });

        try
        {
            var input = new SendMessageInput(conversationId, currentUserId, request.Text ?? string.Empty, request.MediaUrl);
            var message = await _sendMessageUseCase.ExecuteAsync(input);

            // Broadcast via SignalR to recipient for real-time delivery (bypassing Firestore latency)
            var conversation = await _conversationRepository.GetConversationByIdAsync(conversationId);
            if (conversation != null)
            {
                var recipientUid = conversation.ParticipantUids.FirstOrDefault(uid => uid != currentUserId);
                if (!string.IsNullOrEmpty(recipientUid))
                {
                    var payloadStr = System.Text.Json.JsonSerializer.Serialize(message);
                    await _hubContext.Clients.Group(recipientUid).SendAsync("NewMessage", payloadStr);
                }
            }

            return CreatedAtAction(nameof(GetMessages), new { conversationId = conversationId }, message);
        }
        catch (FluentValidation.ValidationException valEx)
        {
            return BadRequest(new { Error = valEx.Message });
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Failed to send message in conversation {ConversationId} by user {SenderId}", conversationId, currentUserId);
            return StatusCode(500, new { Error = "An error occurred while sending the message" });
        }
    }

    [HttpGet]
    public async Task<IActionResult> GetMessages(
        string conversationId,
        [FromQuery] string? cursor = null,
        [FromQuery] int limit = 20)
    {
        var currentUserId = User.FindFirst(ClaimTypes.NameIdentifier)?.Value;
        if (string.IsNullOrEmpty(currentUserId))
            return Unauthorized(new { Error = "User ID missing from claims" });

        DateTime? cursorDateTime = null;
        if (!string.IsNullOrEmpty(cursor))
        {
            if (DateTime.TryParse(cursor, out var parsedCursor))
            {
                cursorDateTime = parsedCursor.ToUniversalTime();
            }
            else
            {
                return BadRequest(new { Error = "Invalid cursor format. Expected ISO DateTime." });
            }
        }

        try
        {
            var messages = await _getMessagesUseCase.ExecuteAsync(conversationId, currentUserId, cursorDateTime, limit);
            return Ok(messages);
        }
        catch (FluentValidation.ValidationException valEx)
        {
            return BadRequest(new { Error = valEx.Message });
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Failed to get messages for conversation {ConversationId} by user {Uid}", conversationId, currentUserId);
            return StatusCode(500, new { Error = "An error occurred while retrieving messages" });
        }
    }
}

public record SendMessageRequest(string? Text, string? MediaUrl);
