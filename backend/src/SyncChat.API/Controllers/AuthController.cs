using FirebaseAdmin.Auth;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using SyncChat.Application.Interfaces;

namespace SyncChat.API.Controllers;

[ApiController]
[Route("api/auth")]
public class AuthController : ControllerBase
{
    private readonly IUserRepository _userRepository;
    private readonly ILogger<AuthController> _logger;

    public AuthController(IUserRepository userRepository, ILogger<AuthController> logger)
    {
        _userRepository = userRepository;
        _logger = logger;
    }

    /// <summary>
    /// Anonymous endpoint — creates a new Firebase Auth user + Firestore profile document.
    /// </summary>
    [HttpPost("register")]
    [AllowAnonymous]
    public async Task<IActionResult> Register([FromBody] RegisterRequest request)
    {
        if (string.IsNullOrWhiteSpace(request.DisplayName))
            return BadRequest(new { Error = "Display name is required" });

        if (string.IsNullOrWhiteSpace(request.Email) || !IsValidEmail(request.Email))
            return BadRequest(new { Error = "Invalid email format" });

        if (string.IsNullOrWhiteSpace(request.Password) || request.Password.Length < 6)
            return BadRequest(new { Error = "Password must be at least 6 characters" });

        try
        {
            // Create the Firebase Auth user via Admin SDK
            var userArgs = new UserRecordArgs
            {
                Email = request.Email,
                Password = request.Password,
                DisplayName = request.DisplayName,
                EmailVerified = false
            };

            var userRecord = await FirebaseAuth.DefaultInstance.CreateUserAsync(userArgs);

            // Create the users/{uid} document in Firestore
            await _userRepository.UpsertUserAsync(
                userRecord.Uid,
                request.DisplayName,
                request.Email,
                string.Empty,
                string.Empty,
                Array.Empty<string>()
            );

            return Ok(new
            {
                Uid = userRecord.Uid,
                Email = userRecord.Email,
                Message = "Account created successfully"
            });
        }
        catch (FirebaseAuthException ex) when (ex.AuthErrorCode == AuthErrorCode.EmailAlreadyExists)
        {
            return Conflict(new { Error = "An account with this email already exists" });
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Registration failed for {Email}", request.Email);
            return StatusCode(500, new { Error = "Registration failed. Please try again." });
        }
    }

    private static bool IsValidEmail(string email)
    {
        try { return new System.Net.Mail.MailAddress(email).Address == email; }
        catch { return false; }
    }
}

public record RegisterRequest(string DisplayName, string Email, string Password);
