using System.Security.Claims;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using SyncChat.API.Repositories;

namespace SyncChat.API.Controllers;

[Authorize]
[ApiController]
[Route("api/[controller]")]
public class UsersController : ControllerBase
{
    private readonly IUserRepository _userRepository;
    private readonly ILogger<UsersController> _logger;

    public UsersController(IUserRepository userRepository, ILogger<UsersController> logger)
    {
        _userRepository = userRepository;
        _logger = logger;
    }

    [HttpPost("me")]
    public async Task<IActionResult> UpsertProfile([FromBody] UpsertProfileRequest request)
    {
        var uid = User.FindFirst(ClaimTypes.NameIdentifier)?.Value;
        if (string.IsNullOrEmpty(uid))
            return Unauthorized(new { Error = "Uid claim missing" });

        if (string.IsNullOrWhiteSpace(request.Email) || !IsValidEmail(request.Email))
            return BadRequest(new { Error = "Invalid email format" });

        try
        {
            await _userRepository.UpsertUserAsync(
                uid,
                request.DisplayName,
                request.Email,
                request.PhotoUrl ?? string.Empty,
                request.FcmTokens ?? Array.Empty<string>());

            return Ok(new { Success = true, Message = "Profile upserted successfully" });
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Failed to upsert profile for UID {Uid}", uid);
            return StatusCode(500, new { Error = "Database update failed" });
        }
    }

    private static bool IsValidEmail(string email)
    {
        try { return new System.Net.Mail.MailAddress(email).Address == email; }
        catch { return false; }
    }
}

public record UpsertProfileRequest(
    string DisplayName,
    string Email,
    string? PhotoUrl,
    string[]? FcmTokens);
