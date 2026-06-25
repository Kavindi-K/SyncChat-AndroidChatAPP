using System;
using System.Security.Claims;
using System.Threading.Tasks;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using SyncChat.Application.UseCases.Messages;

namespace SyncChat.API.Controllers;

[Authorize]
[ApiController]
[Route("api/conversations/{conversationId}/messages")]
public class MessagesController : ControllerBase
{
    private readonly SendMessageUseCase _sendMessageUseCase;
    private readonly GetMessagesUseCase _getMessagesUseCase;
    private readonly ILogger<MessagesController> _logger;

    public MessagesController(
        SendMessageUseCase sendMessageUseCase,
        GetMessagesUseCase getMessagesUseCase,
        ILogger<MessagesController> logger)
    {
        _sendMessageUseCase = sendMessageUseCase;
        _getMessagesUseCase = getMessagesUseCase;
        _logger = logger;
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
