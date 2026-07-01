using System;
using System.IO;
using System.Threading.Tasks;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http.Features;
using Microsoft.AspNetCore.Mvc;

namespace SyncChat.API.Controllers;

[ApiController]
[Route("api/[controller]")]
public class MockStorageController : ControllerBase
{
    private readonly IWebHostEnvironment _env;

    public MockStorageController(IWebHostEnvironment env)
    {
        _env = env;
    }

    [HttpPut]
    [AllowAnonymous] // Bypasses auth checks for local PUT upload
    [DisableRequestSizeLimit] // Allow large file uploads in dev mode
    public async Task<IActionResult> UploadFile(
        [FromQuery] string userId, 
        [FromQuery] string fileName, 
        [FromQuery] string contentType)
    {
        // Guard: this endpoint only exists in Development — returns 404 in production
        if (!_env.IsDevelopment())
            return NotFound();

        if (string.IsNullOrEmpty(userId) || string.IsNullOrEmpty(fileName))
            return BadRequest("userId and fileName are required query parameters.");

        // Define target directory in wwwroot
        var webRoot = _env.WebRootPath;
        if (string.IsNullOrEmpty(webRoot))
        {
            webRoot = Path.Combine(Directory.GetCurrentDirectory(), "wwwroot");
        }

        var targetDir = Path.Combine(webRoot, "mockstorage", "uploads", userId);
        if (!Directory.Exists(targetDir))
        {
            Directory.CreateDirectory(targetDir);
        }

        var targetPath = Path.Combine(targetDir, fileName);

        // Save incoming request body stream to target file
        using (var fileStream = System.IO.File.Create(targetPath))
        {
            await Request.Body.CopyToAsync(fileStream);
        }

        return Ok(new { Message = "File uploaded successfully." });
    }
}
