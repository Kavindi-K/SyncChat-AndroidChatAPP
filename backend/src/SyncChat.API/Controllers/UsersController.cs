using System;
using System.Security.Claims;
using System.Threading.Tasks;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using SyncChat.Application.Interfaces;
using SyncChat.Application.UseCases.Users;

namespace SyncChat.API.Controllers;

[Authorize]
[ApiController]
[Route("api/[controller]")]
public class UsersController : ControllerBase
{
    private readonly IUserRepository _userRepository;
    private readonly UserSearchUseCase _userSearchUseCase;
    private readonly ILogger<UsersController> _logger;

    public UsersController(
        IUserRepository userRepository,
        UserSearchUseCase userSearchUseCase,
        ILogger<UsersController> logger)
    {
        _userRepository = userRepository;
        _userSearchUseCase = userSearchUseCase;
        _logger = logger;
    }

    [HttpGet("{uid}")]
    public async Task<IActionResult> GetProfile(string uid)
    {
        if (string.IsNullOrWhiteSpace(uid))
            return BadRequest(new { Error = "Uid is required" });

        var user = await _userRepository.GetUserByIdAsync(uid);
        if (user == null)
            return NotFound(new { Error = "User not found" });

        return Ok(user);
    }

    [HttpGet("search")]
    public async Task<IActionResult> SearchUsers([FromQuery] string q)
    {
        // UserSearchUseCase handles validation and throws ValidationException if query is empty.
        // The GlobalExceptionMiddleware will catch ValidationException and return 400 Bad Request.
        var results = await _userSearchUseCase.ExecuteAsync(q);
        return Ok(results);
    }

    [HttpPut("me")]
    public async Task<IActionResult> UpdateProfile([FromBody] UpsertProfileRequest request)
    {
        return await UpsertProfileInternal(request);
    }

    [HttpPost("me")]
    public async Task<IActionResult> UpsertProfile([FromBody] UpsertProfileRequest request)
    {
        return await UpsertProfileInternal(request);
    }

    private async Task<IActionResult> UpsertProfileInternal(UpsertProfileRequest request)
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

            return Ok(new { Success = true, Message = "Profile updated successfully" });
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Failed to update profile for UID {Uid}", uid);
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
