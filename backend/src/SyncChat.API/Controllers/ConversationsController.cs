using System;
using System.Security.Claims;
using System.Threading.Tasks;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using SyncChat.Application.Interfaces;
using SyncChat.Application.UseCases.Conversations;

namespace SyncChat.API.Controllers;

[Authorize]
[ApiController]
[Route("api/[controller]")]
public class ConversationsController : ControllerBase
{
    private readonly CreateConversationUseCase _createConversationUseCase;
    private readonly IConversationRepository _conversationRepository;
    private readonly ILogger<ConversationsController> _logger;

    public ConversationsController(
        CreateConversationUseCase createConversationUseCase,
        IConversationRepository conversationRepository,
        ILogger<ConversationsController> logger)
    {
        _createConversationUseCase = createConversationUseCase;
        _conversationRepository = conversationRepository;
        _logger = logger;
    }

    [HttpPost]
    public async Task<IActionResult> CreateConversation([FromBody] CreateConversationRequest request)
    {
        var currentUserId = User.FindFirst(ClaimTypes.NameIdentifier)?.Value;
        if (string.IsNullOrEmpty(currentUserId))
            return Unauthorized(new { Error = "User ID missing from claims" });

        if (string.IsNullOrWhiteSpace(request.TargetUserId))
            return BadRequest(new { Error = "Target user ID is required" });

        try
        {
            var conversation = await _createConversationUseCase.ExecuteAsync(currentUserId, request.TargetUserId);
            return Ok(conversation);
        }
        catch (FluentValidation.ValidationException valEx)
        {
            return BadRequest(new { Error = valEx.Message });
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Failed to create/get conversation between {Current} and {Target}", currentUserId, request.TargetUserId);
            return StatusCode(500, new { Error = "An error occurred while creating the conversation" });
        }
    }

    [HttpGet]
    public async Task<IActionResult> GetConversations()
    {
        var currentUserId = User.FindFirst(ClaimTypes.NameIdentifier)?.Value;
        if (string.IsNullOrEmpty(currentUserId))
            return Unauthorized(new { Error = "User ID missing from claims" });

        try
        {
            var conversations = await _conversationRepository.GetConversationsForUserAsync(currentUserId);
            return Ok(conversations);
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Failed to load conversations for user {Uid}", currentUserId);
            return StatusCode(500, new { Error = "An error occurred while loading conversations" });
        }
    }
}

public record CreateConversationRequest(string TargetUserId);
