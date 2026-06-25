using System;
using System.IO;
using System.Threading.Tasks;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;

namespace SyncChat.API.Controllers;

[Authorize]
[ApiController]
[Route("api/[controller]")]
public class UploadController : ControllerBase
{
    private readonly IWebHostEnvironment _env;
    private readonly ILogger<UploadController> _logger;

    public UploadController(IWebHostEnvironment env, ILogger<UploadController> logger)
    {
        _env = env;
        _logger = logger;
    }

    [HttpPost]
    public IActionResult GetUploadUrl([FromBody] UploadRequest request)
    {
        if (string.IsNullOrWhiteSpace(request.FileName))
            return BadRequest(new { Error = "FileName is required" });

        var fileExtension = Path.GetExtension(request.FileName);
        var uniqueFileName = $"{Guid.NewGuid():N}{fileExtension}";

        // Construct base URL from the incoming request (supports local dev host/port)
        var scheme = Request.Scheme;
        var host = Request.Host.ToUriComponent();

        var uploadUrl = $"{scheme}://{host}/api/upload/file/{uniqueFileName}";
        var downloadUrl = $"{scheme}://{host}/uploads/{uniqueFileName}";

        return Ok(new UploadResponse(uploadUrl, downloadUrl));
    }

    [HttpPut("file/{fileName}")]
    [AllowAnonymous] // Allow client PUT upload without complex Authorization requirements for media simulation
    public async Task<IActionResult> UploadFile(string fileName)
    {
        if (string.IsNullOrWhiteSpace(fileName))
            return BadRequest(new { Error = "FileName path parameter is required" });

        try
        {
            var uploadsFolder = Path.Combine(_env.WebRootPath ?? Path.Combine(Directory.GetCurrentDirectory(), "wwwroot"), "uploads");
            
            if (!Directory.Exists(uploadsFolder))
            {
                Directory.CreateDirectory(uploadsFolder);
            }

            var filePath = Path.Combine(uploadsFolder, fileName);
            
            using (var fileStream = new FileStream(filePath, FileMode.Create))
            {
                await Request.Body.CopyToAsync(fileStream);
            }

            _logger.LogInformation("File {FileName} uploaded successfully to local storage", fileName);
            return Ok(new { Success = true });
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Failed to upload file {FileName} locally", fileName);
            return StatusCode(500, new { Error = "Failed to upload file." });
        }
    }
}

public record UploadRequest(string FileName, string ContentType);
public record UploadResponse(string UploadUrl, string DownloadUrl);
