using System;
using System.IO;
using System.Security.Claims;
using System.Text;
using System.Threading.Tasks;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Moq;
using Google.Apis.Auth.OAuth2;
using SyncChat.API.Controllers;
using SyncChat.Application.Interfaces;
using SyncChat.Infrastructure.Services;
using Xunit;

namespace SyncChat.API.Tests;

public class UploadServiceTests
{
    private static readonly string DummyPrivateKey = 
        "-----BEGIN PRIVATE KEY-----\n" +
        "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQCev1alICY5MQv4\n" +
        "43q3ewBLYTApqHBozDBOIHldv2K7onECdc982g2s5Fkb/UKtlpXKSwmRd/WdGIxf\n" +
        "L/DxKLYyjv4GYpHpkwlO6jwDJKhKxx4jyrFiZbrq60wHx0tL2NtwOWE9rYBBgtnJ\n" +
        "RQq+pdo1fxEIidGx0GKU9L1k58kNI07/ui5QWbYJ2wKOIkD2mEswGx3u9R+8Fw16\n" +
        "W4hjjYFAO6Edp9U0UadDysotFW+KlW1bJBsOxSXKF3y/mHC6hL/ofoPLETdC3nZ7\n" +
        "FoLAN5+6tK3ja4GsDdzpu0sbawbEAvAhHr9j73LB07Y4wdlHwrG/89ufu03aSZy8\n" +
        "mvhYJs7NAgMBAAECggEADE6c2t1ks+S2XxEnt5Ind4XtNCA/uBLdyy4fqqHGokcE\n" +
        "MuLLogwiU506aVNCTZnnOqaESIz/yNzug7IASySoXMAS4o+BbMB+UBS/GqacuNC6\n" +
        "E0qeDkOrxkBVWyRbaoxr6sXlYODoGMFvvU0HJGOlnfsgE2+NMcIMOoHE2HiocMZa\n" +
        "PGsyWr59ChOHe1LvwLibbkmn1qZLnv+Un6psu+940NegXZHHRmpQk0wynLLf2ge+\n" +
        "zQr92J/f1hkALuV52z1dGBJe6WvIPhaO0lCubPUsN2cV4bnAvE04gDXWTJWZO8SM\n" +
        "WqJe/f9+X4dxlL3kOPacB2GA4EfobHunPhmevhR38QKBgQDLUprLn5Z23kcl3l0Q\n" +
        "guzh0kcM4ujdI0IyyMwti2Eu7tZpzwkCMYWaQFnA/CNfj5pvo6nbs6ZnD5nBd02v\n" +
        "f95WaFHkT8dlOy6dfYyhakI29I1LBnIXik1OQf24lafteJidUH9wkqgkuPCK3F0W\n" +
        "rVu9KP1L6adXfd2UiETLxfDApQKBgQDH4EaOCIib6OW8R4S+D4CCVnnlPypiqTJS\n" +
        "Tfqn23I6IkeNwjbR2+23mJgHxvgH0qANyrw3S/wmJWAQyqop5wHtyFHTNnXh9VcB\n" +
        "f47dWSuBjS2LSCVNYDUGVR/9zjJCHRA2Hp/Y7BNuXT3ocrc547rW1EWtwp4301pa\n" +
        "KQ6ko1CVCQKBgQCeIT6WwyVoiXNYNlq27ryA3OO5V/i3lCZ2DMkPWulYcRR88jIV\n" +
        "bKJ11zp25yIzviHkVatTXaM7YFy6pKjcp1wqY7PdF1cCmkak4fIvz85zozsIcJjn\n" +
        "Sf7ZsGU929bgz/wRzXtv9/+hn9wkg0I60tUYhvqIwc4OM6vTSzAGM4oMqQKBgH1s\n" +
        "gjm8pyTW59rXMjgl+ClR5JnzokBZFifJoSHWNf6+5hHRrp7QGILeMYCn3ZrjE+az\n" +
        "sposh2TUjUzcMB2tRWuWCaq1gRGy588b4WWLAB8CnXLKagX8+ikoH7QfwS/1luev\nHfV8ZtEZl7CW091yxxrqB3N7LdewPAFZxEOR7A4pAoGAQ7brxcyiNyt6DWMW/5af\n" +
        "BOmmd4lJsEzoj8sHO52gJqbhhzvSRByc3U8ODjhjfONJ7VsHi7seS7+l9T+b5p4o\n" +
        "ihT0jfDZOgC/HkxY0EN8d+ZGmbOrcEsLa/uFAOMVmkx7CBSQLoyAN8DysoqAghqg\n" +
        "17NmrbQKH7i5UXW8+NmtqH4=\n" +
        "-----END PRIVATE KEY-----";

    private GoogleCredential CreateDummyCredential()
    {
        var json = @$"{{
            ""type"": ""service_account"",
            ""project_id"": ""test-project-123"",
            ""private_key_id"": ""dummy-key-id"",
            ""private_key"": ""{DummyPrivateKey.Replace("\n", "\\n")}"",
            ""client_email"": ""firebase-adminsdk@test-project-123.iam.gserviceaccount.com""
        }}";
        using var stream = new MemoryStream(Encoding.UTF8.GetBytes(json));
        return GoogleCredential.FromStream(stream);
    }

    [Fact]
    public async Task UploadService_GenerateSignedUrl_ReturnsValidUrlScopedToUploadsUidUuid()
    {
        // Arrange
        var credential = CreateDummyCredential();
        var service = new FirebaseUploadService(credential, "test-project-123.appspot.com");
        var userId = "test-uid-123";
        var fileName = "photo.jpg";
        var contentType = "image/jpeg";

        // Act
        var (uploadUrl, downloadUrl) = await service.GenerateSignedUrlAsync(userId, fileName, contentType);

        // Assert
        Assert.NotNull(uploadUrl);
        Assert.StartsWith("https://storage.googleapis.com/test-project-123.appspot.com/uploads/test-uid-123/", uploadUrl);
        Assert.Contains("X-Goog-Credential", uploadUrl);
        
        Assert.NotNull(downloadUrl);
        Assert.StartsWith("https://firebasestorage.googleapis.com/v0/b/test-project-123.appspot.com/o/uploads%2Ftest-uid-123%2F", downloadUrl);
        Assert.EndsWith(".jpg?alt=media", downloadUrl);
    }

    [Fact]
    public async Task UploadController_GetUploadUrl_UidTakenFromJwtClaimsNotRequestBody()
    {
        // Arrange
        var mockUploadService = new Mock<IUploadService>();
        mockUploadService.Setup(s => s.GenerateSignedUrlAsync(It.IsAny<string>(), It.IsAny<string>(), It.IsAny<string>()))
            .ReturnsAsync(("http://signed-url", "http://download-url"));

        var controller = new UploadController(mockUploadService.Object, new Mock<Microsoft.Extensions.Logging.ILogger<UploadController>>().Object);

        // Mock JWT Authenticated User Context with claim 'test-uid-from-jwt'
        var user = new ClaimsPrincipal(new ClaimsIdentity(new[]
        {
            new Claim(ClaimTypes.NameIdentifier, "test-uid-from-jwt")
        }, "TestAuthType"));

        controller.ControllerContext = new ControllerContext
        {
            HttpContext = new DefaultHttpContext { User = user }
        };

        var request = new UploadRequest("image.png", "image/png");

        // Act
        var result = await controller.GetUploadUrl(request);

        // Assert
        var okResult = Assert.IsType<OkObjectResult>(result);
        var response = Assert.IsType<UploadResponse>(okResult.Value);
        Assert.Equal("http://signed-url", response.UploadUrl);
        Assert.Equal("http://download-url", response.DownloadUrl);

        // Verify the service was called with the UID from JWT (claims)
        mockUploadService.Verify(s => s.GenerateSignedUrlAsync("test-uid-from-jwt", "image.png", "image/png"), Times.Once);
    }
}
