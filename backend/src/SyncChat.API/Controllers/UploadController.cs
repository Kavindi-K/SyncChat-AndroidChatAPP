using System;
using System.IO;
using System.Security.Claims;
using System.Threading.Tasks;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using SyncChat.Application.Interfaces;

namespace SyncChat.API.Controllers;

[Authorize]
[ApiController]
[Route("api/[controller]")]
public class UploadController : ControllerBase
{
    private readonly IUploadService _uploadService;
    private readonly ILogger<UploadController> _logger;

    public UploadController(IUploadService uploadService, ILogger<UploadController> logger)
    {
        _uploadService = uploadService;
        _logger = logger;
    }

    [HttpPost]
    public async Task<IActionResult> GetUploadUrl([FromBody] UploadRequest request)
    {
        if (string.IsNullOrWhiteSpace(request.FileName))
            return BadRequest(new { Error = "FileName is required" });
        if (string.IsNullOrWhiteSpace(request.ContentType))
            return BadRequest(new { Error = "ContentType is required" });

        var userId = User.FindFirst(ClaimTypes.NameIdentifier)?.Value;
        if (string.IsNullOrEmpty(userId))
            return Unauthorized(new { Error = "User is not authenticated." });

        try
        {
            var (uploadUrl, downloadUrl) = await _uploadService.GenerateSignedUrlAsync(userId, request.FileName, request.ContentType);
            return Ok(new UploadResponse(uploadUrl, downloadUrl));
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error generating signed URL for user {UserId}", userId);
            return StatusCode(500, new { Error = "Failed to generate upload URL." });
        }
    }
}

public record UploadRequest(string FileName, string ContentType);
public record UploadResponse(string UploadUrl, string DownloadUrl);
